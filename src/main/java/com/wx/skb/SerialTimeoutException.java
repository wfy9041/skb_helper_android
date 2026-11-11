package com.wx.skb;

import java.io.IOException;

/** 串口在指定时间内未读到完整响应时抛出的超时异常。 */
public class SerialTimeoutException extends IOException {
    /** 创建带错误描述的超时异常。 */
    public SerialTimeoutException(String message) {
        super(message);
    }
}
