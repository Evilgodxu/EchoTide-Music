#include "UsbIsoStream.h"

#include <errno.h>
#include <linux/usb/ch9.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>

namespace {

/** 等时包完成状态：-EXDEV 表示部分包出错，属正常范畴，不当成整条传输失败 */
constexpr int kStatusPartial = -EXDEV;

/**
 * 无完成事件时每次等待的上限（毫秒）。
 *
 * 回收线程不用阻塞式 REAPURB 而自行等待完成事件，就是为了让停机路径有确定的收尾时机：阻塞式调用在
 * "此刻恰好没有在飞的 URB"时会一直等下去，停机就卡在 join 上——表现是整个进程失去响应。
 */
constexpr int kReapWaitMs = 5;

/** 停机后等待在飞 URB 回收的上限：超时即放弃释放其内存（见析构），不再无限等下去 */
constexpr int kDrainTimeoutMs = 200;

/**
 * 回收线程的调度优先级：音频类的 -19（平台侧等价常量 ANDROID_PRIORITY_URGENT_AUDIO）。
 *
 * 本线程的截止期是**一个服务间隔**（高速下 1ms）：每回收一个 URB 就要立刻补一个上去，否则在飞段一旦
 * 被送空，设备侧收到的就是断续。默认优先级下，别的应用启动时那一波 CPU 抢占就足以让本线程误期，
 * 而它误期的表现恰好绕开所有既有计数——环里有数据，欠载计数不响；URB 正常完成，传输错误计数不响。
 */
constexpr int kReapThreadPriority = -19;

std::string errnoText(int code) {
    return std::string(strerror(code)) + " (" + std::to_string(code) + ")";
}

uint32_t readU24(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16);
}

uint32_t readU32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}

/**
 * 提交失败是否意味着这条流再无可能恢复。
 *
 * 设备已拔出、连接已关闭、接口已归还，这三类都让后续提交必然同样失败——任其反复失败会让环再也腾不出
 * 空间，播放线程随之永久阻塞。其余错误（如瞬时带宽不足）留给下一次机会即可。
 */
bool isFatalSubmitError(int code) {
    return code == ENODEV || code == ESHUTDOWN || code == EBADF || code == ENOENT || code == ENXIO;
}

/** 把 usbdevfs_urb 及其尾部柔性数组成员安置进给定的存储块 */
usbdevfs_urb* placeUrb(std::vector<uint64_t>& storage, int packetCount) {
    const size_t bytes = sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) * size_t(packetCount);
    storage.assign((bytes + sizeof(uint64_t) - 1) / sizeof(uint64_t), 0);
    return reinterpret_cast<usbdevfs_urb*>(storage.data());
}

}  // namespace

std::unique_ptr<UsbIsoStream> UsbIsoStream::create(const Config& config, std::string& error) {
    auto stream = std::unique_ptr<UsbIsoStream>(new UsbIsoStream());
    if (!stream->start(config, error)) return nullptr;
    return stream;
}

UsbIsoStream::~UsbIsoStream() {
    stop();
    // 回收通道已断时会有 URB 落在内核手里。内核持有 usbdevfs_urb 结构与数据缓冲的指针，并在回收时把
    // 状态与各包实际长度写回其中，此刻释放用户态内存即 use-after-free——症状是进程无声消失、不留异常。
    // 故这种情形下**故意不释放**：把这一块留在进程里（仅在设备异常消失且回收通道已断时出现），
    // 换取绝不误伤无关内存
    if (abandoned_.load()) {
        for (auto& urb : urbs_) {
            (void) urb.release();
        }
        if (feedback_) (void) feedback_.release();
    }
    // 关闭本层自己复制的那份 fd（见 start）。停机后回收线程已 join，此时不再有 ioctl 在飞
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

bool UsbIsoStream::start(const Config& config, std::string& error) {
    config_ = config;
    if (config_.fd < 0 || config_.frameBytes <= 0 || config_.maxPacketBytes <= 0 ||
        config_.sampleRate <= 0 || config_.packetsPerUrb <= 0 || config_.urbCount <= 0) {
        error = "建流参数不完整";
        return false;
    }
    // 自己复制一份 fd 而不是直接用它：调用方随时可能因拔插或归还设备而关掉连接，而内核会把那个编号
    // 立刻回收给别的打开操作——继续用原编号发 ioctl 就打到别人的文件上去了。复制后本层的生命周期
    // 与调用方解耦：即便连接已关，本层收到的也只是设备已不可用，而不会误伤无关的文件
    fd_ = ::dup(config_.fd);
    if (fd_ < 0) {
        error = "复制设备文件描述符失败：" + errnoText(errno);
        return false;
    }

    // 服务间隔频率取决于 USB 速度：高速为 8000 个微帧/秒，其余为 1000 个帧/秒。
    // 速度取自平台自身的查询口（libusb 也是这么得到的），拿不到时按最大包长反推：
    // 全速等时端点的单包上限为 1023 字节，超过它只可能是更快的总线
    const int speed = ioctl(fd_, USBDEVFS_GET_SPEED);
    if (speed >= 0) {
        highSpeed_ = speed >= USB_SPEED_HIGH;
    } else {
        highSpeed_ = config_.maxPacketBytes > 1023;
    }
    // bInterval 大于 1 表示每 2^(n-1) 个服务间隔才发一包，包随之变大；封顶到 8 个间隔
    const int shift = std::clamp(config_.interval, 1, 16) - 1;
    packetsPerSecond_ = (highSpeed_ ? 8000 : 1000) >> std::min(shift, 3);

    maxFramesPerPacket_ = uint32_t(config_.maxPacketBytes / config_.frameBytes);
    if (maxFramesPerPacket_ == 0) {
        error = "端点的单包容量放不下一个音频帧";
        return false;
    }
    const uint32_t nominalFrames = uint32_t(config_.sampleRate) / uint32_t(packetsPerSecond_);
    if (nominalFrames == 0 || nominalFrames > maxFramesPerPacket_) {
        error = "端点带宽容不下该采样率：" + std::to_string(config_.sampleRate) + "Hz 需 " +
                std::to_string(config_.sampleRate / packetsPerSecond_) + " 帧/包，上限为 " +
                std::to_string(maxFramesPerPacket_) + " 帧";
        return false;
    }
    // 标称每包帧数是分数，用 16.16 定点保存，由相位累积摊到整数包上
    nominalQ16_.store(uint32_t((uint64_t(config_.sampleRate) << 16) / uint64_t(packetsPerSecond_)));

    // 在飞 URB 数按**时长**求出，而不是按固定个数：余量只有落在时间上才有意义。每个 URB 的时长是
    // packetsPerUrb 个服务间隔——高速下 8 包仅 1ms，配置的 8 个 URB 合起来才 8ms，一次外部应用抢 CPU
    // 就足以把整段在飞耗光；全速下一个 URB 本就长达 8 个服务间隔，既有配置已经够用，不跟着目标再翻倍。
    // 停机排空按本值等待，故它是唯一的在飞口径
    const int usPerUrb = std::max(1, config_.packetsPerUrb * 1000000 / packetsPerSecond_);
    const int targetUrbs = (config_.inFlightTargetMs * 1000 + usPerUrb - 1) / usPerUrb;
    urbCount_ = std::max(config_.urbCount, targetUrbs);
    inFlightUs_ = int64_t(usPerUrb) * int64_t(urbCount_);

    ring_.assign(size_t(config_.ringBytes), 0);
    ringHead_ = 0;
    ringSize_ = 0;
    // 环长由调用方按延迟预算给定，且不得短于整段在飞——环比它短时播放线程写不进、回收线程也腾不出，
    // 表现为周期性停顿，该下限由 UsbIsoOutput 在调用前保证

    urbs_.reserve(size_t(urbCount_));
    for (int i = 0; i < urbCount_; ++i) {
        auto urb = std::make_unique<Urb>();
        urb->urb = placeUrb(urb->descriptor, config_.packetsPerUrb);
        urb->buffer.assign(size_t(config_.packetsPerUrb) * size_t(config_.maxPacketBytes), 0);
        urbs_.push_back(std::move(urb));
    }
    if (config_.feedbackEndpoint != 0) {
        feedback_ = std::make_unique<FeedbackUrb>();
        feedback_->urb = placeUrb(feedback_->descriptor, 1);
        const int packetBytes =
            config_.feedbackMaxPacketBytes > 0 ? config_.feedbackMaxPacketBytes : (highSpeed_ ? 4 : 3);
        feedback_->buffer.assign(size_t(packetBytes), 0);
    }

    running_ = true;
    // 先把所有 URB 投出去再起回收线程：投递与回收分处两线程，回收线程阻塞等待完成事件
    for (auto& urb : urbs_) fillAndSubmit(urb->urb);
    if (feedback_) submitFeedback();
    reapThread_ = std::thread([this] { reapLoop(); });
    return true;
}

long UsbIsoStream::write(const uint8_t* data, size_t bytes) {
    size_t written = 0;
    std::unique_lock<std::mutex> lock(ringMutex_);
    while (written < bytes) {
        if (!running_.load() || disconnected_.load()) {
            return written > 0 ? long(written) : -1;
        }
        const size_t free = ring_.size() - ringSize_;
        if (free == 0) {
            // 暂停期间环只进不出，继续等就是永久阻塞——而暂停与恢复都走调用方那一条线程。
            // 故就地把已接纳的部分交回去，余量由调用方按"下游已满"自行重试
            if (paused_.load()) break;
            // 等回收线程腾出空间；超时只是为了避免在无通知时永久阻塞
            ringCv_.wait_for(lock, std::chrono::milliseconds(20));
            continue;
        }
        const size_t chunk = std::min(free, bytes - written);
        const size_t tail = (ringHead_ + ringSize_) % ring_.size();
        const size_t first = std::min(chunk, ring_.size() - tail);
        std::memcpy(ring_.data() + tail, data + written, first);
        if (chunk > first) std::memcpy(ring_.data(), data + written + first, chunk - first);
        ringSize_ += chunk;
        written += chunk;
        // 一有真实音频即退出「未起播」态：起播前的空环不算欠载，否则每首开头都会记一次假欠载
        primed_ = true;
    }
    return long(written);
}

void UsbIsoStream::drain() {
    {
        std::unique_lock<std::mutex> lock(ringMutex_);
        while (ringSize_ > 0 && running_.load() && !disconnected_.load()) {
            ringCv_.wait_for(lock, std::chrono::milliseconds(10));
        }
    }
    // 环已空，但在飞的 URB 仍按服务间隔逐个送出；等这段时间过完才算真的送尽
    std::this_thread::sleep_for(std::chrono::microseconds(inFlightUs_));
}

void UsbIsoStream::setPaused(bool paused) {
    if (paused_.exchange(paused) == paused) return;
    // 恢复时唤醒可能正在等环空间的播放线程：暂停期间环只进不出，写满即阻塞
    if (!paused) ringCv_.notify_all();
}

void UsbIsoStream::stop() {
    // 先落 running_ 再撤 URB：fillAndSubmit 的入口以此为准，此后不会再投出新的 URB，
    // 撤下的那一批因此就是最后一批
    running_ = false;
    discardAll();
    // join 返回即保证在飞 URB 已全部回收（或已标记放弃）。析构路径与关闭 fd 都以此为前提：
    // 内核持有未回收 URB 的指针，早一刻释放就是 use-after-free
    if (reapThread_.joinable()) reapThread_.join();
    {
        std::lock_guard<std::mutex> lock(ringMutex_);
        ringHead_ = 0;
        ringSize_ = 0;
    }
    ringCv_.notify_all();
}

UsbIsoStream::Stats UsbIsoStream::stats() const {
    Stats result;
    result.framesSent = framesSent_.load();
    result.framesPlayed = framesPlayed_.load();
    result.underruns = underruns_.load();
    result.transferErrors = transferErrors_.load();
    result.disconnected = disconnected_.load();
    return result;
}

void UsbIsoStream::reapLoop() {
    using SteadyClock = std::chrono::steady_clock;
    // 本线程按音频线程的优先级运行（见 kReapThreadPriority）：提交与回收都在这条线上，误期即断流。
    // 提升失败（进程的 nice 下限不允许）不是致命错误——照常送流，只是回到了默认优先级下的脆弱状态
    setpriority(PRIO_PROCESS, 0, kReapThreadPriority);
    SteadyClock::time_point drainDeadline{};
    while (true) {
        const bool running = running_.load();
        const int pending = inFlightCount_.load();
        if (!running) {
            // 停机后只回收、不再投递：在飞的那些必须全部收回来，否则析构路径就不敢释放它们的内存
            if (pending == 0) break;
            if (drainDeadline == SteadyClock::time_point{}) {
                drainDeadline = SteadyClock::now() + std::chrono::milliseconds(kDrainTimeoutMs);
            } else if (SteadyClock::now() > drainDeadline) {
                abandoned_ = true;
                break;
            }
        }

        usbdevfs_urb* completed = nullptr;
        // 非阻塞回收 + 等完成事件：usbfs 在有待回收 URB 时置 POLLOUT，故这是"等"而非"轮询"。
        // 不用阻塞式 REAPURB 的原因在停机：那时若恰好没有在飞 URB（提交全部失败等情形），
        // 阻塞式调用会一直等下去，停机就卡在 join 上
        if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &completed) < 0) {
            const int code = errno;
            if (code == EINTR) continue;
            if (code == EAGAIN || code == EWOULDBLOCK) {
                if (!running_.load() && inFlightCount_.load() == 0) break;
                pollfd waiting{fd_, POLLOUT, 0};
                const int ready = poll(&waiting, 1, kReapWaitMs);
                // 设备断开时 poll 立刻以 POLLHUP 返回而不置 POLLOUT，此时短等一次再试，
                // 免得在"再也等不到完成事件"的状态下空转
                if (ready > 0 && (waiting.revents & (POLLHUP | POLLERR)) != 0 &&
                    (waiting.revents & POLLOUT) == 0) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(1));
                }
                continue;
            }
            // 回收通道已断（设备消失或连接关闭）：此后任何 URB 都收不回来，故记下放弃
            disconnected_ = true;
            running_ = false;
            if (inFlightCount_.load() > 0) abandoned_ = true;
            break;
        }
        if (completed == nullptr) continue;

        if (feedback_ && completed == feedback_->urb) {
            feedback_->inFlight = false;
            inFlightCount_.fetch_sub(1);
            const int received = completed->iso_frame_desc[0].actual_length != 0
                                     ? int(completed->iso_frame_desc[0].actual_length)
                                     : int(completed->actual_length);
            if (completed->status == 0 && received > 0) {
                onFeedback(feedback_->buffer.data(), received);
            }
            if (running_.load()) submitFeedback();
            continue;
        }

        Urb* slot = findSlot(completed);
        // 不属于本层的 URB：不该出现，忽略以免误减在飞数
        if (slot == nullptr) continue;
        slot->inFlight = false;
        inFlightCount_.fetch_sub(1);

        if (completed->status != 0 && completed->status != kStatusPartial) {
            ++transferErrors_;
            if (completed->status == -ENODEV || completed->status == -ESHUTDOWN) {
                // 设备已不可用：不再投递，但**继续回收**——剩下的那些仍在内核手里，
                // 此刻退出会让它们永远收不回来，析构路径就只能放弃释放
                disconnected_ = true;
                running_ = false;
                continue;
            }
        }
        // 回收即表示这一批已由主机侧送出，按各包实际长度累计供播放位置取用。撤下的 URB 以错误完成，
        // 其 actual_length 不反映真实送出量，故只在成功与部分成功时计入
        if (completed->status == 0 || completed->status == kStatusPartial) {
            uint64_t played = 0;
            const unsigned packets = completed->number_of_packets;
            for (unsigned i = 0; i < packets; ++i) {
                played += completed->iso_frame_desc[i].actual_length;
            }
            // 只计入真实音频：环空时补出的静音不是"已播出的内容"，把它一并计入会让播放位置越过已写出的
            // 量，媒体3 据此判输出已空并把播放状态打回缓冲中
            const uint64_t actualFrames = played / size_t(config_.frameBytes);
            framesPlayed_ += std::min<uint64_t>(slot->realBytes / size_t(config_.frameBytes), actualFrames);
        }
        if (running_.load()) fillAndSubmit(completed);
    }
    // 收尾：仍有在飞即意味着它们的内存不能释放，供析构路径判断
    if (inFlightCount_.load() > 0) abandoned_ = true;
}

UsbIsoStream::Urb* UsbIsoStream::findSlot(usbdevfs_urb* urb) {
    // 回收返回的就是提交时那个指针
    for (auto& candidate : urbs_) {
        if (candidate->urb == urb) return candidate.get();
    }
    return nullptr;
}

void UsbIsoStream::fillAndSubmit(usbdevfs_urb* urb) {
    Urb* slot = findSlot(urb);
    if (slot == nullptr) return;
    // 停机后不再投递：在飞的那些要全部收回来，此时再投出新的就永远收不回来了
    if (!running_.load()) return;

    const bool paused = paused_.load();
    uint8_t* buffer = slot->buffer.data();
    size_t offset = 0;
    size_t realBytes = 0;
    for (int i = 0; i < config_.packetsPerUrb; ++i) {
        const size_t want = size_t(nextPacketFrames()) * size_t(config_.frameBytes);
        size_t got = 0;
        // 暂停期间不从环里取数据：环里攒着的正是"已写出而未播出"的部分，取走它等于把待播量抹平
        if (!paused) {
            std::lock_guard<std::mutex> lock(ringMutex_);
            got = readRing(buffer + offset, want);
        }
        if (got < want) {
            // 补零而非缩短包长：等时端点上短包会让设备的时间轴出现缺口并产生可闻的断续
            std::fill(buffer + offset + got, buffer + offset + want, uint8_t(0));
            // 暂停不是欠载：此刻本就不该有数据送出
            if (!paused && primed_.load()) {
                ++underruns_;
            }
        }
        realBytes += got;
        framesSent_ += got / size_t(config_.frameBytes);
        urb->iso_frame_desc[i].length = static_cast<unsigned int>(want);
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
        offset += want;
    }
    slot->realBytes = realBytes;

    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = config_.outEndpoint;
    urb->status = 0;
    // ISO_ASAP：不指定起始帧，由内核在下一个可用服务间隔送出，避免自算帧号带来的相位偏差
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = buffer;
    urb->buffer_length = static_cast<int>(offset);
    urb->actual_length = 0;
    urb->start_frame = 0;
    urb->number_of_packets = config_.packetsPerUrb;
    urb->error_count = 0;
    urb->signr = 0;
    urb->usercontext = nullptr;

    if (ioctl(fd_, USBDEVFS_SUBMITURB, urb) < 0) {
        const int code = errno;
        ++transferErrors_;
        slot->inFlight = false;
        // 提交失败若是设备已不在或接口已被归还，则这条流再无可能恢复：不判定终止的话环再也腾不出空间，
        // 播放线程会一直等在那里——表现为整曲卡死而不是安静地失败
        if (isFatalSubmitError(code)) {
            disconnected_ = true;
            running_ = false;
            ringCv_.notify_all();
        }
        return;
    }
    slot->inFlight = true;
    inFlightCount_.fetch_add(1);
    // 环里腾出了空间，唤醒可能正在等空间的播放线程
    ringCv_.notify_one();
}

void UsbIsoStream::submitFeedback() {
    if (!feedback_ || feedbackDisabled_.load()) return;
    usbdevfs_urb* urb = feedback_->urb;
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = config_.feedbackEndpoint;
    urb->status = 0;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = feedback_->buffer.data();
    urb->buffer_length = static_cast<int>(feedback_->buffer.size());
    urb->actual_length = 0;
    urb->start_frame = 0;
    urb->number_of_packets = 1;
    urb->error_count = 0;
    urb->signr = 0;
    urb->usercontext = nullptr;
    urb->iso_frame_desc[0].length = static_cast<unsigned int>(feedback_->buffer.size());
    urb->iso_frame_desc[0].actual_length = 0;
    urb->iso_frame_desc[0].status = 0;
    if (ioctl(fd_, USBDEVFS_SUBMITURB, urb) < 0) {
        const int code = errno;
        ++transferErrors_;
        feedback_->inFlight = false;
        // 反馈端点提交不上不必终止整条流：它只是自适应时钟的跟随手段，缺了包长退回标称值而已。
        // 但**不能就此释放它**——停机路径可能正拿它的指针去撤 URB，故只作废、留着内存
        if (isFatalSubmitError(code)) {
            feedbackDisabled_ = true;
        }
        return;
    }
    feedback_->inFlight = true;
    inFlightCount_.fetch_add(1);
}

void UsbIsoStream::discardAll() {
    // 撤下在飞的 URB：内核会把它们以失败完成，回收线程因此得以把它们收回来。
    // 恰好在此刻刚提交、没被撤到的那一个不必特殊处理——它是等时 URB，最迟一个 URB 的时长后自行完成，
    // 停机路径会等它回到手上再释放（见 reapLoop 的收尾与析构）
    for (auto& urb : urbs_) {
        if (urb->inFlight.exchange(false)) {
            ioctl(fd_, USBDEVFS_DISCARDURB, urb->urb);
        }
    }
    if (feedback_ && feedback_->inFlight.exchange(false)) {
        ioctl(fd_, USBDEVFS_DISCARDURB, feedback_->urb);
    }
}

uint32_t UsbIsoStream::nextPacketFrames() {
    uint32_t nominal = nominalQ16_.load();
    const uint32_t measured = feedbackQ16_.load();
    if (measured != 0) {
        // 一阶平滑：直接把包长切到反馈值会让每包帧数跳变，听起来是周期性的时基抖动
        nominal = (nominal * 7 + measured) / 8;
        nominalQ16_.store(nominal);
    }
    phase_ += nominal;
    const uint32_t frames = phase_ >> 16;
    phase_ &= 0xFFFF;
    return std::min(frames, maxFramesPerPacket_);
}

void UsbIsoStream::onFeedback(const uint8_t* data, int length) {
    if (length < 3) return;
    uint64_t value = length >= 4 ? (readU32(data) & 0x0FFFFFFFu) : readU24(data);
    // 设备在时钟锁定前恒发 0，此时不能据此调整包长
    if (value == 0) return;
    // 全速设备按 10.14 上报，统一折算到 16.16
    if (!highSpeed_) value <<= 2;
    const uint64_t nominal = nominalQ16_.load();
    if (nominal == 0) return;
    if (feedbackShift_ == kUnknownShift) {
        // 部分设备按另一速度的格式上报，故先找出能把值落到标称附近的位移，与内核驱动同口径
        int shift = 0;
        while (value < nominal - nominal / 4 && shift < 8) {
            value <<= 1;
            ++shift;
        }
        while (value > nominal + nominal / 2 && shift > -8) {
            value >>= 1;
            --shift;
        }
        feedbackShift_ = shift;
    } else if (feedbackShift_ >= 0) {
        value <<= feedbackShift_;
    } else {
        value >>= -feedbackShift_;
    }
    // 偏离标称八分之一以上者一律丢弃：这类值通常来自拔插或设备重启的瞬态
    if (value < nominal - nominal / 8 || value > nominal + nominal / 8) return;
    feedbackQ16_.store(uint32_t(value));
}

size_t UsbIsoStream::readRing(uint8_t* destination, size_t bytes) {
    const size_t take = std::min(bytes, ringSize_);
    if (take == 0) return 0;
    const size_t first = std::min(take, ring_.size() - ringHead_);
    std::memcpy(destination, ring_.data() + ringHead_, first);
    const size_t second = take - first;
    if (second > 0) std::memcpy(destination + first, ring_.data(), second);
    ringHead_ = (ringHead_ + take) % ring_.size();
    ringSize_ -= take;
    return take;
}
