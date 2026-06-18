package com.wx.skb.hostapp;

import com.wx.skb.SerialTransport;
import java.io.IOException;

public final class NativeSerialTransport implements SerialTransport {
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
    public synchronized byte[] read(int length, int timeoutMillis) throws IOException {
        ensureOpen();
        return nativeRead(handle, length, timeoutMillis);
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

    private static native byte[] nativeRead(long handle, int length, int timeoutMillis) throws IOException;

    private static native void nativeClose(long handle) throws IOException;
}
