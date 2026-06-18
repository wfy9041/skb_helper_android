package com.wx.skb;

import java.io.IOException;

public class SerialTimeoutException extends IOException {
    public SerialTimeoutException(String message) {
        super(message);
    }
}
