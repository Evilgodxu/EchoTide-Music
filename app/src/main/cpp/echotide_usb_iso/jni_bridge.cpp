// 等时输出的 JNI 桥：只做参数搬运与句柄转换，不含任何传输逻辑。
//
// 句柄即 UsbIsoStream 对象的指针，由 Kotlin 侧持有；close 之后句柄立即失效，
// 调用方必须在同一处保证不会再使用它。
#include <jni.h>
#include <android/log.h>

#include <cstddef>
#include <string>

#include "UsbIsoStream.h"

namespace {

constexpr const char* kTag = "UsbIsoNative";

UsbIsoStream* fromHandle(jlong handle) {
    return reinterpret_cast<UsbIsoStream*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_yichao_evilgodxu_data_music_playback_usb_UsbIsoNative_open(
        JNIEnv* env, jclass clazz, jint fd, jint outEndpoint, jint frameBytes, jint maxPacketBytes,
        jint feedbackEndpoint, jint feedbackMaxPacketBytes, jint sampleRate, jint interval,
        jint packetsPerUrb, jint urbCount, jint ringBytes) {
    (void) env;
    (void) clazz;
    UsbIsoStream::Config config;
    config.fd = fd;
    config.outEndpoint = uint8_t(outEndpoint);
    config.frameBytes = frameBytes;
    config.maxPacketBytes = maxPacketBytes;
    config.feedbackEndpoint = uint8_t(feedbackEndpoint);
    config.feedbackMaxPacketBytes = feedbackMaxPacketBytes;
    config.sampleRate = sampleRate;
    config.interval = interval;
    config.packetsPerUrb = packetsPerUrb;
    config.urbCount = urbCount;
    config.ringBytes = ringBytes;

    std::string error;
    std::unique_ptr<UsbIsoStream> stream = UsbIsoStream::create(config, error);
    if (stream == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "建流失败：%s", error.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(stream.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yichao_evilgodxu_data_music_playback_usb_UsbIsoNative_write(
        JNIEnv* env, jclass clazz, jlong handle, jobject pcm, jint offset, jint bytes) {
    (void) clazz;
    UsbIsoStream* stream = fromHandle(handle);
    if (stream == nullptr || bytes <= 0) return -1;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(pcm));
    if (base == nullptr) return -1;
    const long written = stream->write(base + offset, size_t(bytes));
    return jint(written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yichao_evilgodxu_data_music_playback_usb_UsbIsoNative_drain(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (UsbIsoStream* stream = fromHandle(handle)) stream->drain();
}

extern "C" JNIEXPORT void JNICALL
Java_com_yichao_evilgodxu_data_music_playback_usb_UsbIsoNative_setPaused(
        JNIEnv* env, jclass clazz, jlong handle, jboolean paused) {
    (void) env;
    (void) clazz;
    if (UsbIsoStream* stream = fromHandle(handle)) stream->setPaused(paused != JNI_FALSE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yichao_evilgodxu_data_music_playback_usb_UsbIsoNative_stop(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (UsbIsoStream* stream = fromHandle(handle)) stream->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_yichao_evilgodxu_data_music_playback_usb_UsbIsoNative_close(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    // 析构中会停止送流并收回在飞 URB，故关闭无需先调 stop
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_yichao_evilgodxu_data_music_playback_usb_UsbIsoNative_stats(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    // 顺序与 Kotlin 侧 Stats 的构造一一对应：装入帧数、送出帧数、欠载次数、少送帧数、
    // URB 错误数、传输错误数、是否断开
    constexpr int kValueCount = 7;
    jlong values[kValueCount] = {0, 0, 0, 0, 0, 0, 0};
    if (UsbIsoStream* stream = fromHandle(handle)) {
        const UsbIsoStream::Stats stats = stream->stats();
        values[0] = jlong(stats.framesSent);
        values[1] = jlong(stats.framesPlayed);
        values[2] = jlong(stats.underruns);
        values[3] = jlong(stats.missedFrames);
        values[4] = jlong(stats.urbErrors);
        values[5] = jlong(stats.transferErrors);
        values[6] = stats.disconnected ? 1 : 0;
    }
    jlongArray result = env->NewLongArray(kValueCount);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, kValueCount, values);
    return result;
}
