package com.knoxboxgb;

import me.zed_0xff.zombie_buddy.Exposer;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicLong;

/** Runs the existing single CoffeeGB instance on its own paced thread. */
@Exposer.LuaClass(name = "KBMGameBoyRealtime")
public final class KBMGameBoyRealtime {
    private static final long FRAME_NANOS = 16_666_667L;
    private static volatile boolean running;
    private static volatile Thread worker;
    private static volatile int padMask;
    private static volatile long frameCount;
    private static volatile String lastError = "";
    private static final AtomicLong timedFrames = new AtomicLong();
    private static final AtomicLong busyNanos = new AtomicLong();
    private static final AtomicLong maxBusyNanos = new AtomicLong();
    private static final AtomicLong maxLateNanos = new AtomicLong();
    private static final AtomicLong lateFrames = new AtomicLong();

    private KBMGameBoyRealtime() {}

    public static synchronized boolean start() {
        if (running) return true;
        if (!KBMGameBoy.isRomLoaded()) {
            lastError = "No Game Boy ROM is loaded";
            return false;
        }
        padMask = 0;
        frameCount = 0;
        lastError = "";
        running = true;
        Thread thread = new Thread(KBMGameBoyRealtime::loop, "KBM-GameBoy-realtime");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 3);
        worker = thread;
        thread.start();
        return true;
    }

    public static synchronized void stop() {
        Thread thread;
        running = false;
        thread = worker;
        worker = null;
        if (thread != null) {
            LockSupport.unpark(thread);
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setPadMask(int mask) {
        padMask = mask;
    }

    public static long getFrameCount() {
        return frameCount;
    }

    public static String getLastError() {
        return lastError;
    }

    public static String getTimingStats() {
        long count = timedFrames.getAndSet(0);
        long busy = busyNanos.getAndSet(0);
        long maxBusy = maxBusyNanos.getAndSet(0);
        long maxLate = maxLateNanos.getAndSet(0);
        long late = lateFrames.getAndSet(0);
        return String.format(java.util.Locale.ROOT,
            "busyAvg=%.1f busyMax=%.1f lateMax=%.1f lateFrames=%d",
            count == 0 ? 0.0 : busy / (count * 1_000_000.0),
            maxBusy / 1_000_000.0, maxLate / 1_000_000.0, late);
    }

    private static void loop() {
        long next = System.nanoTime();
        try {
            while (running && Thread.currentThread() == worker) {
                long begun = System.nanoTime();
                long lateness = begun - next;
                if (lateness > 0) {
                    lateFrames.incrementAndGet();
                    maxLateNanos.accumulateAndGet(lateness, Math::max);
                }
                KBMGameBoy.setPadMask(1, padMask);
                KBMGameBoy.runFrame();
                long busy = System.nanoTime() - begun;
                timedFrames.incrementAndGet();
                busyNanos.addAndGet(busy);
                maxBusyNanos.accumulateAndGet(busy, Math::max);
                frameCount++;
                next += FRAME_NANOS;
                long remaining = next - System.nanoTime();
                if (remaining > 0) LockSupport.parkNanos(remaining);
                else if (remaining < -FRAME_NANOS) next = System.nanoTime();
            }
        } catch (Throwable error) {
            lastError = error.toString();
            System.err.println("[KBM][GBrealtime] worker stopped: " + error);
        } finally {
            if (Thread.currentThread() == worker) {
                running = false;
                KBMGameBoy.setPadMask(1, 0);
            }
        }
    }
}
