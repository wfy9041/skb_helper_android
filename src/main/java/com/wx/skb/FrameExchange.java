package com.wx.skb;

import java.util.Arrays;

/** 一次命令的原始发送帧和接收帧。 */
public final class FrameExchange {
    private final byte[] requestFrame;
    private final byte[] responseFrame;

    public FrameExchange(byte[] requestFrame, byte[] responseFrame) {
        this.requestFrame = requestFrame == null ? new byte[0] : requestFrame.clone();
        this.responseFrame = responseFrame == null ? new byte[0] : responseFrame.clone();
    }

    public byte[] getRequestFrame() { return Arrays.copyOf(requestFrame, requestFrame.length); }
    public byte[] getResponseFrame() { return Arrays.copyOf(responseFrame, responseFrame.length); }
}
