#include "UsbIsoStream.h"

#include <android/log.h>
#include <errno.h>
#include <linux/usb/ch9.h>
#include <linux/usbdevice_fs.h>
#include <string.h>
#include <sys/ioctl.h>

#include <algorithm>
#include <chrono>
#include <cstring>

namespace {

constexpr const char* kTag = "UsbIsoStream";

/** 等时包完成状态：-EXDEV 表示部分包出错，属正常范畴，不当成整条传输失败 */
constexpr int kStatusPartial = -EXDEV;

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
}

void logError(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

std::string errnoText(int code) {
    return std::string(strerror(code)) + " (" + std::to_string(code) + ")";
}

uint32_t readU24(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16);
}

uint32_t readU32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
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
}

bool UsbIsoStream::start(const Config& config, std::string& error) {
    config_ = config;
    if (config_.fd < 0 || config_.frameBytes <= 0 || config_.maxPacketBytes <= 0 ||
        config_.sampleRate <= 0 || config_.packetsPerUrb <= 0 || config_.urbCount <= 0) {
        error = "建流参数不完整";
        return false;
    }
    fd_ = config_.fd;

    // 服务间隔频率取决于 USB 速度：高速为 8000 个微帧/秒，其余为 1000 个帧/秒。
    // 速度取自平台自身的查询口（libusb 也是这么得到的），拿不到时按最大包长反推：
    // 全速等时端点的单包上限为 1023 字节，超过它只可能是更快的总线
    const int speed = ioctl(fd_, USBDEVFS_GET_SPEED);
    if (speed >= 0) {
        highSpeed_ = speed >= USB_SPEED_HIGH;
    } else {
        highSpeed_ = config_.maxPacketBytes > 1023;
        logInfo("速度查询不可用，按最大包长推断为" + std::string(highSpeed_ ? "高速" : "全速"));
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

    ring_.assign(size_t(config_.ringBytes), 0);
    ringHead_ = 0;
    ringSize_ = 0;

    urbs_.reserve(size_t(config_.urbCount));
    for (int i = 0; i < config_.urbCount; ++i) {
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

    logInfo("等时输出已建立：" + std::string(highSpeed_ ? "高速" : "全速") + "，服务间隔 " +
            std::to_string(packetsPerSecond_) + "/秒，每包 " +
            std::to_string(nominalQ16_.load() / 65536.0) + " 帧（上限 " +
            std::to_string(maxFramesPerPacket_) + "），" + std::to_string(config_.urbCount) + " 个 URB × " +
            std::to_string(config_.packetsPerUrb) + " 包");
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
    const long us = 1000000L * config_.packetsPerUrb * config_.urbCount / std::max(1, packetsPerSecond_);
    std::this_thread::sleep_for(std::chrono::microseconds(us));
}

void UsbIsoStream::stop() {
    running_ = false;
    discardAll();
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
    while (running_.load()) {
        usbdevfs_urb* completed = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURB, &completed) < 0) {
            const int code = errno;
            if (code == EINTR) continue;
            if (!running_.load()) break;
            // 设备被拔出或连接已关闭：两条路都意味着本次输出终止
            logError("回收 URB 失败：" + errnoText(code));
            disconnected_ = true;
            running_ = false;
            break;
        }
        if (completed == nullptr) continue;

        if (feedback_ && completed == feedback_->urb) {
            feedback_->inFlight = false;
            const int received = completed->iso_frame_desc[0].actual_length != 0
                                     ? int(completed->iso_frame_desc[0].actual_length)
                                     : int(completed->actual_length);
            if (completed->status == 0 && received > 0) {
                onFeedback(feedback_->buffer.data(), received);
            }
            if (running_.load()) submitFeedback();
            continue;
        }

        if (completed->status != 0 && completed->status != kStatusPartial) {
            ++transferErrors_;
            if (completed->status == -ENODEV || completed->status == -ESHUTDOWN) {
                logError("数据端点已不可用，本次输出终止");
                disconnected_ = true;
                running_ = false;
                break;
            }
        }
        if (!running_.load()) break;
        // 回收即表示这一批已由主机侧送出，按各包实际长度累计供播放位置取用。撤下的 URB 以错误完成，
        // 其 actual_length 不反映真实送出量，故只在成功与部分成功时计入
        if (completed->status == 0 || completed->status == kStatusPartial) {
            uint64_t played = 0;
            const unsigned packets = completed->number_of_packets;
            for (unsigned i = 0; i < packets; ++i) {
                played += completed->iso_frame_desc[i].actual_length;
            }
            framesPlayed_ += played / size_t(config_.frameBytes);
        }
        fillAndSubmit(completed);
    }
}

void UsbIsoStream::fillAndSubmit(usbdevfs_urb* urb) {
    // 找出该 URB 对应的槽位：回收返回的就是提交时那个指针
    Urb* slot = nullptr;
    for (auto& candidate : urbs_) {
        if (candidate->urb == urb) {
            slot = candidate.get();
            break;
        }
    }
    if (slot == nullptr) return;

    uint8_t* buffer = slot->buffer.data();
    size_t offset = 0;
    for (int i = 0; i < config_.packetsPerUrb; ++i) {
        const size_t want = size_t(nextPacketFrames()) * size_t(config_.frameBytes);
        size_t got = 0;
        {
            std::lock_guard<std::mutex> lock(ringMutex_);
            got = readRing(buffer + offset, want);
        }
        if (got < want) {
            // 补零而非缩短包长：等时端点上短包会让设备的时间轴出现缺口并产生可闻的断续
            std::fill(buffer + offset + got, buffer + offset + want, uint8_t(0));
            if (primed_.load()) ++underruns_;
        }
        framesSent_ += got / size_t(config_.frameBytes);
        urb->iso_frame_desc[i].length = static_cast<unsigned int>(want);
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
        offset += want;
    }

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
        ++transferErrors_;
        slot->inFlight = false;
        return;
    }
    slot->inFlight = true;
    // 环里腾出了空间，唤醒可能正在等空间的播放线程
    ringCv_.notify_one();
}

void UsbIsoStream::submitFeedback() {
    if (!feedback_) return;
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
        ++transferErrors_;
        feedback_->inFlight = false;
        return;
    }
    feedback_->inFlight = true;
}

void UsbIsoStream::discardAll() {
    // 撤下在飞的 URB：内核会把它们以失败完成，阻塞在回收上的线程因此得以返回
    for (auto& urb : urbs_) {
        if (urb->inFlight) {
            ioctl(fd_, USBDEVFS_DISCARDURB, urb->urb);
            urb->inFlight = false;
        }
    }
    if (feedback_ && feedback_->inFlight) {
        ioctl(fd_, USBDEVFS_DISCARDURB, feedback_->urb);
        feedback_->inFlight = false;
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
        logInfo("反馈格式位移定为 " + std::to_string(shift));
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
