package com.wx.skb.hostapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.wx.skb.SkbClient;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程级 SKB Android SDK 入口。
 *
 * <p>SDK 在内部串行管理一个串口连接。所有回调和监听通知均在主线程执行，串口收发和
 * 重连在 SDK 的工作线程执行。使用前先调用 {@link #init(Context, String, Callback)}。</p>
 */
public final class SkbSdk {
    /** 当前 SDK 版本。 */
    public static final String VERSION = "1.2.0";
    /** 串口连接状态。 */
    public enum State {
        /** 未连接。 */
        DISCONNECTED,
        /** 正在打开串口。 */
        CONNECTING,
        /** 串口已连接，可以发起业务命令。 */
        CONNECTED,
        /** 连接异常，SDK 正在等待自动重连。 */
        RECONNECTING
    }

    /** 异步操作结果回调；两个方法均在主线程执行。 */
    public interface Callback<T> {
        /** 操作成功；带返回值的操作通过 {@code value} 返回结果。 */
        void onSuccess(T value);
        /** 操作失败；具体原因可能是超时、串口 I/O、协议错误或 SDK 状态错误。 */
        void onError(Exception error);
    }

    /** 连接、重连、调试和收发日志监听器；通知在主线程执行。 */
    public interface Listener {
        /** 收到 SDK 状态或日志事件。 */
        void onEvent(State state, String devicePath, String message);
    }

    /** SDK 操作失败时使用的状态异常。 */
    public static final class SdkException extends Exception {
        private final String code;
        /** 创建带错误码和描述的 SDK 异常。 */
        public SdkException(String code, String message) { super(message); this.code = code; }
        /** 返回机器可判断的错误码，例如 {@code DEBUG_BUSY} 或 {@code NOT_CONNECTED}。 */
        public String getCode() { return code; }
    }
    /** 锁板命令返回门的状态；状态值由 SDK 转换为业务含义。 */
    public static final class DoorResult {
        private final int lockId;
        private final int status;
        private final byte[] requestFrame;
        private final byte[] responseFrame;

        private DoorResult(int lockId, int status, byte[] requestFrame, byte[] responseFrame) {
            this.lockId = lockId;
            this.status = status;
            this.requestFrame = requestFrame == null ? new byte[0] : requestFrame.clone();
            this.responseFrame = responseFrame == null ? new byte[0] : responseFrame.clone();
        }
        /** 返回锁号。 */
        public int getLockId() { return lockId; }
        /** 返回门状态：1 表示打开，0 表示关闭。 */
        public int getStatus() { return status; }
        /** 返回门是否已打开。 */
        public boolean isOpen() { return status == 1; }
        /** 返回本次请求发送的完整原始帧。 */
        public byte[] getRequestFrame() { return Arrays.copyOf(requestFrame, requestFrame.length); }
        /** 返回本次响应收到的完整原始帧。 */
        public byte[] getResponseFrame() { return Arrays.copyOf(responseFrame, responseFrame.length); }
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ManagedSerialSession SESSION = new ManagedSerialSession(
            path -> new NativeSerialTransport(path, 9600));
    static {
        SESSION.observe((state, path, message) -> {
            for (Listener listener : LISTENERS) MAIN.post(() -> {
                if (LISTENERS.contains(listener)) listener.onEvent(state, path, message);
            });
        });
    }
    private SkbSdk() {}
    static <T> ManagedSerialSession.Result<T> callback(Callback<T> callback) {
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        return (value, error) -> MAIN.post(() -> {
            if (error == null) callback.onSuccess(value); else callback.onError(error);
        });
    }
    /** 初始化或切换共享串口连接；成功后才应启用业务操作。 */
    public static void init(Context context, String devicePath, Callback<Void> callback) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        SESSION.init(null, devicePath, callback(callback));
    }
    /** 返回当前连接状态。 */
    public static State getState() { return SESSION.state(); }
    /** 返回当前使用的串口设备路径；未连接时可能为 {@code null}。 */
    public static String getDevicePath() { return SESSION.path(); }
    /** 返回人工调试界面是否正在接管共享连接。 */
    public static boolean isDebugging() { return SESSION.debugging(); }
    /** 注册连接和日志监听器；重复注册同一实例不会产生重复通知。 */
    public static void addListener(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        LISTENERS.addIfAbsent(listener);
    }
    /** 移除已注册的监听器。传入未注册的实例不会产生影响。 */
    public static void removeListener(Listener listener) { LISTENERS.remove(listener); }
    /** 关闭串口并取消自动重连；之后仍可再次调用 {@link #init(Context, String, Callback)}。 */
    public static void shutdown(Callback<Void> callback) { SESSION.shutdown(null, callback(callback)); }

    /** 打开人工调试界面；界面使用当前 SDK 共享的串口连接。 */
    public static void openDebug(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    /** 瞬时开锁，使用默认响应超时；成功回调返回锁板反馈门的状态。 */
    public static void openLock(int lockId, Callback<DoorResult> callback) {
        openLock(lockId, SkbClient.DEFAULT_TIMEOUT_MILLIS, callback);
    }
    /** 瞬时开锁，并指定本次响应读取超时（毫秒）。 */
    public static void openLock(int lockId, int timeoutMillis, Callback<DoorResult> callback) {
        commandDoor(skb -> skb.openLock(lockId, timeoutMillis), lockId, callback);
    }
    /** 设置锁为常开。 */
    public static void keepOpen(int lockId, Callback<DoorResult> callback) {
        commandDoor(skb -> skb.keepOpen(lockId, SkbClient.DEFAULT_TIMEOUT_MILLIS), lockId, callback);
    }
    /** 设置锁为常关。 */
    public static void keepClose(int lockId, Callback<DoorResult> callback) {
        commandDoor(skb -> skb.keepClose(lockId, SkbClient.DEFAULT_TIMEOUT_MILLIS), lockId, callback);
    }
    /** 设置延时关锁；延时单位为 50 毫秒。 */
    public static void delayClose(int lockId, long delayTicks50ms, Callback<DoorResult> callback) {
        commandDoor(skb -> skb.delayClose(lockId, delayTicks50ms, SkbClient.DEFAULT_TIMEOUT_MILLIS), lockId, callback);
    }
    /** 查询锁板门状态。 */
    public static void getDoorStatus(int lockId, Callback<SkbClient.StatusResult> callback) {
        SESSION.command(null, skb -> skb.getDoorStatus(lockId, SkbClient.DEFAULT_TIMEOUT_MILLIS), callback(callback));
    }
    /** 从 1 号锁开始动态查询所有锁板的门状态。 */
    public static void getAllDoorStatus(Callback<List<SkbClient.StatusResult>> callback) {
        SESSION.command(null, skb -> skb.getAllDoorStatus(SkbClient.DEFAULT_TIMEOUT_MILLIS), callback(callback));
    }
    /** 查询指定锁号是否存在。 */
    public static void existCheck(int lockId, Callback<SkbClient.StatusResult> callback) {
        SESSION.command(null, skb -> skb.existCheck(lockId, SkbClient.DEFAULT_TIMEOUT_MILLIS), callback(callback));
    }
    /** 设置锁号起止范围。 */
    public static void setAddressRange(int start, int end, Callback<SkbClient.Ack> callback) {
        commandAck(skb -> skb.setAddressRange(start, end, SkbClient.DEFAULT_TIMEOUT_MILLIS), callback);
    }
    /** 设置逻辑锁号到物理通道的映射。 */
    public static void setChannelMap(int lockId, int channel, Callback<SkbClient.Ack> callback) {
        commandAck(skb -> skb.setChannelMap(lockId, channel, SkbClient.DEFAULT_TIMEOUT_MILLIS), callback);
    }
    /** 设置请求设备地址；后续重连仍沿用该地址。 */
    public static void setDeviceAddress(int address, Callback<Void> callback) {
        SESSION.setDeviceAddress(address, callback(callback));
    }
    /** 发送需要 ACK 的自定义功能码命令；数据会复制后异步入队。 */
    public static void sendRaw(int function, byte[] data, int timeout, Callback<SkbClient.Ack> callback) {
        byte[] copy = data.clone();
        SESSION.command(null, skb -> skb.sendRaw(function, copy, timeout), callback(callback));
    }

    /** 设置起始锁号；该协议命令不等待 ACK。 */
    public static void setStartLockId(int lockId, Callback<Void> callback) {
        SESSION.command(null, skb -> { skb.setStartLockId(lockId); return null; }, callback(callback));
    }
    /** 设置结束锁号。 */
    public static void setEndLockId(int lockId, Callback<SkbClient.Ack> callback) {
        commandAck(skb -> skb.setEndLockId(lockId, SkbClient.DEFAULT_TIMEOUT_MILLIS), callback);
    }
    /** 发送不等待 ACK 的自定义功能码命令；数据会复制后异步入队。 */
    public static void sendRawNoAck(int function, byte[] data, Callback<Void> callback) {
        byte[] copy = data.clone();
        SESSION.command(null, skb -> { skb.sendRawNoAck(function, copy); return null; }, callback(callback));
    }

    private interface AckCommand {
        SkbClient.Ack execute(SkbClient skb) throws Exception;
    }

    private static void commandAck(AckCommand command, Callback<SkbClient.Ack> callback) {
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        SESSION.command(null, command::execute, callback(callback));
    }

    private static void commandDoor(AckCommand command, int requestedLockId, Callback<DoorResult> callback) {
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        SESSION.command(null, skb -> toDoorResult(command.execute(skb), requestedLockId), callback(callback));
    }

    private static DoorResult toDoorResult(SkbClient.Ack ack, int requestedLockId) throws SdkException {
        if (ack == null) throw new SdkException("INVALID_BOARD_RESPONSE", "锁板未返回门状态");
        byte[] data = ack.getData();
        if (data.length < 2) throw new SdkException("INVALID_BOARD_RESPONSE", "锁板门状态数据不足");
        int responseLockId = data[0] & 0xff;
        int status = data[1] & 0xff;
        if (responseLockId != requestedLockId) {
            throw new SdkException("INVALID_BOARD_RESPONSE", "锁板返回了错误的锁号=" + responseLockId);
        }
        if (status != 0 && status != 1) {
            throw new SdkException("INVALID_BOARD_RESPONSE", "锁板返回了未知门状态=" + status);
        }
        return new DoorResult(responseLockId, status,
                ack.getRequestFrame(), ack.getResponseFrame());
    }

    // Activity-only access: never expose the live client or transport to host applications.
    static void enterDebug(Object owner, Callback<Void> cb) { SESSION.enter(owner, callback(cb)); }
    static void leaveDebug(Object owner) { SESSION.leave(owner); }
    static void debugInit(Object owner, String path, Callback<Void> cb) { SESSION.init(owner, path, callback(cb)); }
    static void debugDisconnect(Object owner, Callback<Void> cb) { SESSION.shutdown(owner, callback(cb)); }
    static <T> void debugCommand(Object owner, ManagedSerialSession.Command<T> command, Callback<T> cb) {
        SESSION.command(owner, command, callback(cb));
    }
    static void debugRaw(Object owner, byte[] data, Callback<Void> cb) { SESSION.raw(owner, data, callback(cb)); }
}
