// 原生崩溃记录器：让"进程无声消失"变成一条可读的记录。
//
// Java 侧的未捕获异常由 CrashLogManager 落盘，但原生层的 SIGSEGV / SIGABRT 会直接终止进程，
// 一条日志都留不下——这正是"闪退却无崩溃日志"最可能的来源。本文件装一个极小的信号处理器，
// 只用异步信号安全的系统调用（write / gettid），把信号号、出错地址与线程号写进独立的
// native_crash.log；处理完恢复默认处置并重发信号，tombstone 照常生成，崩溃语义不变。
//
// 它只负责**记录**，不改变进程如何死。若某次"进程消失"连这里都没记到，那多半不是原生崩溃，
// 而是被系统回收（LMK / ANR 等），须另看 logcat 的 am_kill 与 dumpsys activity exit-info。
#include <jni.h>
#include <android/log.h>

#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

namespace {

constexpr const char* kTag = "EchoTideCrash";

int g_crashFd = -1;

const char* signalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        case SIGSYS: return "SIGSYS";
        default: return "SIG?";
    }
}

void crashHandler(int sig, siginfo_t* info, void* /*ucontext*/) {
    // snprintf 对 %d/%x/%s 在 C 区域是可重入的，debuggerd 与 bionic 自身也这么做；
    // 其余只用 write / gettid 这类异步信号安全的调用
    char line[256];
    const int n = snprintf(line, sizeof(line), "原生崩溃: 信号 %d (%s), 地址 0x%llx, 线程 %d\n",
                           sig, signalName(sig),
                           (unsigned long long)(info != nullptr ? (uintptr_t)info->si_addr : 0),
                           static_cast<int>(gettid()));
    if (n > 0 && g_crashFd >= 0) {
        const ssize_t ignored = write(g_crashFd, line, static_cast<size_t>(n));
        (void) ignored;
    }
    __android_log_write(ANDROID_LOG_FATAL, kTag, line);
    // 恢复默认处置并重发，让系统照常生成 tombstone
    signal(sig, SIG_DFL);
    raise(sig);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_yichao_evilgodxu_log_NativeCrashMonitor_nativeInstall(JNIEnv* env, jclass /*clazz*/,
                                                               jstring path) {
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) return;
    const int fd = open(utf, O_WRONLY | O_CREAT | O_APPEND, 0666);
    env->ReleaseStringUTFChars(path, utf);
    g_crashFd = fd;  // 打开失败时为 -1，此时只走 logcat

    // 独立信号栈：栈溢出的 SIGSEGV 也能记下，而不是在处理器里再次崩溃
    static uint8_t stack[SIGSTKSZ * 4];
    stack_t ss;
    ss.ss_sp = stack;
    ss.ss_size = sizeof(stack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    const int signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP, SIGSYS};
    for (int sig : signals) {
        sigaction(sig, &sa, nullptr);
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "原生崩溃记录已安装（fd=%d）", fd);
}
