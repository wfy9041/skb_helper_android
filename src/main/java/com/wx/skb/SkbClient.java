package com.wx.skb;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** 基于串口传输的同步 SKB 协议客户端。 */
public class SkbClient implements Closeable {
    /** 瞬时开锁功能码。 */
    public static final int FUN_OPEN_LOCK = 0;
    /** 设置起始锁号功能码。 */
    public static final int FUN_SET_START_LOCK_ID = 1;
    /** 设置结束锁号功能码。 */
    public static final int FUN_SET_END_LOCK_ID = 2;
    /** 查询门状态功能码。 */
    public static final int FUN_GET_DOOR_STATUS = 3;
    /** 设置地址范围功能码。 */
    public static final int FUN_SET_ADDR = 4;
    /** 设置通道映射功能码。 */
    public static final int FUN_SET_CHANNEL_MAP = 5;
    /** 常开功能码。 */
    public static final int FUN_KEEP_OPEN = 6;
    /** 常关功能码。 */
    public static final int FUN_KEEP_CLOSE = 7;
    /** 查询常开常关状态功能码。 */
    public static final int FUN_GET_KEEP_STATUS = 8;
    /** 延时关锁功能码。 */
    public static final int FUN_DELAY_CLOSE = 9;
    /** 存在检测功能码。 */
    public static final int FUN_EXIST_CHECK = 10;

    private static final int DEFAULT_DEVICE_ADDRESS = 1;
    /** 带应答命令的默认响应读取超时，单位为毫秒。 */
    public static final int DEFAULT_TIMEOUT_MILLIS = 800;
    private static final int DEFAULT_COMMAND_INTERVAL_MILLIS = 50;

    private final SerialTransport transport;
    private final Object busLock = new Object();
    private int deviceAddress = DEFAULT_DEVICE_ADDRESS;
    private int sequence = 0;
    private int commandIntervalMillis = DEFAULT_COMMAND_INTERVAL_MILLIS;
    private long lastCommandFinishedAtMillis = 0;

    /** 使用系统串口名创建客户端，例如 {@code COM3} 或 {@code /dev/ttyUSB0}。 */
    public SkbClient(String portName) throws SkbException {
        this(new JSerialCommTransport(portName));
    }

    /** 使用自定义传输层创建客户端，便于 Android Native 串口或测试替换。 */
    public SkbClient(SerialTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.transport = transport;
    }

    /** 设置后续请求使用的设备地址，地址不能为 0。 */
    public void setDeviceAddress(int deviceAddress) {
        if (deviceAddress == 0) {
            throw new IllegalArgumentException("deviceAddress must be non-zero");
        }
        synchronized (busLock) {
            this.deviceAddress = ProtocolFrame.requireByte("deviceAddress", deviceAddress);
        }
    }

    /** 设置两次命令之间的最小间隔，单位为毫秒；传入 0 表示不额外等待。 */
    public void setCommandIntervalMillis(int commandIntervalMillis) {
        if (commandIntervalMillis < 0) {
            throw new IllegalArgumentException("commandIntervalMillis must not be negative");
        }
        synchronized (busLock) {
            this.commandIntervalMillis = commandIntervalMillis;
        }
    }

    /** 瞬时开锁。 */
    public Ack openLock(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_OPEN_LOCK, oneByte(lockId), timeoutMillis);
    }

    /** 设置常开。 */
    public Ack keepOpen(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_KEEP_OPEN, oneByte(lockId), timeoutMillis);
    }

    /** 设置常关。 */
    public Ack keepClose(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_KEEP_CLOSE, oneByte(lockId), timeoutMillis);
    }

    /** 设置延时关锁，延时单位为 50 毫秒。 */
    public Ack delayClose(int lockId, long delayTicks50ms, int timeoutMillis) throws IOException, SkbException {
        if (delayTicks50ms < 0 || delayTicks50ms > 0xffffffffL) {
            throw new IllegalArgumentException("delayTicks50ms must be 0..0xffffffff");
        }

        byte[] data = new byte[5];
        data[0] = (byte) ProtocolFrame.requireByte("lockId", lockId);
        data[1] = (byte) ((delayTicks50ms >> 24) & 0xff);
        data[2] = (byte) ((delayTicks50ms >> 16) & 0xff);
        data[3] = (byte) ((delayTicks50ms >> 8) & 0xff);
        data[4] = (byte) (delayTicks50ms & 0xff);
        return commandWithAck(FUN_DELAY_CLOSE, data, timeoutMillis);
    }

    /** 查询锁板门状态。 */
    public StatusResult getDoorStatus(int lockId, int timeoutMillis) throws IOException, SkbException {
        Ack ack = commandWithAck(FUN_GET_DOOR_STATUS, oneByte(lockId), timeoutMillis);
        return StatusResult.fromAck(ack);
    }

    /** 使用指定请求地址查询锁板门状态。 */
    public StatusResult getDoorStatus(int requestDeviceAddress, int lockId, int timeoutMillis)
            throws IOException, SkbException {
        Ack ack = commandWithAck(requestDeviceAddress, FUN_GET_DOOR_STATUS, oneByte(lockId), timeoutMillis);
        return StatusResult.fromAck(ack);
    }

    /** 按锁板代表锁号逐板查询门状态。 */
    public List<StatusResult> getAllDoorStatus(Collection<Integer> boardProbeLockIds, int timeoutMillis)
            throws IOException, SkbException {
        if (boardProbeLockIds == null) {
            throw new IllegalArgumentException("boardProbeLockIds must not be null");
        }
        requireTimeoutMillis(timeoutMillis);

        List<StatusResult> results = new ArrayList<StatusResult>();
        synchronized (busLock) {
            for (Integer probeLockId : boardProbeLockIds) {
                if (probeLockId == null) {
                    throw new IllegalArgumentException("boardProbeLockIds must not contain null");
                }
                int lockId = ProtocolFrame.requireByte("probeLockId", probeLockId);
                Ack ack = commandWithAckLocked(lockId, FUN_GET_DOOR_STATUS, oneByte(lockId), timeoutMillis);
                results.add(StatusResult.fromAck(ack));
            }
        }
        return results;
    }

    /** 从 1 号锁开始动态查询所有锁板，下一板从上一结果的结束锁号加一开始。 */
    public List<StatusResult> getAllDoorStatus(int timeoutMillis) throws IOException, SkbException {
        return getAllDoorStatus(1, timeoutMillis);
    }

    /** 从指定锁号开始动态查询所有锁板；下一板无响应超时后结束。 */
    public List<StatusResult> getAllDoorStatus(int firstLockId, int timeoutMillis) throws IOException, SkbException {
        ProtocolFrame.requireByte("firstLockId", firstLockId);
        if (firstLockId <= 0) {
            throw new IllegalArgumentException("firstLockId must be non-zero");
        }
        requireTimeoutMillis(timeoutMillis);

        List<StatusResult> results = new ArrayList<StatusResult>();
        synchronized (busLock) {
            int probeLockId = firstLockId;
            while (probeLockId <= 255) {
                try {
                    Ack ack = commandWithAckLocked(probeLockId, FUN_GET_DOOR_STATUS,
                            oneByte(probeLockId), timeoutMillis);
                    StatusResult statusResult = StatusResult.fromAck(ack);
                    results.add(statusResult);
                    int nextProbeLockId = statusResult.getEndLockId() + 1;
                    if (nextProbeLockId <= probeLockId) {
                        throw new SkbException("invalid door status range returned by board");
                    }
                    probeLockId = nextProbeLockId;
                } catch (SerialTimeoutException e) {
                    break;
                }
            }
        }
        return results;
    }

    /** 兼容旧版 API；{@code lastLockId} 和 {@code locksPerBoard} 当前不会参与计算。 */
    @Deprecated
    public List<StatusResult> getAllDoorStatus(int firstLockId, int lastLockId, int locksPerBoard, int timeoutMillis)
            throws IOException, SkbException {
        ProtocolFrame.requireByte("firstLockId", firstLockId);
        if (firstLockId <= 0) {
            throw new IllegalArgumentException("firstLockId must be non-zero");
        }
        return getAllDoorStatus(firstLockId, timeoutMillis);
    }

    /** 查询指定锁号是否存在。 */
    public StatusResult existCheck(int lockId, int timeoutMillis) throws IOException, SkbException {
        Ack ack = commandWithAck(FUN_EXIST_CHECK, oneByte(lockId), timeoutMillis);
        return StatusResult.fromAck(ack);
    }

    /** 设置起始锁号；当前协议命令不返回 ACK。 */
    public void setStartLockId(int lockId) throws IOException {
        commandNoAck(FUN_SET_START_LOCK_ID, oneByte(lockId));
    }

    /** 设置结束锁号。 */
    public Ack setEndLockId(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_SET_END_LOCK_ID, oneByte(lockId), timeoutMillis);
    }

    /** 一次性设置起始和结束锁号。 */
    public Ack setAddressRange(int startLockId, int endLockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_SET_ADDR, new byte[] {
                (byte) ProtocolFrame.requireByte("startLockId", startLockId),
                (byte) ProtocolFrame.requireByte("endLockId", endLockId)
        }, timeoutMillis);
    }

    /** 设置逻辑锁号到物理通道的映射。 */
    public Ack setChannelMap(int logicalLockId, int physicalChannel, int timeoutMillis)
            throws IOException, SkbException {
        return commandWithAck(FUN_SET_CHANNEL_MAP, new byte[] {
                (byte) ProtocolFrame.requireByte("logicalLockId", logicalLockId),
                (byte) ProtocolFrame.requireByte("physicalChannel", physicalChannel)
        }, timeoutMillis);
    }

    /** 发送需要 ACK 的自定义功能码命令。 */
    public Ack sendRaw(int function, byte[] data, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(function, data, timeoutMillis);
    }

    /** 发送不等待 ACK 的自定义功能码命令。 */
    public void sendRawNoAck(int function, byte[] data) throws IOException {
        commandNoAck(function, data);
    }

    private Ack commandWithAck(int function, byte[] data, int timeoutMillis) throws IOException, SkbException {
        requireTimeoutMillis(timeoutMillis);
        synchronized (busLock) {
            return commandWithAckLocked(deviceAddress, function, data, timeoutMillis);
        }
    }

    private Ack commandWithAck(int requestDeviceAddress, int function, byte[] data, int timeoutMillis)
            throws IOException, SkbException {
        ProtocolFrame.requireByte("requestDeviceAddress", requestDeviceAddress);
        if (requestDeviceAddress == 0) {
            throw new IllegalArgumentException("requestDeviceAddress must be non-zero");
        }
        requireTimeoutMillis(timeoutMillis);
        synchronized (busLock) {
            return commandWithAckLocked(requestDeviceAddress, function, data, timeoutMillis);
        }
    }

    private Ack commandWithAckLocked(int requestDeviceAddress, int function, byte[] data, int timeoutMillis)
            throws IOException, SkbException {
        try {
            int requestSequence = nextSequence();
            byte[] requestFrame = writeFrame(requestDeviceAddress, function, requestSequence, data);
            ProtocolFrame ack = readFrame(timeoutMillis);
            if (ack.function != function) {
                throw new SkbException("unexpected ack function: " + ack.function + ", expected " + function);
            }
            if (ack.sequence != requestSequence) {
                throw new SkbException("unexpected ack sequence: " + ack.sequence + ", expected " + requestSequence);
            }
            return new Ack(ack.function, ack.sequence, ack.data, requestFrame, ack.encode());
        } finally {
            lastCommandFinishedAtMillis = System.currentTimeMillis();
        }
    }

    private void commandNoAck(int function, byte[] data) throws IOException {
        synchronized (busLock) {
            try {
                writeFrame(deviceAddress, function, nextSequence(), data);
            } finally {
                lastCommandFinishedAtMillis = System.currentTimeMillis();
            }
        }
    }

    private byte[] writeFrame(int requestDeviceAddress, int function, int requestSequence, byte[] data) throws IOException {
        waitCommandInterval();
        transport.clearInputBuffer();
        ProtocolFrame frame = new ProtocolFrame(requestDeviceAddress, function, requestSequence, data);
        byte[] encoded = frame.encode();
        transport.write(encoded);
        return encoded;
    }

    private ProtocolFrame readFrame(int timeoutMillis) throws IOException, SkbException {
        byte[] header = transport.read(4, timeoutMillis);
        int dataLength = ProtocolFrame.unsigned(header[3]);
        byte[] tail = transport.read(dataLength + 1, timeoutMillis);
        byte[] frame = new byte[header.length + tail.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(tail, 0, frame, header.length, tail.length);
        return ProtocolFrame.decode(frame);
    }

    private int nextSequence() {
        sequence = (sequence + 1) & 0xff;
        if (sequence == 0) {
            sequence = 1;
        }
        return sequence;
    }

    private void waitCommandInterval() throws IOException {
        if (commandIntervalMillis <= 0 || lastCommandFinishedAtMillis <= 0) {
            return;
        }

        long waitMillis = lastCommandFinishedAtMillis + commandIntervalMillis - System.currentTimeMillis();
        if (waitMillis <= 0) {
            return;
        }

        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for RS485 command interval", e);
        }
    }

    private static byte[] oneByte(int value) {
        return new byte[] {(byte) ProtocolFrame.requireByte("value", value)};
    }

    private static int requireTimeoutMillis(int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        return timeoutMillis;
    }

    @Override
    /** 关闭底层串口传输。 */
    public void close() throws IOException {
        synchronized (busLock) {
            transport.close();
        }
    }

    /** 已校验的协议 ACK 结果。返回的字节数组是副本，可安全修改。 */
    public static final class Ack {
        private final int function;
        private final int sequence;
        private final byte[] data;
        private final byte[] requestFrame;
        private final byte[] responseFrame;

        private Ack(int function, int sequence, byte[] data, byte[] requestFrame, byte[] responseFrame) {
            this.function = function;
            this.sequence = sequence;
            this.data = Arrays.copyOf(data, data.length);
            this.requestFrame = Arrays.copyOf(requestFrame, requestFrame.length);
            this.responseFrame = Arrays.copyOf(responseFrame, responseFrame.length);
        }

        /** 返回 ACK 对应的功能码。 */
        public int getFunction() {
            return function;
        }

        /** 返回 ACK 对应的请求序号。 */
        public int getSequence() {
            return sequence;
        }

        /** 返回 ACK 数据区副本。 */
        public byte[] getData() {
            return Arrays.copyOf(data, data.length);
        }

        /** 返回本次请求发送的完整原始帧。 */
        public byte[] getRequestFrame() {
            return Arrays.copyOf(requestFrame, requestFrame.length);
        }

        /** 返回本次响应收到的完整原始帧。 */
        public byte[] getResponseFrame() {
            return Arrays.copyOf(responseFrame, responseFrame.length);
        }

        /** 返回数据区第 2 个字节作为无符号结果码；数据不足时返回 -1。 */
        public int getResult() {
            return data.length >= 2 ? ProtocolFrame.unsigned(data[1]) : -1;
        }
    }

    /** 门状态查询结果，包含锁号范围和每个锁对应的状态字节。 */
    public static final class StatusResult {
        private final int startLockId;
        private final int endLockId;
        private final byte[] status;
        private final byte[] requestFrame;
        private final byte[] responseFrame;

        private StatusResult(int startLockId, int endLockId, byte[] status,
                             byte[] requestFrame, byte[] responseFrame) {
            this.startLockId = startLockId;
            this.endLockId = endLockId;
            this.status = Arrays.copyOf(status, status.length);
            this.requestFrame = Arrays.copyOf(requestFrame, requestFrame.length);
            this.responseFrame = Arrays.copyOf(responseFrame, responseFrame.length);
        }

        /** 将门状态 ACK 解析为结构化结果。 */
        public static StatusResult fromAck(Ack ack) throws SkbException {
            byte[] data = ack.getData();
            if (data.length < 2) {
                return new StatusResult(0, 0, new byte[0], ack.getRequestFrame(), ack.getResponseFrame());
            }

            int startLockId = ProtocolFrame.unsigned(data[0]);
            int endLockId = ProtocolFrame.unsigned(data[1]);
            int count = endLockId >= startLockId ? endLockId - startLockId + 1 : 0;
            if (data.length != count + 2) {
                throw new SkbException("status ack length does not match address range");
            }
            return new StatusResult(startLockId, endLockId, Arrays.copyOfRange(data, 2, data.length),
                    ack.getRequestFrame(), ack.getResponseFrame());
        }

        /** 返回状态数组对应的起始锁号。 */
        public int getStartLockId() {
            return startLockId;
        }

        /** 返回状态数组对应的结束锁号。 */
        public int getEndLockId() {
            return endLockId;
        }

        /** 返回状态字节数组副本，索引 0 对应 {@link #getStartLockId()}。 */
        public byte[] getStatus() {
            return Arrays.copyOf(status, status.length);
        }

        /** 返回本次状态查询发送的完整原始帧。 */
        public byte[] getRequestFrame() {
            return Arrays.copyOf(requestFrame, requestFrame.length);
        }

        /** 返回本次状态查询收到的完整原始帧。 */
        public byte[] getResponseFrame() {
            return Arrays.copyOf(responseFrame, responseFrame.length);
        }
    }
}
