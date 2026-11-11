#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

namespace {

struct SerialHandle {
    int fd;
};

const int kReadAvailableIdleMillis = 20;

// 向 Java 层抛出指定类型的异常。
//
// className 使用 JNI 形式的类名，例如 "java/io/IOException"。
// 这个函数只负责创建并抛出异常，不会中断 C++ 当前函数的执行流程；
// 调用方在抛出异常后仍需要显式 return，避免继续访问无效资源。
void throwException(JNIEnv *env, const char *className, const char *message) {
    jclass cls = env->FindClass(className);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

// 按当前 errno 生成 IOException。
//
// Linux 系统调用失败时通常会设置 errno，这里把业务前缀和 strerror(errno)
// 拼成完整错误信息再抛给 Java，方便上层看到类似 Permission denied、
// No such file or directory 这类真实底层原因。
void throwIo(JNIEnv *env, const char *prefix) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", prefix, strerror(errno));
    throwException(env, "java/io/IOException", message);
}

// 把 Java 传入的整数波特率转换成 termios 需要的 speed_t 常量。
//
// termios 不能直接使用 9600、115200 这类普通整数，而是使用 B9600、
// B115200 等平台常量。返回 0 表示当前实现不支持该波特率，调用方会
// 设置 EINVAL 并向 Java 抛出配置失败异常。
speed_t baudToConstant(int baudRate) {
    switch (baudRate) {
        case 1200: return B1200;
        case 2400: return B2400;
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        case 460800: return B460800;
        case 921600: return B921600;
        default: return 0;
    }
}

// 配置串口 fd 为原始 8N1 通信模式。
//
// 这里完成三件事：
// 1. 设置输入/输出波特率；
// 2. 配置 8 位数据位、无校验、1 位停止位、关闭硬件流控；
// 3. 清空输入输出缓存，并关闭 canonical/echo 等终端文本处理逻辑。
//
// VMIN/VTIME 设置为 0，配合 O_NONBLOCK 使用，读写等待统一由 poll()
// 控制，这样 Java 层可以得到明确的超时行为。
bool configurePort(int fd, int baudRate) {
    termios options;
    if (tcgetattr(fd, &options) != 0) {
        return false;
    }

    speed_t speed = baudToConstant(baudRate);
    if (speed == 0) {
        errno = EINVAL;
        return false;
    }

    cfsetispeed(&options, speed);
    cfsetospeed(&options, speed);

    options.c_cflag |= CLOCAL | CREAD;
    options.c_cflag &= ~CSIZE;
    options.c_cflag |= CS8;
    options.c_cflag &= ~PARENB;
    options.c_cflag &= ~CSTOPB;
    options.c_cflag &= ~CRTSCTS;

    options.c_iflag = 0;
    options.c_oflag = 0;
    options.c_lflag = 0;
    options.c_cc[VMIN] = 0;
    options.c_cc[VTIME] = 0;

    tcflush(fd, TCIOFLUSH);
    return tcsetattr(fd, TCSANOW, &options) == 0;
}

// 把 Java 层保存的 long 句柄还原成 C++ SerialHandle 指针。
//
// JNI 不能直接把 C++ 指针作为 Java 对象使用，所以 nativeOpen() 会把
// SerialHandle* 转成 jlong 返回给 Java；后续读写关闭时再通过这个函数
// 转回指针。调用方必须检查返回值和 fd 是否有效。
SerialHandle *fromHandle(jlong handle) {
    return reinterpret_cast<SerialHandle *>(static_cast<intptr_t>(handle));
}

// 返回单调递增时钟的毫秒数。
//
// 使用 CLOCK_MONOTONIC 而不是系统墙上时间，避免用户修改系统时间、
// NTP 校时等操作影响串口读超时计算。
int64_t nowMillis() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

}  // namespace

// Java NativeSerialTransport.nativeOpen() 的 C++ 实现。
//
// 负责打开 /dev/tty* 串口设备、配置串口参数，并把 C++ 侧的 SerialHandle
// 指针以 jlong 形式返回给 Java。成功后 Java 持有的是一个 native 句柄；
// 失败时直接抛 IOException，不返回可用句柄。
extern "C" JNIEXPORT jlong JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeOpen(
        JNIEnv *env, jclass, jstring devicePath, jint baudRate) {
    const char *path = env->GetStringUTFChars(devicePath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    // 打开代表板载串口的 Linux 字符设备，例如 /dev/ttyS0。
    //
    // O_RDWR：协议需要在同一个 fd 上写请求、读响应。
    // O_NOCTTY：避免该设备成为进程控制终端，防止终端驱动产生无关副作用。
    // O_NONBLOCK：避免 open/read/write 无限阻塞，后续读写统一用 poll()
    // 控制明确的等待时间。
    int fd = open(path, O_RDWR | O_NOCTTY | O_NONBLOCK);
    env->ReleaseStringUTFChars(devicePath, path);
    if (fd < 0) {
        throwIo(env, "open serial port failed");
        return 0;
    }

    // 将 fd 配置成原始 8N1 串口。配置失败时立即关闭 fd，避免 Java 拿到
    // 一个只打开但未正确初始化的串口句柄。这里保存 configurePort() 的
    // errno，是为了让 IOException 报告真实配置失败原因，而不是后续 close()
    // 可能覆盖出来的错误。
    if (!configurePort(fd, baudRate)) {
        int savedErrno = errno;
        close(fd);
        errno = savedErrno;
        throwIo(env, "configure serial port failed");
        return 0;
    }

    SerialHandle *serial = new SerialHandle();
    serial->fd = fd;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(serial));
}

// Java NativeSerialTransport.nativeWrite() 的 C++ 实现。
//
// 将 Java byte[] 中的完整请求帧写入串口。由于 fd 是非阻塞模式，一次
// write() 不一定能写完全部数据，所以这里循环写入，遇到 EAGAIN/EWOULDBLOCK
// 时用 poll(POLLOUT) 等待串口可写。全部写完后调用 tcdrain()，尽量确保
// 数据已经交给串口驱动发送。
extern "C" JNIEXPORT void JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeWrite(
        JNIEnv *env, jclass, jlong handle, jbyteArray data) {
    SerialHandle *serial = fromHandle(handle);
    if (serial == nullptr || serial->fd < 0) {
        throwException(env, "java/io/IOException", "serial port is closed");
        return;
    }

    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return;
    }

    int offset = 0;
    while (offset < length) {
        ssize_t written = write(serial->fd, bytes + offset, length - offset);
        if (written > 0) {
            offset += static_cast<int>(written);
            continue;
        }
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            pollfd pfd;
            pfd.fd = serial->fd;
            pfd.events = POLLOUT;
            pfd.revents = 0;
            if (poll(&pfd, 1, 1000) > 0) {
                continue;
            }
        }
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        throwIo(env, "serial write failed");
        return;
    }

    tcdrain(serial->fd);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

// Discard bytes received before the next request. This must run before writing
// the request, otherwise it could discard the ACK for that request.
extern "C" JNIEXPORT void JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeClearInput(
        JNIEnv *env, jclass, jlong handle) {
    SerialHandle *serial = fromHandle(handle);
    if (serial == nullptr || serial->fd < 0) {
        throwException(env, "java/io/IOException", "serial port is closed");
        return;
    }

    if (tcflush(serial->fd, TCIFLUSH) != 0) {
        throwIo(env, "serial input buffer flush failed");
    }
}

// Java NativeSerialTransport.nativeRead() 的 C++ 实现。
//
// 读取指定 length 字节，适合协议层已经知道响应长度的场景。函数会一直
// 累积读取，直到拿满 length 字节；如果在 timeoutMillis 内没有读到下一批
// 数据，就向 Java 抛 SerialTimeoutException。
//
// 每次成功读到数据后会重新计算 deadline，这表示 timeoutMillis 是“相邻
// 数据之间的最大等待时间”，而不是整次读取的固定总时长。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeRead(
        JNIEnv *env, jclass, jlong handle, jint length, jint timeoutMillis) {
    SerialHandle *serial = fromHandle(handle);
    if (serial == nullptr || serial->fd < 0) {
        throwException(env, "java/io/IOException", "serial port is closed");
        return nullptr;
    }
    if (length <= 0 || timeoutMillis <= 0) {
        throwException(env, "java/lang/IllegalArgumentException", "length and timeout must be positive");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(length);
    if (result == nullptr) {
        return nullptr;
    }

    jbyte *buffer = new jbyte[length];
    int offset = 0;
    int64_t deadline = nowMillis() + timeoutMillis;

    while (offset < length) {
        int remainingMs = static_cast<int>(deadline - nowMillis());
        if (remainingMs <= 0) {
            delete[] buffer;
            throwException(env, "com/wx/skb/SerialTimeoutException", "serial read timeout");
            return nullptr;
        }

        pollfd pfd;
        pfd.fd = serial->fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pollResult = poll(&pfd, 1, remainingMs);
        if (pollResult == 0) {
            delete[] buffer;
            throwException(env, "com/wx/skb/SerialTimeoutException", "serial read timeout");
            return nullptr;
        }
        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            delete[] buffer;
            throwIo(env, "serial poll failed");
            return nullptr;
        }

        ssize_t count = read(serial->fd, buffer + offset, length - offset);
        if (count > 0) {
            offset += static_cast<int>(count);
            deadline = nowMillis() + timeoutMillis;
        } else if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            delete[] buffer;
            throwIo(env, "serial read failed");
            return nullptr;
        }
    }

    env->SetByteArrayRegion(result, 0, length, buffer);
    delete[] buffer;
    return result;
}

// Java NativeSerialTransport.nativeReadUntil() 的 C++ 实现。
//
// 按“期望结束符”读取一段数据，返回内容包含结束符本身。例如结束符传入
// '\n'，则读到换行字节后立即返回。这里每次只从串口读取 1 字节，是为了
// 避免一次 read() 把结束符后面的下一帧数据也取走，导致后续读取丢数据。
//
// timeoutMillis 的语义与 nativeRead() 一致：等待第一字节和相邻字节都使用
// 这个超时时间；每成功读到 1 字节就重新计时。maxLength 用来限制最大读取
// 长度，防止设备一直不发送结束符时无限占用内存或线程。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeReadUntil(
        JNIEnv *env, jclass, jlong handle, jbyte terminator, jint maxLength, jint timeoutMillis) {
    SerialHandle *serial = fromHandle(handle);
    if (serial == nullptr || serial->fd < 0) {
        throwException(env, "java/io/IOException", "serial port is closed");
        return nullptr;
    }
    if (maxLength <= 0 || timeoutMillis <= 0) {
        throwException(env, "java/lang/IllegalArgumentException", "maxLength and timeout must be positive");
        return nullptr;
    }

    jbyte *buffer = new jbyte[maxLength];
    int offset = 0;
    int64_t deadline = nowMillis() + timeoutMillis;

    while (offset < maxLength) {
        int remainingMs = static_cast<int>(deadline - nowMillis());
        if (remainingMs <= 0) {
            delete[] buffer;
            throwException(env, "com/wx/skb/SerialTimeoutException", "serial read terminator timeout");
            return nullptr;
        }

        pollfd pfd;
        pfd.fd = serial->fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pollResult = poll(&pfd, 1, remainingMs);
        if (pollResult == 0) {
            delete[] buffer;
            throwException(env, "com/wx/skb/SerialTimeoutException", "serial read terminator timeout");
            return nullptr;
        }
        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            delete[] buffer;
            throwIo(env, "serial poll failed");
            return nullptr;
        }

        jbyte value = 0;
        ssize_t count = read(serial->fd, &value, 1);
        if (count > 0) {
            buffer[offset++] = value;
            if (value == terminator) {
                jbyteArray result = env->NewByteArray(static_cast<jsize>(offset));
                if (result != nullptr) {
                    env->SetByteArrayRegion(result, 0, static_cast<jsize>(offset), buffer);
                }
                delete[] buffer;
                return result;
            }
            deadline = nowMillis() + timeoutMillis;
            continue;
        }
        if (count == 0 || errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            continue;
        }
        delete[] buffer;
        throwIo(env, "serial read failed");
        return nullptr;
    }

    delete[] buffer;
    throwException(env, "java/io/IOException", "serial read terminator not found before max length");
    return nullptr;
}

// Java NativeSerialTransport.nativeReadAvailable() 的 C++ 实现。
//
// 读取当前可用的一段数据，最多 maxLength 字节。首次等待使用 timeoutMillis；
// 一旦已经读到至少 1 字节，后续只再等待一个很短的空闲时间
// kReadAvailableIdleMillis，用来把同一帧中紧跟着到达的字节一起收完。
//
// 这个方法适合调试、日志或无法预先确定响应长度的场景；协议固定长度读取
// 优先使用 nativeRead()。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeReadAvailable(
        JNIEnv *env, jclass, jlong handle, jint maxLength, jint timeoutMillis) {
    SerialHandle *serial = fromHandle(handle);
    if (serial == nullptr || serial->fd < 0) {
        throwException(env, "java/io/IOException", "serial port is closed");
        return nullptr;
    }
    if (maxLength <= 0 || timeoutMillis <= 0) {
        throwException(env, "java/lang/IllegalArgumentException", "maxLength and timeout must be positive");
        return nullptr;
    }

    jbyte *buffer = new jbyte[maxLength];
    int offset = 0;
    int pollTimeoutMillis = timeoutMillis;

    while (offset < maxLength) {
        pollfd pfd;
        pfd.fd = serial->fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pollResult = poll(&pfd, 1, pollTimeoutMillis);
        if (pollResult == 0) {
            if (offset > 0) {
                break;
            }
            delete[] buffer;
            throwException(env, "com/wx/skb/SerialTimeoutException", "serial read timeout");
            return nullptr;
        }
        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            delete[] buffer;
            throwIo(env, "serial poll failed");
            return nullptr;
        }

        ssize_t count = read(serial->fd, buffer + offset, maxLength - offset);
        if (count > 0) {
            offset += static_cast<int>(count);
            pollTimeoutMillis = kReadAvailableIdleMillis;
            continue;
        }
        if (count == 0 || errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            if (offset > 0) {
                break;
            }
            continue;
        }
        delete[] buffer;
        throwIo(env, "serial read failed");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(offset));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(offset), buffer);
    }
    delete[] buffer;
    return result;
}

// Java NativeSerialTransport.nativeClose() 的 C++ 实现。
//
// 关闭 nativeOpen() 创建的串口 fd，并释放 SerialHandle 内存。Java 层会把
// handle 置 0；C++ 层这里也会先把 fd 标记为 -1，避免关闭失败路径里继续把
// 该结构当成有效串口使用。
extern "C" JNIEXPORT void JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeClose(
        JNIEnv *env, jclass, jlong handle) {
    SerialHandle *serial = fromHandle(handle);
    if (serial == nullptr) {
        return;
    }
    if (serial->fd >= 0 && close(serial->fd) != 0) {
        serial->fd = -1;
        delete serial;
        throwIo(env, "serial close failed");
        return;
    }
    serial->fd = -1;
    delete serial;
}
