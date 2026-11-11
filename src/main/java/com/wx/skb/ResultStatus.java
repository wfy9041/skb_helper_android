package com.wx.skb;

/** SDK 命令执行结果状态。 */
public enum ResultStatus {
    SUCCESS,
    RECEIVE_TIMEOUT,
    FRAME_PARSE_FAILED,
    UNSUPPORTED,
    IO_ERROR,
    INVALID_PARAMETER,
    OTHER_ERROR
}
