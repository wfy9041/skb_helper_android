package com.wx.skb;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class JSerialCommTransport implements SerialTransport {
    private final Object serialPort;
    private final Method writeBytes;
    private final Method readBytes;
    private final Method flushIOBuffers;
    private final Method closePort;
    private final Class<?> writeCountType;
    private final Class<?> readCountType;

    JSerialCommTransport(String portName) throws SkbException {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("portName must not be empty");
        }

        try {
            Class<?> serialPortClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
            Method getCommPort = serialPortClass.getMethod("getCommPort", String.class);
            Method setComPortParameters = serialPortClass.getMethod(
                    "setComPortParameters", int.class, int.class, int.class, int.class);
            Method setComPortTimeouts = serialPortClass.getMethod(
                    "setComPortTimeouts", int.class, int.class, int.class);
            Method openPort = serialPortClass.getMethod("openPort");

            serialPort = getCommPort.invoke(null, portName);
            setComPortParameters.invoke(serialPort, 9600, 8,
                    staticInt(serialPortClass, "ONE_STOP_BIT", 1),
                    staticInt(serialPortClass, "NO_PARITY", 0));
            setComPortTimeouts.invoke(serialPort,
                    staticInt(serialPortClass, "TIMEOUT_READ_SEMI_BLOCKING", 1),
                    100,
                    0);
            boolean opened = (Boolean) openPort.invoke(serialPort);
            if (!opened) {
                throw new SkbException("failed to open serial port: " + portName);
            }

            writeBytes = byteCountMethod(serialPortClass, "writeBytes");
            readBytes = byteCountMethod(serialPortClass, "readBytes");
            flushIOBuffers = serialPortClass.getMethod("flushIOBuffers");
            writeCountType = writeBytes.getParameterTypes()[1];
            readCountType = readBytes.getParameterTypes()[1];
            closePort = serialPortClass.getMethod("closePort");
        } catch (ClassNotFoundException e) {
            throw new SkbException("jSerialComm is required at runtime: com.fazecast:jSerialComm", e);
        } catch (ReflectiveOperationException e) {
            throw new SkbException("failed to initialize jSerialComm transport", e);
        }
    }

    @Override
    public void clearInputBuffer() throws IOException {
        try {
            boolean flushed = (Boolean) flushIOBuffers.invoke(serialPort);
            if (!flushed) {
                throw new IOException("serial input buffer flush failed");
            }
        } catch (IllegalAccessException e) {
            throw new IOException("serial input buffer flush failed", e);
        } catch (InvocationTargetException e) {
            throw asIOException("serial input buffer flush failed", e);
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        try {
            int written = ((Number) writeBytes.invoke(serialPort, data, countArgument(writeCountType, data.length))).intValue();
            if (written != data.length) {
                throw new IOException("serial write incomplete: " + written + "/" + data.length);
            }
        } catch (IllegalAccessException e) {
            throw new IOException("serial write failed", e);
        } catch (InvocationTargetException e) {
            throw asIOException("serial write failed", e);
        }
    }

    @Override
    public byte[] read(int length, int timeoutMillis) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (offset < length && System.currentTimeMillis() < deadline) {
            byte[] chunk = new byte[length - offset];
            int read;
            try {
                read = ((Number) readBytes.invoke(serialPort, chunk, countArgument(readCountType, chunk.length))).intValue();
            } catch (IllegalAccessException e) {
                throw new IOException("serial read failed", e);
            } catch (InvocationTargetException e) {
                throw asIOException("serial read failed", e);
            }

            if (read > 0) {
                System.arraycopy(chunk, 0, result, offset, read);
                offset += read;
                deadline = System.currentTimeMillis() + timeoutMillis;
            }
        }

        if (offset != length) {
            throw new SerialTimeoutException("serial read timeout: " + offset + "/" + length);
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        try {
            closePort.invoke(serialPort);
        } catch (IllegalAccessException e) {
            throw new IOException("serial close failed", e);
        } catch (InvocationTargetException e) {
            throw asIOException("serial close failed", e);
        }
    }

    private static IOException asIOException(String message, InvocationTargetException e) {
        Throwable cause = e.getTargetException();
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(message, cause);
    }

    private static Method byteCountMethod(Class<?> serialPortClass, String methodName) throws NoSuchMethodException {
        try {
            return serialPortClass.getMethod(methodName, byte[].class, long.class);
        } catch (NoSuchMethodException ignored) {
            return serialPortClass.getMethod(methodName, byte[].class, int.class);
        }
    }

    private static Object countArgument(Class<?> type, int value) {
        if (type == long.class || type == Long.class) {
            return (long) value;
        }
        return value;
    }

    private static int staticInt(Class<?> type, String fieldName, int defaultValue) {
        try {
            return ((Number) type.getField(fieldName).get(null)).intValue();
        } catch (ReflectiveOperationException e) {
            return defaultValue;
        }
    }
}
