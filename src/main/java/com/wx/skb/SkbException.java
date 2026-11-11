package com.wx.skb;

/** SKB 协议或响应内容不符合预期时抛出的异常。 */
public class SkbException extends Exception {
    /** 创建带错误描述的异常。 */
    public SkbException(String message) {
        super(message);
    }

    /** 创建带错误描述和底层原因的异常。 */
    public SkbException(String message, Throwable cause) {
        super(message, cause);
    }
}
