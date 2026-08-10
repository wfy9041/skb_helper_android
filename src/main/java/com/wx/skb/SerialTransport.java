package com.wx.skb;

import java.io.Closeable;
import java.io.IOException;

public interface SerialTransport extends Closeable {
    void write(byte[] data) throws IOException;

    void clearInputBuffer() throws IOException;

    byte[] read(int length, int timeoutMillis) throws IOException;

    @Override
    void close() throws IOException;
}
