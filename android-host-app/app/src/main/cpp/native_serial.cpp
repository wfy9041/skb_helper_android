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

void throwException(JNIEnv *env, const char *className, const char *message) {
    jclass cls = env->FindClass(className);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

void throwIo(JNIEnv *env, const char *prefix) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s", prefix, strerror(errno));
    throwException(env, "java/io/IOException", message);
}

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

SerialHandle *fromHandle(jlong handle) {
    return reinterpret_cast<SerialHandle *>(static_cast<intptr_t>(handle));
}

int64_t nowMillis() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_wx_skb_hostapp_NativeSerialTransport_nativeOpen(
        JNIEnv *env, jclass, jstring devicePath, jint baudRate) {
    const char *path = env->GetStringUTFChars(devicePath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    int fd = open(path, O_RDWR | O_NOCTTY | O_NONBLOCK);
    env->ReleaseStringUTFChars(devicePath, path);
    if (fd < 0) {
        throwIo(env, "open serial port failed");
        return 0;
    }

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
