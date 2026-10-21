// Grymlock-authored Game Boy audio worker. See LICENSES/KnoxBox-License.md.
// SPDX-License-Identifier: LicenseRef-KnoxBox-Original-Content
package com.vtbasic.gb;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.sound.Sound.SoundSampleEvent;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Arrays;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Audio output for the bundled CoffeeGB engine. */
final class VTIGameBoyEngine$AudioWorker implements Runnable {
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int DIVIDER = (int) (4194304f / SAMPLE_RATE);
    private static final int LINE_BYTES = 8192;
    private static final int FADE_FRAMES = 64;
    private static final int SILENCE_BYTES = 882; // 10 ms, stereo 8-bit
    private static final AudioFormat FORMAT = new AudioFormat(
        AudioFormat.Encoding.PCM_UNSIGNED, SAMPLE_RATE, 8, CHANNELS,
        CHANNELS, SAMPLE_RATE, false);

    private final BlockingDeque<int[]> queue = new LinkedBlockingDeque<>(10);
    private final AtomicLong producedPcmBytes = new AtomicLong();
    private volatile boolean doStop;
    private volatile int droppedBlocks;

    VTIGameBoyEngine$AudioWorker(EventBus bus) {
        bus.register(this::accept, SoundSampleEvent.class);
    }

    void requestStop() {
        doStop = true;
    }

    private void accept(SoundSampleEvent event) {
        int[] block = event.buffer().clone();
        producedPcmBytes.addAndGet((block.length + DIVIDER - 1) / DIVIDER);
        if (!queue.offerLast(block)) {
            queue.pollFirst();
            droppedBlocks++;
            queue.offerLast(block);
        }
    }

    private static byte[] convert(int[] samples) {
        byte[] pcm = new byte[(samples.length + DIVIDER - 1) / DIVIDER];
        int written = 0;
        for (int i = 0; i < samples.length; i += DIVIDER) {
            pcm[written++] = (byte) samples[i];
        }
        return pcm;
    }

    private static void fadeIn(byte[] pcm) {
        int frames = Math.min(FADE_FRAMES, pcm.length / CHANNELS);
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                int i = frame * CHANNELS + channel;
                int value = pcm[i] & 0xff;
                pcm[i] = (byte) (128 + (value - 128) * frame / frames);
            }
        }
    }

    /** Linear resampling keeps sound continuous when Viewpoint slows emulation. */
    private static byte[] stretch(byte[] pcm, double rate) {
        int inputFrames = pcm.length / CHANNELS;
        if (Math.abs(rate - 1.0) < 0.005 || inputFrames < 2) return pcm;
        int outputFrames = Math.max(2, (int) Math.round(inputFrames / rate));
        byte[] output = new byte[outputFrames * CHANNELS];
        for (int frame = 0; frame < outputFrames; frame++) {
            double position = (double) frame * (inputFrames - 1) / (outputFrames - 1);
            int left = (int) position;
            int right = Math.min(left + 1, inputFrames - 1);
            double fraction = position - left;
            for (int channel = 0; channel < CHANNELS; channel++) {
                int a = pcm[left * CHANNELS + channel] & 0xff;
                int b = pcm[right * CHANNELS + channel] & 0xff;
                output[frame * CHANNELS + channel] = (byte) Math.round(a + (b - a) * fraction);
            }
        }
        return output;
    }

    private static byte[] softSilence(int left, int right) {
        byte[] pcm = new byte[SILENCE_BYTES];
        Arrays.fill(pcm, (byte) 128);
        int frames = Math.min(FADE_FRAMES, pcm.length / CHANNELS);
        for (int frame = 0; frame < frames; frame++) {
            pcm[frame * 2] = (byte) (left + (128 - left) * frame / frames);
            pcm[frame * 2 + 1] = (byte) (right + (128 - right) * frame / frames);
        }
        return pcm;
    }

    @Override
    public void run() {
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 2);
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, LINE_BYTES);
        } catch (Throwable error) {
            System.err.println("[KBM][GBaudio] audio line unavailable: " + error);
            return;
        }

        int lastLeft = 128;
        int lastRight = 128;
        boolean afterSilence = true;
        int underruns = 0;
        double playbackRate = 1.0;
        long nextReport = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            // The Lua loader immediately generates six frames. Prime the line
            // with four before starting to cover the first render hitch.
            int primed = 0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (!doStop && primed < 4 && System.nanoTime() < deadline) {
                int[] block = queue.pollFirst(10, TimeUnit.MILLISECONDS);
                if (block == null) continue;
                byte[] pcm = convert(block);
                if (afterSilence) fadeIn(pcm);
                line.write(pcm, 0, pcm.length);
                lastLeft = pcm[pcm.length - 2] & 0xff;
                lastRight = pcm[pcm.length - 1] & 0xff;
                afterSilence = false;
                primed++;
            }
            line.start();

            long lastMeasure = System.nanoTime();
            long lastProduced = producedPcmBytes.get();
            long nextMeasure = lastMeasure + TimeUnit.MILLISECONDS.toNanos(250);

            while (!doStop) {
                int[] block = queue.pollFirst(5, TimeUnit.MILLISECONDS);
                if (block != null) {
                    byte[] pcm = stretch(convert(block), playbackRate);
                    if (afterSilence) fadeIn(pcm);
                    line.write(pcm, 0, pcm.length);
                    lastLeft = pcm[pcm.length - 2] & 0xff;
                    lastRight = pcm[pcm.length - 1] & 0xff;
                    afterSilence = false;
                } else if (line.available() >= line.getBufferSize() - 2205) {
                    // Keep the device fed through a slow emulator frame. A
                    // short ramp removes the sharp sample jump on underrun.
                    byte[] pcm = softSilence(lastLeft, lastRight);
                    line.write(pcm, 0, pcm.length);
                    lastLeft = lastRight = 128;
                    afterSilence = true;
                    underruns++;
                }
                long now = System.nanoTime();
                if (now >= nextMeasure) {
                    long produced = producedPcmBytes.get();
                    double elapsed = (now - lastMeasure) / 1_000_000_000.0;
                    double measured = (produced - lastProduced)
                        / (SAMPLE_RATE * CHANNELS * elapsed);
                    double target = Math.max(0.45, Math.min(1.0, measured));
                    playbackRate = playbackRate * 0.5 + target * 0.5;
                    if (queue.size() <= 1 && measured < 0.95)
                        playbackRate = Math.min(playbackRate, Math.max(0.45, target - 0.02));
                    if (queue.size() >= 6 && measured >= 0.95)
                        playbackRate = Math.max(playbackRate, 1.06);
                    lastMeasure = now;
                    lastProduced = produced;
                    nextMeasure = now + TimeUnit.MILLISECONDS.toNanos(250);
                }
                if (now >= nextReport) {
                    if (underruns != 0 || droppedBlocks != 0 || playbackRate < 0.995) {
                        System.out.println("[KBM][GBaudio] underruns=" + underruns
                            + " dropped=" + droppedBlocks
                            + " rate=" + String.format(java.util.Locale.ROOT, "%.2f", playbackRate));
                    }
                    underruns = droppedBlocks = 0;
                    nextReport = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                }
            }
            byte[] end = softSilence(lastLeft, lastRight);
            line.write(end, 0, end.length);
            line.drain();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            line.stop();
            line.close();
        }
    }
}
