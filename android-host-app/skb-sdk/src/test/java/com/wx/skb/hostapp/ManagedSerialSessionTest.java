package com.wx.skb.hostapp;

import com.wx.skb.SerialTimeoutException;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain JVM regression tests against the production session, with an injected serial port. */
public final class ManagedSerialSessionTest {
    static final class Reply<T> implements ManagedSerialSession.Result<T> {
        final CompletableFuture<T> done = new CompletableFuture<>();
        volatile Exception error;
        public void complete(T value, Exception error) { this.error = error; done.complete(value); }
        T await() throws Exception { return done.get(5, TimeUnit.SECONDS); }
        void ok() throws Exception { await(); if (error != null) throw new AssertionError(error); }
        void error(String code) throws Exception {
            await();
            check(error instanceof SkbSdk.SdkException && code.equals(((SkbSdk.SdkException) error).getCode()), "expected " + code + ", got " + error);
        }
    }
    static final class FakePort implements ManagedSerialSession.Port {
        final AtomicInteger writes = new AtomicInteger();
        volatile boolean closed, timeout, broken, reading;
        volatile byte[] last;
        byte[] response;
        int offset;
        public void clearInputBuffer() {}
        public void write(byte[] bytes) throws IOException {
            check(!closed, "write after close");
            writes.incrementAndGet(); last = bytes.clone();
            if (broken) throw new IOException("disconnected");
            response = new byte[] {bytes[0], bytes[1], bytes[2], 2, bytes[4], 0, 0};
            int sum = 0;
            for (int i = 0; i < response.length - 1; i++) sum += response[i] & 255;
            response[response.length - 1] = (byte) (256 - sum);
            offset = 0;
        }
        public byte[] read(int length, int timeoutMillis) throws IOException {
            reading = true;
            try {
                if (timeout) throw new SerialTimeoutException("timeout");
                byte[] result = Arrays.copyOfRange(response, offset, offset + length);
                offset += length;
                return result;
            } finally { reading = false; }
        }
        public byte[] readAvailable(int length, int timeout) {
            check(!reading, "passive reader competed with response reader");
            return new byte[0];
        }
        public void close() { closed = true; }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void init(ManagedSerialSession session) throws Exception {
        Reply<Void> reply = new Reply<>(); session.init(null, "/dev/test", reply); reply.ok();
    }
    static void stop(ManagedSerialSession session) throws Exception {
        Reply<Void> reply = new Reply<>(); session.shutdown(null, reply); reply.ok();
    }
    public static void main(String[] args) throws Exception {
        timeoutDoesNotReplay();
        debugHandoff();
        reconnectDoesNotReplay();
        shutdownCancelsRecovery();
        System.out.println("PASS: timeout, debug handoff, shared lifetime, reconnect, shutdown");
    }
    static void timeoutDoesNotReplay() throws Exception {
        FakePort port = new FakePort();
        AtomicInteger opens = new AtomicInteger();
        ManagedSerialSession session = new ManagedSerialSession(path -> { opens.incrementAndGet(); return port; });
        init(session); init(session);
        check(opens.get() == 1, "idempotent init opened twice");
        port.timeout = true;
        Reply<Object> reply = new Reply<>();
        session.command(null, skb -> skb.openLock(1, 10), reply); reply.await();
        check(reply.error instanceof SerialTimeoutException, "timeout not returned");
        check(port.writes.get() == 1 && session.state() == SkbSdk.State.CONNECTED, "timeout replayed or reconnected");
        stop(session);
    }
    static void debugHandoff() throws Exception {
        FakePort port = new FakePort();
        ManagedSerialSession session = new ManagedSerialSession(path -> port); init(session);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Reply<Object> active = new Reply<>();
        session.command(null, skb -> { started.countDown(); check(release.await(3, TimeUnit.SECONDS), "release timed out"); return skb.openLock(1, 10); }, active);
        check(started.await(3, TimeUnit.SECONDS), "command did not start");
        Reply<Object> queued = new Reply<>(); session.command(null, skb -> skb.openLock(2, 10), queued);
        Object owner = new Object(); Reply<Void> entered = new Reply<>(); session.enter(owner, entered);
        check(!entered.done.isDone(), "debug entered before in-flight command finished");
        Reply<Object> busy = new Reply<>(); session.command(null, skb -> skb.openLock(3, 10), busy); busy.error("DEBUG_BUSY");
        release.countDown(); active.ok(); queued.error("DEBUG_BUSY"); entered.ok();
        Reply<Void> recreated = new Reply<>(); session.enter(owner, recreated); recreated.ok();
        Reply<Void> duplicate = new Reply<>(); session.enter(new Object(), duplicate); duplicate.error("DEBUG_BUSY");
        Reply<Object> manual = new Reply<>(); session.command(owner, skb -> skb.openLock(4, 10), manual); manual.ok();
        session.leave(owner);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (session.debugging() && System.nanoTime() < until) Thread.sleep(5);
        check(!session.debugging() && !port.closed, "debug exit closed shared port or retained ownership");
        Reply<Object> stale = new Reply<>(); session.command(owner, skb -> skb.openLock(5, 10), stale); stale.error("DEBUG_CLOSED");
        Reply<Object> business = new Reply<>(); session.command(null, skb -> skb.openLock(6, 10), business); business.ok();
        check(port.writes.get() == 3, "queued or rejected commands were sent");
        stop(session);
    }
    static void reconnectDoesNotReplay() throws Exception {
        FakePort first = new FakePort(), second = new FakePort();
        AtomicInteger opens = new AtomicInteger();
        ManagedSerialSession session = new ManagedSerialSession(path -> opens.incrementAndGet() == 1 ? first : second);
        init(session);
        Reply<Void> address = new Reply<>(); session.setDeviceAddress(7, address); address.ok();
        first.broken = true;
        Reply<Object> failed = new Reply<>(); session.command(null, skb -> skb.openLock(1, 10), failed); failed.await();
        check(failed.error instanceof IOException && first.closed, "I/O failure did not close port");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (session.state() != SkbSdk.State.CONNECTED && System.nanoTime() < until) Thread.sleep(10);
        check(opens.get() == 2 && second.writes.get() == 0, "reconnect replayed failed command");
        Reply<Object> fresh = new Reply<>(); session.command(null, skb -> skb.openLock(2, 10), fresh); fresh.ok();
        check(second.last[0] == 7, "reconnect lost device address");
        stop(session);
    }
    static void shutdownCancelsRecovery() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        ManagedSerialSession session = new ManagedSerialSession(path -> { opens.incrementAndGet(); throw new IOException("permission denied"); });
        Reply<Void> failed = new Reply<>(); session.init(null, "/dev/test", failed); failed.await();
        check(failed.error instanceof IOException, "init failure not returned");
        stop(session); Thread.sleep(2200);
        check(opens.get() == 1 && session.state() == SkbSdk.State.DISCONNECTED, "shutdown did not cancel reconnect");
    }
}
