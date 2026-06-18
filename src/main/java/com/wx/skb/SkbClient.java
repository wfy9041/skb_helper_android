package com.wx.skb;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class SkbClient implements Closeable {
    public static final int FUN_OPEN_LOCK = 0;
    public static final int FUN_SET_START_LOCK_ID = 1;
    public static final int FUN_SET_END_LOCK_ID = 2;
    public static final int FUN_GET_DOOR_STATUS = 3;
    public static final int FUN_SET_ADDR = 4;
    public static final int FUN_SET_CHANNEL_MAP = 5;
    public static final int FUN_KEEP_OPEN = 6;
    public static final int FUN_KEEP_CLOSE = 7;
    public static final int FUN_GET_KEEP_STATUS = 8;
    public static final int FUN_DELAY_CLOSE = 9;
    public static final int FUN_EXIST_CHECK = 10;

    private static final int DEFAULT_DEVICE_ADDRESS = 1;
    public static final int DEFAULT_TIMEOUT_MILLIS = 800;
    private static final int DEFAULT_COMMAND_INTERVAL_MILLIS = 50;

    private final SerialTransport transport;
    private final Object busLock = new Object();
    private int deviceAddress = DEFAULT_DEVICE_ADDRESS;
    private int sequence = 0;
    private int commandIntervalMillis = DEFAULT_COMMAND_INTERVAL_MILLIS;
    private long lastCommandFinishedAtMillis = 0;

    public SkbClient(String portName) throws SkbException {
        this(new JSerialCommTransport(portName));
    }

    public SkbClient(SerialTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.transport = transport;
    }

    public void setDeviceAddress(int deviceAddress) {
        if (deviceAddress == 0) {
            throw new IllegalArgumentException("deviceAddress must be non-zero");
        }
        synchronized (busLock) {
            this.deviceAddress = ProtocolFrame.requireByte("deviceAddress", deviceAddress);
        }
    }

    public void setCommandIntervalMillis(int commandIntervalMillis) {
        if (commandIntervalMillis < 0) {
            throw new IllegalArgumentException("commandIntervalMillis must not be negative");
        }
        synchronized (busLock) {
            this.commandIntervalMillis = commandIntervalMillis;
        }
    }

    public Ack openLock(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_OPEN_LOCK, oneByte(lockId), timeoutMillis);
    }

    public Ack keepOpen(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_KEEP_OPEN, oneByte(lockId), timeoutMillis);
    }

    public Ack keepClose(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_KEEP_CLOSE, oneByte(lockId), timeoutMillis);
    }

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

    public StatusResult getDoorStatus(int lockId, int timeoutMillis) throws IOException, SkbException {
        Ack ack = commandWithAck(FUN_GET_DOOR_STATUS, oneByte(lockId), timeoutMillis);
        return StatusResult.fromAck(ack);
    }

    public StatusResult getDoorStatus(int requestDeviceAddress, int lockId, int timeoutMillis)
            throws IOException, SkbException {
        Ack ack = commandWithAck(requestDeviceAddress, FUN_GET_DOOR_STATUS, oneByte(lockId), timeoutMillis);
        return StatusResult.fromAck(ack);
    }

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

    public List<StatusResult> getAllDoorStatus(int timeoutMillis) throws IOException, SkbException {
        return getAllDoorStatus(1, timeoutMillis);
    }

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

    @Deprecated
    public List<StatusResult> getAllDoorStatus(int firstLockId, int lastLockId, int locksPerBoard, int timeoutMillis)
            throws IOException, SkbException {
        ProtocolFrame.requireByte("firstLockId", firstLockId);
        if (firstLockId <= 0) {
            throw new IllegalArgumentException("firstLockId must be non-zero");
        }
        return getAllDoorStatus(firstLockId, timeoutMillis);
    }

    public StatusResult existCheck(int lockId, int timeoutMillis) throws IOException, SkbException {
        Ack ack = commandWithAck(FUN_EXIST_CHECK, oneByte(lockId), timeoutMillis);
        return StatusResult.fromAck(ack);
    }

    public void setStartLockId(int lockId) throws IOException {
        commandNoAck(FUN_SET_START_LOCK_ID, oneByte(lockId));
    }

    public Ack setEndLockId(int lockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_SET_END_LOCK_ID, oneByte(lockId), timeoutMillis);
    }

    public Ack setAddressRange(int startLockId, int endLockId, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(FUN_SET_ADDR, new byte[] {
                (byte) ProtocolFrame.requireByte("startLockId", startLockId),
                (byte) ProtocolFrame.requireByte("endLockId", endLockId)
        }, timeoutMillis);
    }

    public Ack setChannelMap(int logicalLockId, int physicalChannel, int timeoutMillis)
            throws IOException, SkbException {
        return commandWithAck(FUN_SET_CHANNEL_MAP, new byte[] {
                (byte) ProtocolFrame.requireByte("logicalLockId", logicalLockId),
                (byte) ProtocolFrame.requireByte("physicalChannel", physicalChannel)
        }, timeoutMillis);
    }

    public Ack sendRaw(int function, byte[] data, int timeoutMillis) throws IOException, SkbException {
        return commandWithAck(function, data, timeoutMillis);
    }

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
            writeFrame(requestDeviceAddress, function, requestSequence, data);
            ProtocolFrame ack = readFrame(timeoutMillis);
            if (ack.function != function) {
                throw new SkbException("unexpected ack function: " + ack.function + ", expected " + function);
            }
            if (ack.sequence != requestSequence) {
                throw new SkbException("unexpected ack sequence: " + ack.sequence + ", expected " + requestSequence);
            }
            return new Ack(ack.function, ack.sequence, ack.data);
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

    private void writeFrame(int requestDeviceAddress, int function, int requestSequence, byte[] data) throws IOException {
        waitCommandInterval();
        ProtocolFrame frame = new ProtocolFrame(requestDeviceAddress, function, requestSequence, data);
        transport.write(frame.encode());
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
    public void close() throws IOException {
        synchronized (busLock) {
            transport.close();
        }
    }

    public static final class Ack {
        private final int function;
        private final int sequence;
        private final byte[] data;

        private Ack(int function, int sequence, byte[] data) {
            this.function = function;
            this.sequence = sequence;
            this.data = Arrays.copyOf(data, data.length);
        }

        public int getFunction() {
            return function;
        }

        public int getSequence() {
            return sequence;
        }

        public byte[] getData() {
            return Arrays.copyOf(data, data.length);
        }

        public int getResult() {
            return data.length >= 2 ? ProtocolFrame.unsigned(data[1]) : -1;
        }
    }

    public static final class StatusResult {
        private final int startLockId;
        private final int endLockId;
        private final byte[] status;

        private StatusResult(int startLockId, int endLockId, byte[] status) {
            this.startLockId = startLockId;
            this.endLockId = endLockId;
            this.status = Arrays.copyOf(status, status.length);
        }

        public static StatusResult fromAck(Ack ack) throws SkbException {
            byte[] data = ack.getData();
            if (data.length < 2) {
                return new StatusResult(0, 0, new byte[0]);
            }

            int startLockId = ProtocolFrame.unsigned(data[0]);
            int endLockId = ProtocolFrame.unsigned(data[1]);
            int count = endLockId >= startLockId ? endLockId - startLockId + 1 : 0;
            if (data.length != count + 2) {
                throw new SkbException("status ack length does not match address range");
            }
            return new StatusResult(startLockId, endLockId, Arrays.copyOfRange(data, 2, data.length));
        }

        public int getStartLockId() {
            return startLockId;
        }

        public int getEndLockId() {
            return endLockId;
        }

        public byte[] getStatus() {
            return Arrays.copyOf(status, status.length);
        }
    }
}
