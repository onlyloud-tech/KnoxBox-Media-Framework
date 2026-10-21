package com.knoxboxgb;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import me.zed_0xff.zombie_buddy.Exposer;

/** JCodec-backed movie player exposed to Lua by ZombieBuddy. */
@Exposer.LuaClass(name="KBMMoviePlayer")
public final class KBMMoviePlayer {
    private static final String TEXTURE_KEY = "kbmgb_movie_player";
    private static final double DEFAULT_FPS = 24.0;

    private static SeekableByteChannel channel;
    private static FrameGrab grab;
    private static int[] pixels;
    private static Object texture;
    private static int width;
    private static int height;
    private static int currentFrame;
    private static int totalFrames;
    private static double fps = DEFAULT_FPS;
    private static boolean playing;
    private static boolean finished;
    private static long lastNanos;
    private static double accumulatedSeconds;
    private static long decodedRevision;
    private static long uploadedRevision = -1;
    private static String status = "idle";
    private static String error = "";

    private KBMMoviePlayer() {}

    /** Loads a file below common/media/video. Directory traversal is rejected. */
    public static synchronized boolean load(String fileName) {
        try {
            return loadFile(resolveCoreVideo(fileName));
        } catch (Throwable t) {
            return failLoad(t);
        }
    }

    /** Loads a video belonging to an active PZ mod. */
    public static synchronized boolean loadFromMod(String modId, String fileName) {
        try {
            return loadFile(resolveModVideo(modId, fileName));
        } catch (Throwable t) {
            return failLoad(t);
        }
    }

    /** Loads a camera recording from the current save, by sanitized ID only. */
    public static synchronized boolean loadRecording(String recordingId) {
        try {
            if (recordingId == null || !recordingId.matches("rec_[0-9]{13}_[0-9a-f]{8}")) {
                throw new IllegalArgumentException("invalid recording id");
            }
            Class<?> fsClass = Class.forName("zombie.ZomboidFileSystem");
            Object fs = fsClass.getField("instance").get(null);
            String path = (String) fsClass.getMethod("getFileNameInCurrentSave", String.class)
                .invoke(fs, "KnoxBoxRecordings/" + recordingId + ".mp4");
            if (path == null) throw new IllegalStateException("no active save directory");
            return loadFile(new File(path).getCanonicalFile());
        } catch (Throwable t) {
            return failLoad(t);
        }
    }

    private static boolean failLoad(Throwable t) {
        close();
        error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        status = "load_failed";
        return false;
    }

    private static File resolveCoreVideo(String fileName) {
        try {
            URI location = KBMMoviePlayer.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File jar = new File(location).getCanonicalFile();
            File javaDir = jar.isDirectory() ? jar : jar.getParentFile();
            File mediaDir = javaDir.getParentFile().getCanonicalFile();
            return resolveBelow(new File(mediaDir, "video"), fileName);
        } catch (Throwable t) {
            throw new IllegalStateException("could not resolve core movie", t);
        }
    }

    private static File resolveModVideo(String modId, String fileName) {
        try {
            if (modId == null || !modId.matches("[A-Za-z0-9_.-]{1,128}")) {
                throw new IllegalArgumentException("invalid movie mod id");
            }
            Class<?> fsClass = Class.forName("zombie.ZomboidFileSystem");
            Object fs = fsClass.getField("instance").get(null);
            String modDir = (String) fsClass.getMethod("getModDir", String.class).invoke(fs, modId);
            if (modDir == null) throw new IllegalStateException("movie mod is not active: " + modId);
            Object info = fsClass.getMethod("getModInfoForDir", String.class).invoke(fs, modDir);
            String versionDir = modInfoPath(info, "getVersionDir");
            String commonDir = modInfoPath(info, "getCommonDir");
            File[] roots = {
                versionDir == null ? null : new File(versionDir, "media/video"),
                commonDir == null ? null : new File(commonDir, "media/video"),
                new File(modDir, "media/video")
            };
            for (File root : roots) {
                if (root == null) continue;
                File candidate = resolveBelow(root, fileName);
                if (candidate.isFile()) return candidate;
            }
            throw new IllegalStateException("movie missing in active mod " + modId + ": " + fileName);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("could not resolve movie mod " + modId, t);
        }
    }

    private static String modInfoPath(Object info, String method) throws Exception {
        if (info == null) return null;
        Object value = info.getClass().getMethod(method).invoke(info);
        return value == null ? null : String.valueOf(value);
    }

    private static File resolveBelow(File directory, String fileName) throws Exception {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("empty movie filename");
        }
        File root = directory.getCanonicalFile();
        File video = new File(root, fileName).getCanonicalFile();
        String prefix = root.getPath() + File.separator;
        if (!video.getPath().startsWith(prefix)) {
            throw new SecurityException("movie path escapes media/video");
        }
        return video;
    }

    private static synchronized boolean loadFile(File video) {
        close();
        try {
            if (!video.isFile()) throw new IllegalStateException("movie missing: " + video);

            channel = NIOUtils.readableChannel(video);
            grab = FrameGrab.createFrameGrab(channel);
            totalFrames = grab.getVideoTrack().getMeta().getTotalFrames();
            double duration = grab.getVideoTrack().getMeta().getTotalDuration();
            fps = duration > 0 && totalFrames > 0 ? totalFrames / duration : DEFAULT_FPS;
            if (!decodeNext()) throw new IllegalStateException("movie contains no decodable frames");
            status = "ready";
            error = "";
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            status = "load_failed";
            closeChannelOnly();
            return false;
        }
    }

    private static boolean decodeNext() throws Exception {
        if (grab == null) return false;
        Picture picture = grab.getNativeFrame();
        if (picture == null) {
            finished = true;
            playing = false;
            status = "finished";
            return false;
        }
        BufferedImage image = AWTUtil.toBufferedImage(picture);
        width = image.getWidth();
        height = image.getHeight();
        pixels = image.getRGB(0, 0, width, height, pixels, 0, width);
        currentFrame++;
        decodedRevision++;
        return true;
    }

    public static synchronized void play() {
        if (grab == null || finished) return;
        playing = true;
        lastNanos = System.nanoTime();
        status = "playing";
    }

    public static synchronized void pause() {
        playing = false;
        lastNanos = 0L;
        status = grab == null ? "idle" : "paused";
    }

    public static synchronized void toggle() { if (playing) pause(); else play(); }

    public static synchronized void update() {
        if (!playing || grab == null || finished) return;
        long now = System.nanoTime();
        if (lastNanos == 0L) { lastNanos = now; return; }
        double elapsed = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        accumulatedSeconds += Math.min(elapsed, 0.5);
        double frameSeconds = 1.0 / Math.max(1.0, fps);
        int decodedThisCall = 0;
        try {
            while (accumulatedSeconds >= frameSeconds && decodedThisCall < 3) {
                accumulatedSeconds -= frameSeconds;
                if (!decodeNext()) break;
                decodedThisCall++;
            }
        } catch (Throwable t) {
            playing = false;
            error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            status = "decode_failed";
        }
    }

    public static synchronized boolean seekFrame(int oneBasedFrame) {
        if (grab == null) return false;
        int target = Math.max(1, Math.min(totalFrames, oneBasedFrame));
        try {
            grab.seekToFramePrecise(target - 1);
            currentFrame = target - 1;
            finished = false;
            accumulatedSeconds = 0;
            lastNanos = System.nanoTime();
            return decodeNext();
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            status = "seek_failed";
            return false;
        }
    }

    public static synchronized Object getRenderTexture() {
        if (pixels == null || width <= 0 || height <= 0) return null;
        if (texture == null || uploadedRevision != decodedRevision) {
            texture = KBMFrameTexture.update(TEXTURE_KEY, pixels, width, height);
            uploadedRevision = decodedRevision;
        }
        return texture;
    }

    public static synchronized void close() {
        playing = false;
        finished = false;
        closeChannelOnly();
        pixels = null;
        texture = null;
        width = 0;
        height = 0;
        currentFrame = 0;
        totalFrames = 0;
        fps = DEFAULT_FPS;
        lastNanos = 0L;
        accumulatedSeconds = 0;
        decodedRevision = 0;
        uploadedRevision = -1;
        status = "idle";
        error = "";
        try { KBMFrameTexture.resetKey(TEXTURE_KEY); } catch (Throwable ignored) {}
    }

    private static void closeChannelOnly() {
        grab = null;
        if (channel != null) try { channel.close(); } catch (Throwable ignored) {}
        channel = null;
    }

    public static synchronized boolean isPlaying() { return playing; }
    public static synchronized boolean isFinished() { return finished; }
    public static synchronized int getWidth() { return width; }
    public static synchronized int getHeight() { return height; }
    public static synchronized int getCurrentFrame() { return currentFrame; }
    public static synchronized int getTotalFrames() { return totalFrames; }
    public static synchronized double getFps() { return fps; }
    public static synchronized String getStatus() { return status; }
    public static synchronized String getError() { return error; }
    public static String getBridgeVersion() { return "KBMGBMovie-2026-10-03-pack1"; }
}
