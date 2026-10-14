cmake_minimum_required(VERSION 3.22.1)
project(usbiso CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只用 NDK 自带的 platform 头（linux/usbdevice_fs.h 给出 usbfs 的 URB 结构体与 ioctl 编号），
# 不引入任何第三方库：等时传输所需的全部 ABI 都在平台 sysroot 里
add_library(usbiso SHARED
        usbiso/UsbIsoStream.cpp
        usbiso/jni_bridge.cpp)

target_compile_options(usbiso PRIVATE -Wall -Wextra)
target_link_libraries(usbiso log)
