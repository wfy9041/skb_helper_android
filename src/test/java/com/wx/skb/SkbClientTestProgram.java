package com.wx.skb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line smoke test for the SKB Java SDK.
 *
 * <p>Examples:
 * <pre>
 *   java com.wx.skb.SkbClientTestProgram --mock --action all-status
 *   java com.wx.skb.SkbClientTestProgram --port COM3 --action status --lock 1
 *   java com.wx.skb.SkbClientTestProgram --port COM3 --action open --lock 1
 * </pre>
 */
public final class SkbClientTestProgram {
    private SkbClientTestProgram() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            printUsage();
            return;
        }

        SerialTransport transport = options.mock
                ? new MockTransport()
                : null;

        SkbClient client = transport == null
                ? new SkbClient(options.required("port"))
                : new SkbClient(transport);

        try {
            client.setCommandIntervalMillis(options.intValue("interval", 50));
            if (options.has("device-address")) {
                client.setDeviceAddress(options.intValue("device-address", 1));
            }

            runAction(client, options);
        } finally {
            client.close();
        }
    }

    private static void runAction(SkbClient client, Options options) throws Exception {
        String action = options.value("action", "status");
        int lockId = options.intValue("lock", 1);
        int timeoutMillis = options.intValue("timeout", SkbClient.DEFAULT_TIMEOUT_MILLIS);

        if ("open".equals(action)) {
            printAck("open", client.openLock(lockId, timeoutMillis));
        } else if ("keep-open".equals(action)) {
            printAck("keep-open", client.keepOpen(lockId, timeoutMillis));
        } else if ("keep-close".equals(action)) {
            printAck("keep-close", client.keepClose(lockId, timeoutMillis));
        } else if ("delay-close".equals(action)) {
            long ticks = options.longValue("ticks", 20);
            printAck("delay-close", client.delayClose(lockId, ticks, timeoutMillis));
        } else if ("status".equals(action)) {
            printStatus("status", client.getDoorStatus(lockId, timeoutMillis));
        } else if ("all-status".equals(action)) {
            int firstLock = options.intValue("first-lock", 1);
            List<SkbClient.StatusResult> results = client.getAllDoorStatus(firstLock, timeoutMillis);
            for (int i = 0; i < results.size(); i++) {
                printStatus("board[" + i + "]", results.get(i));
            }
            if (results.isEmpty()) {
                System.out.println("all-status: no board responded");
            }
        } else if ("exist-check".equals(action)) {
            printStatus("exist-check", client.existCheck(lockId, timeoutMillis));
        } else if ("set-start".equals(action)) {
            client.setStartLockId(lockId);
            System.out.println("set-start: sent, this command has no ACK in current firmware");
        } else if ("set-end".equals(action)) {
            printAck("set-end", client.setEndLockId(lockId, timeoutMillis));
        } else if ("set-range".equals(action)) {
            int start = options.intValue("start", 1);
            int end = options.intValue("end", 12);
            printAck("set-range", client.setAddressRange(start, end, timeoutMillis));
        } else if ("map".equals(action)) {
            int physical = options.intValue("physical", lockId);
            printAck("map", client.setChannelMap(lockId, physical, timeoutMillis));
        } else {
            throw new IllegalArgumentException("unknown action: " + action);
        }
    }

    private static void printAck(String name, SkbClient.Ack ack) {
        System.out.println(name
                + ": function=" + ack.getFunction()
                + ", sequence=" + ack.getSequence()
                + ", result=" + ack.getResult()
                + ", data=" + toHex(ack.getData()));
    }

    private static void printStatus(String name, SkbClient.StatusResult status) {
        System.out.println(name
                + ": start=" + status.getStartLockId()
                + ", end=" + status.getEndLockId()
                + ", status=" + toHex(status.getStatus()));
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", data[i] & 0xff));
        }
        return builder.toString();
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java com.wx.skb.SkbClientTestProgram --mock --action status --lock 1");
        System.out.println("  java com.wx.skb.SkbClientTestProgram --port COM3 --action open --lock 1");
        System.out.println("Options:");
        System.out.println("  --port <name>            Serial port, for example COM3 or /dev/ttyUSB0");
        System.out.println("  --mock                   Use an in-memory fake transport instead of serial hardware");
        System.out.println("  --action <name>          status, all-status, open, keep-open, keep-close,");
        System.out.println("                           delay-close, exist-check, set-start, set-end, set-range, map");
        System.out.println("  --lock <id>              Lock id, default 1");
        System.out.println("  --first-lock <id>        First lock for all-status, default 1");
        System.out.println("  --ticks <n>              Delay-close ticks, one tick is 50ms, default 20");
        System.out.println("  --start <id> --end <id>  Range for set-range, defaults 1..12");
        System.out.println("  --physical <channel>     Physical channel for map, default is --lock");
        System.out.println("  --device-address <addr>  Request device address, default 1");
        System.out.println("  --timeout <ms>           Read timeout, default 150");
        System.out.println("  --interval <ms>          Command interval, default 50");
    }

    private static final class Options {
        private final Map<String, String> values;
        private final boolean mock;
        private final boolean help;

        private Options(Map<String, String> values, boolean mock, boolean help) {
            this.values = values;
            this.mock = mock;
            this.help = help;
        }

        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<String, String>();
            boolean mock = false;
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--mock".equals(arg)) {
                    mock = true;
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if (arg.startsWith("--")) {
                    String name = arg.substring(2);
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        throw new IllegalArgumentException("missing value for " + arg);
                    }
                    values.put(name, args[++i]);
                } else {
                    throw new IllegalArgumentException("unexpected argument: " + arg);
                }
            }

            return new Options(values, mock, help);
        }

        boolean has(String name) {
            return values.containsKey(name);
        }

        String required(String name) {
            String value = values.get(name);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("--" + name + " is required unless --mock is used");
            }
            return value;
        }

        String value(String name, String defaultValue) {
            String value = values.get(name);
            return value == null ? defaultValue : value;
        }

        int intValue(String name, int defaultValue) {
            return Integer.parseInt(value(name, String.valueOf(defaultValue)));
        }

        long longValue(String name, long defaultValue) {
            return Long.parseLong(value(name, String.valueOf(defaultValue)));
        }
    }

    private static final class MockTransport implements SerialTransport {
        private byte[] response = new byte[0];
        private int responseOffset = 0;
        private boolean closed = false;

        @Override
        public void write(byte[] data) throws IOException {
            ensureOpen();
            try {
                ProtocolFrame request = ProtocolFrame.decode(data);
                byte[] ackData = ackDataFor(request);
                response = ackData == null
                        ? new byte[0]
                        : new ProtocolFrame(
                                request.deviceAddress,
                                request.function,
                                request.sequence,
                                ackData).encode();
                responseOffset = 0;
            } catch (SkbException e) {
                throw new IOException("mock received invalid request", e);
            }
        }

        @Override
        public byte[] read(int length, int timeoutMillis) throws IOException {
            ensureOpen();
            if (responseOffset + length > response.length) {
                throw new SerialTimeoutException("mock response exhausted");
            }
            byte[] result = Arrays.copyOfRange(response, responseOffset, responseOffset + length);
            responseOffset += length;
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("mock transport is closed");
            }
        }

        private static byte[] ackDataFor(ProtocolFrame request) {
            if (request.function == SkbClient.FUN_GET_DOOR_STATUS
                    || request.function == SkbClient.FUN_EXIST_CHECK) {
                int lockId = request.data.length == 0 ? 1 : ProtocolFrame.unsigned(request.data[0]);
                int start = ((Math.max(lockId, 1) - 1) / 12) * 12 + 1;
                if (start > 24) {
                    return null;
                }
                int end = start + 11;
                List<Byte> data = new ArrayList<Byte>();
                data.add((byte) start);
                data.add((byte) end);
                for (int id = start; id <= end; id++) {
                    data.add((byte) (id % 2));
                }
                byte[] result = new byte[data.size()];
                for (int i = 0; i < data.size(); i++) {
                    result[i] = data.get(i);
                }
                return result;
            }

            int lockId = request.data.length == 0 ? 0 : ProtocolFrame.unsigned(request.data[0]);
            return new byte[] {(byte) lockId, 0};
        }
    }
}
