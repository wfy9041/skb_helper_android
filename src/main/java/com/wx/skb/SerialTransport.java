package com.wx.skb;

import java.io.Closeable;
import java.io.IOException;

/** SKB 协议客户端使用的串口传输抽象，可用于替换底层串口实现或编写测试。 */
public interface SerialTransport extends Closeable {
    /** 写入完整协议帧。 */
    void write(byte[] data) throws IOException;

    /** 清空尚未处理的输入数据。 */
    void clearInputBuffer() throws IOException;

    /** 读取指定长度的数据；超时由实现抛出 {@link SerialTimeoutException}。 */
    byte[] read(int length, int timeoutMillis) throws IOException;

    /** 关闭传输资源。 */
    @Override
    void close() throws IOException;
}
