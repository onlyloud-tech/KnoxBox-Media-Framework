package com.knoxboxgb;

import fmod.javafmod;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.zed_0xff.zombie_buddy.Exposer;
import zombie.ZomboidFileSystem;

/** FMOD playback for a registered movie sound clip with pause and seek. */
@Exposer.LuaClass(name = "KBMMovieAudio")
public final class KBMMovieAudio {
    private static final long FMOD_2D_STREAM = 0x8L | 0x80L;
    private static final long FMOD_TIMEUNIT_MS = 1L;
    private static long sound;
    private static long channel;
    private static long lengthMs;
    private static float volume = 1.0f;
    private static String soundId = "";
    private static String error = "";

    private KBMMovieAudio() {}

    public static synchronized boolean playSoundId(String id, long offsetMs) {
        try {
            return playFile(resolveRegisteredSound(id), id, offsetMs);
        } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            return false;
        }
    }

    public static synchronized boolean playRecording(String id, long offsetMs) {
        try {
            Path file = preferredRecordingAudioPath(id);
            if (!Files.isRegularFile(file)) throw new IllegalStateException("recording has no audio");
            return playFile(file.toString(), id, offsetMs);
        } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            return false;
        }
    }

    public static boolean hasRecordingAudio(String id) {
        try { return Files.isRegularFile(preferredRecordingAudioPath(id)); }
        catch (Throwable ignored) { return false; }
    }

    /** Explicit playback probe; this tone is not captured game or microphone audio. */
    public static synchronized boolean installRecordingTestTone(String id) {
        try {
            Path file = recordingAudioPath(id);
            Path video = file.resolveSibling(id + ".mp4");
            if (!Files.isRegularFile(video))
                throw new IllegalStateException("matching recording video is missing");
            if (Files.exists(file) || Files.isRegularFile(preferredRecordingAudioPath(id)))
                throw new IllegalStateException("recording already has audio");
            Files.createDirectories(file.getParent());
            try (InputStream source = KBMMovieAudio.class.getResourceAsStream("recording_test_tone.ogg")) {
                if (source == null) throw new IllegalStateException("test tone missing from Core jar");
                Files.copy(source, file);
            }
            error = "";
            return true;
        } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            return false;
        }
    }

    private static Path recordingAudioPath(String id) {
        if (id == null || !id.matches("rec_[0-9]{13}_[0-9a-f]{8}"))
            throw new IllegalArgumentException("invalid recording id");
        String file = ZomboidFileSystem.instance.getFileNameInCurrentSave(
            "KnoxBoxRecordings/" + id + ".ogg");
        if (file == null) throw new IllegalStateException("no active save directory");
        return Path.of(file).toAbsolutePath().normalize();
    }

    private static Path preferredRecordingAudioPath(String id) {
        Path wav = recordingAudioPath(id).resolveSibling(id + ".wav");
        return Files.isRegularFile(wav) ? wav : recordingAudioPath(id);
    }

    private static boolean playFile(String file, String id, long offsetMs) {
        stop();
        try {
            sound = javafmod.FMOD_System_CreateSound(file, FMOD_2D_STREAM);
            if (sound == 0L) throw new IllegalStateException("FMOD could not load " + id);
            lengthMs = javafmod.FMOD_Sound_GetLength(sound, FMOD_TIMEUNIT_MS);
            if (lengthMs <= 0L) throw new IllegalStateException("invalid audio length for " + id);
            channel = javafmod.FMOD_System_PlaySound(sound, true);
            if (channel == 0L) throw new IllegalStateException("FMOD could not play " + id);
            int volumeResult = javafmod.FMOD_Channel_SetVolume(channel, volume);
            int seekResult = javafmod.FMOD_Channel_SetPosition(channel,
                Math.max(0L, Math.min(lengthMs - 1L, offsetMs)));
            int playResult = javafmod.FMOD_Channel_SetPaused(channel, false);
            if (volumeResult != 0 || seekResult != 0 || playResult != 0)
                throw new IllegalStateException("FMOD channel error "
                    + volumeResult + "/" + seekResult + "/" + playResult);
            soundId = id;
            error = "";
            return true;
        } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            stopHandles();
            return false;
        }
    }

    private static String resolveRegisteredSound(String id) throws Exception {
        if (id == null || !id.matches("KBM_Movie_[A-Za-z0-9_-]{1,160}"))
            throw new IllegalArgumentException("invalid movie sound id");
        Class<?> gameSounds = Class.forName("zombie.GameSounds");
        Object gameSound = gameSounds.getMethod("getSound", String.class).invoke(null, id);
        if (gameSound == null) throw new IllegalStateException("unknown movie sound " + id);
        Field clipsField = gameSound.getClass().getField("clips");
        List<?> clips = (List<?>) clipsField.get(gameSound);
        if (clips == null || clips.isEmpty())
            throw new IllegalStateException("movie sound has no clips " + id);
        Object clip = clips.get(0);
        String relative = (String) clip.getClass().getField("file").get(clip);
        if (relative == null || relative.isEmpty())
            throw new IllegalStateException("movie sound has no file " + id);
        Class<?> fsClass = Class.forName("zombie.ZomboidFileSystem");
        Object fs = fsClass.getField("instance").get(null);
        String absolute = (String) fsClass.getMethod("getAbsolutePath", String.class)
            .invoke(fs, relative);
        if (absolute == null) throw new IllegalStateException("movie sound file missing " + relative);
        return absolute;
    }

    public static synchronized boolean pause() {
        if (channel == 0L) return false;
        return javafmod.FMOD_Channel_SetPaused(channel, true) == 0;
    }

    public static synchronized boolean resume() {
        if (channel == 0L) return false;
        return javafmod.FMOD_Channel_SetPaused(channel, false) == 0;
    }

    public static synchronized boolean seekMs(long offsetMs) {
        if (channel == 0L) return false;
        long target = Math.max(0L, Math.min(lengthMs - 1L, offsetMs));
        return javafmod.FMOD_Channel_SetPosition(channel, target) == 0;
    }

    public static synchronized long getPositionMs() {
        return channel == 0L ? 0L : javafmod.FMOD_Channel_GetPosition(channel, (int) FMOD_TIMEUNIT_MS);
    }

    public static synchronized void setVolume(float value) {
        volume = Math.max(0.0f, Math.min(1.0f, value));
        if (channel != 0L) javafmod.FMOD_Channel_SetVolume(channel, volume);
    }

    public static synchronized void stop() {
        stopHandles();
        error = "";
    }

    private static void stopHandles() {
        if (channel != 0L) {
            try { javafmod.FMOD_Channel_Stop(channel); } catch (Throwable ignored) {}
            channel = 0L;
        }
        if (sound != 0L) {
            try { javafmod.FMOD_Sound_Release(sound); } catch (Throwable ignored) {}
            sound = 0L;
        }
        lengthMs = 0L;
        soundId = "";
    }

    public static String getSoundId() { return soundId; }
    public static String getError() { return error; }
    public static String getBridgeVersion() { return "KBMMovieAudio-2026-10-09-3-wav-recording"; }
}
