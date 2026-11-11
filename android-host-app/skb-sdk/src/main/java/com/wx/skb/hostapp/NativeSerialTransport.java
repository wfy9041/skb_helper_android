package com.wx.skb.hostapp;

import com.wx.skb.SerialTransport;
import java.io.IOException;

public final class NativeSerialTransport implements ManagedSerialSession.Port {
    static {
        System.loadLibrary("skb_serial");
    }

    private long handle;

    public NativeSerialTransport(String devicePath, int baudRate) throws IOException {
        handle = nativeOpen(devicePath, baudRate);
    }

    @Override
    public synchronized void write(byte[] data) throws IOException {
        ensureOpen();
        nativeWrite(handle, data);
    }

    @Override
    public synchronized void clearInputBuffer() throws IOException {
        ensureOpen();
        nativeClearInput(handle);
    }

    @Override
    public synchronized byte[] read(int length, int timeoutMillis) throws IOException {
        ensureOpen();
        return nativeRead(handle, length, timeoutMillis);
    }

    public synchronized byte[] readAvailable(int maxLength, int timeoutMillis) throws IOException {
        ensureOpen();
        return nativeReadAvailable(handle, maxLength, timeoutMillis);
    }

    public synchronized byte[] readUntil(byte terminator, int maxLength, int timeoutMillis) throws IOException {
        ensureOpen();
        return nativeReadUntil(handle, terminator, maxLength, timeoutMillis);
    }

    @Override
    public synchronized void close() throws IOException {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }

    private void ensureOpen() throws IOException {
        if (handle == 0) {
            throw new IOException("serial port is closed");
        }
    }

    private static native long nativeOpen(String devicePath, int baudRate) throws IOException;

    private static native void nativeWrite(long handle, byte[] data) throws IOException;

    private static native void nativeClearInput(long handle) throws IOException;

    private static native byte[] nativeRead(long handle, int length, int timeoutMillis) throws IOException;

    private static native byte[] nativeReadAvailable(long handle, int maxLength, int timeoutMillis) throws IOException;

    private static native byte[] nativeReadUntil(
            long handle, byte terminator, int maxLength, int timeoutMillis) throws IOException;

    private static native void nativeClose(long handle) throws IOException;
}
