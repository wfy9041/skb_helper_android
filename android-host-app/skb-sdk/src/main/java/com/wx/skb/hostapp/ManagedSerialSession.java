package com.wx.skb.hostapp;

import com.wx.skb.SerialTransport;
import com.wx.skb.SerialTimeoutException;
import com.wx.skb.SkbClient;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** All transport access, including passive reads and reconnects, uses one worker. */
final class ManagedSerialSession {
    interface Port extends SerialTransport {
        byte[] readAvailable(int length, int timeoutMillis) throws IOException;
    }
    interface Factory { Port open(String path) throws IOException; }
    interface Result<T> { void complete(T value, Exception error); }
    interface Command<T> { T run(SkbClient client) throws Exception; }
    interface Observer { void changed(SkbSdk.State state, String path, String message); }

    private final Factory factory;
    private final ScheduledExecutorService worker;
    private final Object gate = new Object();
    private Object debugOwner;
    private volatile Observer observer;
    private volatile SkbSdk.State state = SkbSdk.State.DISCONNECTED;
    private volatile String path;
    private Port port;
    private SkbClient client;
    private ScheduledFuture<?> recovery;
    private boolean wanted;
    private int deviceAddress = 1;

    ManagedSerialSession(Factory factory) {
        this.factory = factory;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "skb-serial");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 50, 50, TimeUnit.MILLISECONDS);
    }

    void observe(Observer observer) { this.observer = observer; }
    SkbSdk.State state() { return state; }
    String path() { return path; }
    boolean debugging() { synchronized (gate) { return debugOwner != null; } }

    private Exception denied(Object owner) {
        synchronized (gate) {
            if (owner == null && debugOwner != null) return new SkbSdk.SdkException("DEBUG_BUSY", "人工调试中");
            if (owner != null && owner != debugOwner) return new SkbSdk.SdkException("DEBUG_CLOSED", "调试会话已结束");
            return null;
        }
    }

    private <T> void enqueue(Object owner, Result<T> result, Work<T> work) {
        synchronized (gate) {
            Exception error = denied(owner);
            if (error != null) { result.complete(null, error); return; }
            worker.execute(() -> {
                Exception pendingError = denied(owner);
                if (pendingError != null) { result.complete(null, pendingError); return; }
                T value = null;
                Exception failure = null;
                try { value = work.run(); }
                catch (Exception e) { failure = e; }
                result.complete(value, failure);
            });
        }
    }
    interface Work<T> { T run() throws Exception; }

    void init(Object owner, String devicePath, Result<Void> result) {
        if (devicePath == null || !devicePath.trim().startsWith("/dev/")) {
            result.complete(null, new IllegalArgumentException("串口路径必须以 /dev/ 开头"));
            return;
        }
        final String nextPath = devicePath.trim();
        enqueue(owner, result, () -> {
            if (client != null && nextPath.equals(path)) return null;
            cancelRecovery();
            closePort();
            path = nextPath;
            wanted = true;
            try { openPort(); }
            catch (IOException e) { recover(e); throw e; }
            return null;
        });
    }

    <T> void command(Object owner, Command<T> command, Result<T> result) {
        enqueue(owner, result, () -> {
            if (client == null) throw new SkbSdk.SdkException("NOT_CONNECTED", "串口尚未连接");
            try { return command.run(client); }
            catch (SerialTimeoutException e) { throw e; } // Outcome unknown: never replay a command.
            catch (IOException e) { recover(e); throw e; }
        });
    }

    void setDeviceAddress(int address, Result<Void> result) {
        command(null, skb -> { skb.setDeviceAddress(address); deviceAddress = address; return null; }, result);
    }

    void raw(Object owner, byte[] data, Result<Void> result) {
        final byte[] copy = data.clone();
        command(owner, skb -> { log("TX 原始: " + hex(copy)); port.write(copy); return null; }, result);
    }

    void shutdown(Object owner, Result<Void> result) {
        enqueue(owner, result, () -> {
            wanted = false;
            cancelRecovery();
            closePort();
            publish(SkbSdk.State.DISCONNECTED, "串口已关闭");
            return null;
        });
    }

    void enter(Object owner, Result<Void> result) {
        synchronized (gate) {
            if (debugOwner != null && debugOwner != owner) {
                result.complete(null, new SkbSdk.SdkException("DEBUG_BUSY", "已有人工调试界面"));
                return;
            }
            debugOwner = owner; // Block new and queued business calls before waiting for in-flight work.
            worker.execute(() -> {
                log("已进入人工调试");
                result.complete(null, denied(owner));
            });
        }
    }

    void leave(Object owner) {
        synchronized (gate) {
            if (debugOwner != owner) return;
            // Existing debug commands finish before business calls are admitted again.
            worker.execute(() -> {
                synchronized (gate) { if (debugOwner == owner) debugOwner = null; }
                log("人工调试已结束，恢复宿主业务调用");
            });
        }
    }

    private void openPort() throws IOException {
        publish(SkbSdk.State.CONNECTING, "正在连接 " + path);
        try { port = factory.open(path); }
        catch (LinkageError e) { throw new IOException("串口原生库加载失败，请检查 ABI", e); }
        client = new SkbClient(new SerialTransport() {
            private final java.io.ByteArrayOutputStream rxLog = new java.io.ByteArrayOutputStream();
            public void clearInputBuffer() throws IOException { rxLog.reset(); port.clearInputBuffer(); }
            public void write(byte[] data) throws IOException { log("TX 原始: " + hex(data)); port.write(data); }
            public byte[] read(int length, int timeout) throws IOException {
                try {
                    byte[] data = port.read(length, timeout);
                    rxLog.write(data, 0, data.length);
                    byte[] frame = rxLog.toByteArray();
                    if (frame.length >= 4 && frame.length >= 5 + (frame[3] & 255)) {
                        log("RX 原始: " + hex(frame));
                        rxLog.reset();
                    }
                    return data;
                } catch (IOException e) { rxLog.reset(); throw e; }
            }
            public void close() throws IOException { port.close(); }
        });
        client.setCommandIntervalMillis(100);
        client.setDeviceAddress(deviceAddress);
        publish(SkbSdk.State.CONNECTED, "已连接 " + path + " @ 9600");
    }

    private void recover(IOException error) {
        closePort();
        publish(SkbSdk.State.RECONNECTING, "连接异常: " + error.getMessage());
        if (wanted && recovery == null) recovery = worker.schedule(() -> {
            recovery = null;
            if (!wanted) return;
            try { openPort(); } catch (IOException e) { recover(e); }
        }, 2, TimeUnit.SECONDS);
    }

    private void cancelRecovery() {
        if (recovery != null) { recovery.cancel(false); recovery = null; }
    }
    private void closePort() {
        client = null;
        if (port != null) {
            try { port.close(); } catch (IOException e) { log("关闭串口失败: " + e.getMessage()); }
            port = null;
        }
    }
    private void poll() {
        if (!debugging() || port == null) return;
        try {
            byte[] data = port.readAvailable(4096, 20);
            if (data.length > 0) log("RX 主动上报: " + hex(data));
        } catch (SerialTimeoutException ignored) {
        } catch (IOException e) { recover(e); }
    }
    private void publish(SkbSdk.State next, String message) {
        state = next;
        log(message);
    }
    private void log(String message) {
        Observer current = observer;
        if (current != null) current.changed(state, path, message);
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.US, "%02X ", value & 255));
        return out.toString().trim();
    }
}
