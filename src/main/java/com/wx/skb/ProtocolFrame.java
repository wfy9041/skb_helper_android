package com.wx.skb;

import java.util.Arrays;

final class ProtocolFrame {
    static final int MAX_FRAME_LENGTH = 261;
    static final int MIN_FRAME_LENGTH = 5;

    final int deviceAddress;
    final int function;
    final int sequence;
    final byte[] data;

    ProtocolFrame(int deviceAddress, int function, int sequence, byte[] data) {
        this.deviceAddress = requireByte("deviceAddress", deviceAddress);
        this.function = requireByte("function", function);
        this.sequence = requireByte("sequence", sequence);
        this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        if (this.data.length > 255) {
            throw new IllegalArgumentException("data length must be 0..255");
        }
    }

    byte[] encode() {
        byte[] frame = new byte[MIN_FRAME_LENGTH + data.length];
        frame[0] = (byte) deviceAddress;
        frame[1] = (byte) function;
        frame[2] = (byte) sequence;
        frame[3] = (byte) data.length;
        System.arraycopy(data, 0, frame, 4, data.length);
        frame[frame.length - 1] = (byte) checksum(frame, frame.length - 1);
        return frame;
    }

    static ProtocolFrame decode(byte[] frame) throws SkbException {
        if (frame == null || frame.length < MIN_FRAME_LENGTH || frame.length > MAX_FRAME_LENGTH) {
            throw new SkbException("invalid frame length");
        }

        int dataLength = unsigned(frame[3]);
        int expectedLength = MIN_FRAME_LENGTH + dataLength;
        if (frame.length != expectedLength) {
            throw new SkbException("frame length does not match data length");
        }

        int actualChecksum = unsigned(frame[frame.length - 1]);
        int expectedChecksum = checksum(frame, frame.length - 1);
        if (actualChecksum != expectedChecksum) {
            throw new SkbException("checksum mismatch");
        }

        byte[] data = Arrays.copyOfRange(frame, 4, 4 + dataLength);
        return new ProtocolFrame(unsigned(frame[0]), unsigned(frame[1]), unsigned(frame[2]), data);
    }

    static int checksum(byte[] data, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum = (sum + unsigned(data[i])) & 0xff;
        }
        return (256 - sum) & 0xff;
    }

    static int unsigned(byte value) {
        return value & 0xff;
    }

    static int requireByte(String name, int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be 0..255");
        }
        return value;
    }
}
