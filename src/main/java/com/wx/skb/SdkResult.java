package com.wx.skb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 所有 SDK 业务命令的统一返回结果。 */
public final class SdkResult<T> {
    private final ResultStatus status;
    private final T data;
    private final List<FrameExchange> exchanges;
    private final String message;
    private final Exception error;

    public SdkResult(ResultStatus status, T data, List<FrameExchange> exchanges,
                     String message, Exception error) {
        this.status = status;
        this.data = data;
        this.exchanges = Collections.unmodifiableList(new ArrayList<FrameExchange>(exchanges));
        this.message = message;
        this.error = error;
    }

    public ResultStatus getStatus() { return status; }
    public T getData() { return data; }
    public List<FrameExchange> getExchanges() { return exchanges; }
    public String getMessage() { return message; }
    public Exception getError() { return error; }
    public boolean isSuccess() { return status == ResultStatus.SUCCESS; }
}
