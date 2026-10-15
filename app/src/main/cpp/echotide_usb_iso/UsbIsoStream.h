// 自写的 usbfs 等时输出：不经内核音频栈，直接把 PCM 交给 USB 音频端点的等时 URB 环。
//
// 之所以要有这一层：Java 的 USB 主机 API 只支持批量与中断传输（UsbRequest 的类注释明写
// "bulk and interrupt endpoints"，UsbEndpoint 亦标注等时端点不受支持），而 USB 音频的数据流走的正是
// 等时端点。故数据的最后一公里必须由原生层经 usbfs 提交 URB 完成——本文件即那一层的核心。
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

/** usbfs 的 URB 结构体由平台头文件给出，此处只前置声明以免把内核头带进所有引用方 */
struct usbdevfs_urb;

class UsbIsoStream {
public:
    /**
     * 建流参数。速度相关量由本层自行求解，不要求调用方了解 USB 速度：
     * 服务间隔频率取自设备 fd 的速度查询与端点的 bInterval，标称每包帧数再由采样率与此频率算出。
     */
    struct Config {
        int fd = -1;
        uint8_t outEndpoint = 0;
        /** 每帧字节数：声道数 × 子槽字节数 */
        int frameBytes = 0;
        /** 端点声明的每服务间隔最大字节数，决定单包帧数上限 */
        int maxPacketBytes = 0;
        /** 显式反馈端点；0 表示设备为同步或自适应，无需跟随 */
        uint8_t feedbackEndpoint = 0;
        int feedbackMaxPacketBytes = 0;
        int sampleRate = 0;
        /** 端点的 bInterval：高速下服务间隔为 2^(bInterval-1) 个微帧 */
        int interval = 1;
        /** 每个 URB 承载的服务间隔数 */
        int packetsPerUrb = 0;
        /** 在飞 URB 个数的**下限**：本层另按 [inFlightTargetMs] 与真实包率抬高，见 start() */
        int urbCount = 0;
        /** 在飞段的目标时长（毫秒）：调度余量按时间给定，由本层折算成 URB 个数 */
        int inFlightTargetMs = 0;
        /** 环形缓冲容量（字节） */
        int ringBytes = 0;
    };

    struct Stats {
        /** 已装入 URB 的帧数：提交即计入 */
        uint64_t framesSent = 0;
        /**
         * 已由主机侧送出的帧数：URB 回收时按各包实际长度累计。
         *
         * 与 framesSent 差着在飞 URB 那一整段（本层按目标时长折算，高速下约 16ms、全速下约 64ms）。
         * 播放位置只能取自本值——按提交量报位置会让进度条与曲末判定整体偏前，而设备内部缓冲的那点余量
         * 已小到不必再管。
         */
        uint64_t framesPlayed = 0;
        uint64_t underruns = 0;
        uint64_t transferErrors = 0;
        bool disconnected = false;
    };

    /** 建流并立即开始送出静音；参数不可用时返回 nullptr */
    static std::unique_ptr<UsbIsoStream> create(const Config& config);

    ~UsbIsoStream();
    UsbIsoStream(const UsbIsoStream&) = delete;
    UsbIsoStream& operator=(const UsbIsoStream&) = delete;

    /**
     * 阻塞写入，供播放线程调用。环形缓冲写满时阻塞，而非丢弃数据——直出链路上丢一帧就是一声爆音。
     *
     * @return 实际接纳的字节数；流已停止或设备已拔出时返回 -1，调用方据此结束本次输出。
     */
    long write(const uint8_t* data, size_t bytes);

    /** 等到已写入的数据全部送出设备；曲末排空用 */
    void drain();

    /**
     * 暂停送流：停止从环形缓冲取数据，改用静音填充。
     *
     * 这一段必须由本层做，不能在调用方"不再写入"了事：**环里已写入而尚未送出的数据就是"待播量"**，
     * 媒体3 的待播判据（`AudioSink.hasPendingData`）正据此决定输出是否就绪。平台路径上暂停会让音频轨
     * 停止消费，待播量因此保留；本层若继续把环里的数据投出去，待播量会一路归零，媒体3 据此判输出已空、
     * 把播放状态打回缓冲中并停掉渲染器——表现就是"一暂停就卡住、再播起不来"。
     *
     * 设备侧仍持续收到静音包，故时钟不会被放开，恢复时无需重新锁钟。
     */
    void setPaused(bool paused);

    /** 停止送流；可重复调用 */
    void stop();

    Stats stats() const;

private:
    UsbIsoStream() = default;

    struct Urb;
    struct FeedbackUrb;

    bool start(const Config& config);
    void reapLoop();
    Urb* findSlot(usbdevfs_urb* urb);
    void fillAndSubmit(usbdevfs_urb* urb);
    void submitFeedback();
    void discardAll();
    uint32_t nextPacketFrames();
    void onFeedback(const uint8_t* data, int length);
    size_t readRing(uint8_t* destination, size_t bytes);
    struct Urb {
        /** usbdevfs_urb 及其尾部柔性数组成员 iso_frame_desc[] 的存放地 */
        std::vector<uint64_t> descriptor;
        /** 数据缓冲：packetsPerUrb × maxPacketBytes，按最坏情况分配以免单包越界 */
        std::vector<uint8_t> buffer;
        usbdevfs_urb* urb = nullptr;
        /** 是否已被提交给内核：回收线程与停机路径都会读写，故按原子量发布 */
        std::atomic<bool> inFlight{false};
        /** 本批中包含的真实音频字节数：回收时据此计入"已送出"，静音填充的部分不算 */
        size_t realBytes = 0;
    };

    struct FeedbackUrb {
        std::vector<uint64_t> descriptor;
        std::vector<uint8_t> buffer;
        usbdevfs_urb* urb = nullptr;
        std::atomic<bool> inFlight{false};
    };

    Config config_;
    std::vector<std::unique_ptr<Urb>> urbs_;
    std::unique_ptr<FeedbackUrb> feedback_;
    /**
     * 反馈端点已不可用：不再提交，但**不释放** [feedback_]。
     *
     * 释放会让停机路径手里的指针失效（它可能正拿这个指针去撤 URB），而反馈只是自适应时钟的跟随手段，
     * 缺了它包长退回标称值即可，不值得为它引入一处悬空指针。
     */
    std::atomic<bool> feedbackDisabled_{false};
    /** 本层自己持有的 fd：建流时从调用方给的 fd 复制一份，见 start() */
    int fd_ = -1;
    bool highSpeed_ = false;
    int packetsPerSecond_ = 0;
    uint32_t maxFramesPerPacket_ = 0;
    /** 实际使用的在飞 URB 个数：由配置下限与目标时长共同求出，见 start() */
    int urbCount_ = 0;
    /** 在飞段的时长（微秒）：主机侧能撑住的时间上限，drain 按它等最后一段送出 */
    int64_t inFlightUs_ = 0;

    // 环形缓冲：播放线程写、回收线程读
    std::vector<uint8_t> ring_;
    size_t ringHead_ = 0;
    size_t ringSize_ = 0;
    mutable std::mutex ringMutex_;
    std::condition_variable ringCv_;

    // 分包相位：标称每包帧数是分数（如 44.1kHz 高速下为 5.5125），靠 16.16 相位累积摊到整数包上，
    // 长期平均才等于真实速率；直接取整会让时基偏离并在若干秒后累积成可闻的断续
    uint32_t phase_ = 0;
    std::atomic<uint32_t> nominalQ16_{0};
    std::atomic<uint32_t> feedbackQ16_{0};

    /** 反馈值的格式位移，与内核驱动的自适应口径一致；kUnknownShift 表示尚未定标 */
    static constexpr int kUnknownShift = -100;
    int feedbackShift_ = kUnknownShift;

    std::thread reapThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> primed_{false};
    std::atomic<bool> disconnected_{false};
    /**
     * 在内核手里的 URB 个数（含反馈端点那个）。
     *
     * 停机路径据此判断"能不能释放这些内存"：内核持有 usbdevfs_urb 结构体与数据缓冲的指针，并在回收时
     * 写回状态与各包实际长度，故**未回收的 URB 一经释放就是 use-after-free**——写回落在已被复用或已归还
     * 的堆内存上，进程可能当场消失且不留任何异常。
     */
    std::atomic<int> inFlightCount_{0};
    /** 停机时未能回收的 URB 数大于零：此时必须放弃释放，见析构 */
    std::atomic<bool> abandoned_{false};
    std::atomic<uint64_t> framesSent_{0};
    std::atomic<uint64_t> framesPlayed_{0};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<uint64_t> transferErrors_{0};
};
