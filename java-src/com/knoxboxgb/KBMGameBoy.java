/* Copyright (c) 2026 Grymlock.
 * SPDX-License-Identifier: LicenseRef-KnoxBox-Original-Content
 */
package com.knoxboxgb;

import com.knoxboxgb.KBMFrameTexture;
import com.vtbasic.gb.VTIGameBoyEngine;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import me.zed_0xff.zombie_buddy.Exposer;

@Exposer.LuaClass(name="KBMGameBoy")
public final class KBMGameBoy {
    private static final String BRIDGE_VERSION = "kbmgb-bridge-2026-04-24-skeleton";
    private static VTIGameBoyEngine engine;
    private static String lastError;

    private KBMGameBoy() {
    }

    public static String getBridgeVersion() {
        return BRIDGE_VERSION;
    }

    public static int getWidth() {
        return 160;
    }

    public static int getHeight() {
        return 144;
    }

    public static int btnA() {
        return 1;
    }

    public static int btnB() {
        return 2;
    }

    public static int btnSelect() {
        return 4;
    }

    public static int btnStart() {
        return 8;
    }

    public static int btnUp() {
        return 16;
    }

    public static int btnDown() {
        return 32;
    }

    public static int btnLeft() {
        return 64;
    }

    public static int btnRight() {
        return 128;
    }

    public static boolean init() {
        try {
            if (engine == null) {
                engine = new VTIGameBoyEngine();
            }
            return true;
        }
        catch (Throwable throwable) {
            lastError = KBMGameBoy.summarize(throwable);
            return false;
        }
    }

    public static boolean isReady() {
        return engine != null;
    }

    public static boolean isRomLoaded() {
        return engine != null && engine.isRomLoaded();
    }

    public static String getRomPath() {
        return engine == null ? "" : (engine.getRomPath() == null ? "" : engine.getRomPath());
    }

    public static boolean isCgb() {
        return engine != null && engine.isCgb();
    }

    private static String confineToRomDir(String string) {
        if (string == null || string.isEmpty()) {
            return "empty path";
        }
        try {
            File file = new File(string).getCanonicalFile();
            String string2 = KBMGameBoy.getRomDir();
            if (string2 == null || string2.isEmpty()) {
                return "rom dir unavailable";
            }
            File file2 = new File(string2).getCanonicalFile();
            File file3 = file.getParentFile();
            if (file3 == null || !file3.equals(file2) && !KBMGameBoy.isBundledRomParent(file3, "gb")) {
                return "path outside rom directory or packaged media/roms/gb";
            }
            String string3 = file.getName().toLowerCase();
            if (!string3.endsWith(".gb") && !string3.endsWith(".gbc")) {
                return "extension not allowed (must be .gb or .gbc)";
            }
            return null;
        }
        catch (Throwable throwable) {
            return "path resolution failed: " + throwable.getClass().getSimpleName();
        }
    }

    private static boolean isBundledRomParent(File file, String string) throws IOException {
        String string2 = file.getCanonicalPath().replace('\\', '/').toLowerCase();
        return string2.endsWith("/media/roms/" + string);
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public static String validateRomFile(String string) {
        String string2 = KBMGameBoy.confineToRomDir(string);
        if (string2 != null) {
            return string2;
        }
        File file = new File(string);
        if (!file.exists()) {
            return "file not found";
        }
        if (!file.isFile()) {
            return "not a regular file";
        }
        long l = file.length();
        if (l < 32768L) {
            return "file too small (" + l + " bytes; min 32 KiB)";
        }
        if (l > 0x800000L) {
            return "file too large (" + l + " bytes; max 8 MiB)";
        }
        if ((l & 0x3FFFL) != 0L) {
            return "size not a multiple of 16 KiB";
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");){
            byte[] byArray = new byte[336];
            int n = randomAccessFile.read(byArray);
            if (n < 336) {
                String string3 = "header truncated";
                return string3;
            }
            if ((byArray[260] & 0xFF) == 206 && (byArray[261] & 0xFF) == 237 && (byArray[262] & 0xFF) == 102) {
                if ((byArray[263] & 0xFF) == 102) return "";
            }
            String string4 = "missing Nintendo logo (not a Game Boy ROM)";
            return string4;
        }
        catch (Throwable throwable) {
            return "read error: " + throwable.getMessage();
        }
    }

    public static String validateRom(String string) {
        String string2 = KBMGameBoy.validateRomFile(string);
        return string2 == null ? "" : string2;
    }

    public static boolean loadROM(String string) {
        try {
            String string2;
            if (engine == null) {
                engine = new VTIGameBoyEngine();
            }
            if (!(string2 = KBMGameBoy.validateRomFile(string)).isEmpty()) {
                lastError = "rejected: " + string2;
                return false;
            }
            boolean bl = engine.loadROM(string);
            if (!bl) {
                lastError = "Coffee GB load failed";
            }
            return bl;
        }
        catch (Throwable throwable) {
            lastError = KBMGameBoy.summarize(throwable);
            return false;
        }
    }

    public static void runFrame() {
        if (engine == null) {
            return;
        }
        try {
            engine.runFrame();
        }
        catch (Throwable throwable) {
            lastError = KBMGameBoy.summarize(throwable);
        }
    }

    public static int[] getPixels() {
        if (engine == null && !KBMGameBoy.init()) {
            return new int[23040];
        }
        return engine.getPixels();
    }

    public static Object getFrameTexture() {
        if (engine == null && !KBMGameBoy.init()) {
            return null;
        }
        int[] nArray = engine.getPixels();
        if (nArray == null) {
            return null;
        }
        return KBMFrameTexture.update("gb", nArray, 160, 144);
    }

    public static String getFrameTextureStatus() {
        return KBMFrameTexture.getStatus("gb");
    }

    public static String getFrameTextureInfo() {
        return KBMFrameTexture.getInfo("gb");
    }

    public static String getRegionRLE2(int n, int n2, int n3, int n4, int n5, int n6) {
        if (engine == null) {
            return "";
        }
        int[] nArray = engine.getPixels();
        if (nArray == null) {
            return "";
        }
        if (n5 < 1) {
            n5 = 1;
        }
        if (n6 < 1) {
            n6 = 1;
        }
        int n7 = 160;
        int n8 = 144;
        if (n < 0) {
            n = 0;
        }
        if (n2 > n8) {
            n2 = n8;
        }
        if (n2 <= n) {
            return "";
        }
        int n9 = n7 / n5;
        if (n9 < 1) {
            n9 = 1;
        }
        HashMap<String, int[]> hashMap = new HashMap<String, int[]>();
        StringBuilder stringBuilder = new StringBuilder(2048);
        int n10 = -1;
        for (int i = n; i < n2; i += n5) {
            int n11 = i * n7;
            int n12 = n4 + ++n10 * n6;
            HashSet<String> hashSet = new HashSet<String>();
            int n13 = 0;
            int n14 = nArray[n11] & 0xFFFFFF;
            for (int j = 1; j <= n9; ++j) {
                int n15;
                int n16;
                if (j < n9) {
                    n16 = j * n5;
                    if (n16 >= n7) {
                        n16 = n7 - 1;
                    }
                    n15 = nArray[n11 + n16] & 0xFFFFFF;
                } else {
                    n15 = -1;
                }
                if (n15 == n14) continue;
                n16 = n3 + n13 * n6;
                int n17 = (j - n13) * n6;
                String string = n16 + "|" + n17 + "|" + n14;
                int[] nArray2 = (int[])hashMap.get(string);
                if (nArray2 != null && nArray2[5] == n10 - 1) {
                    nArray2[3] = nArray2[3] + n6;
                    nArray2[5] = n10;
                } else {
                    if (nArray2 != null) {
                        stringBuilder.append(nArray2[0]).append(',').append(nArray2[1]).append(',').append(nArray2[2]).append(',').append(nArray2[3]).append(',').append(nArray2[4]).append(',');
                        hashMap.remove(string);
                    }
                    int[] nArray3 = new int[]{n16, n12, n17, n6, n14, n10};
                    hashMap.put(string, nArray3);
                }
                hashSet.add(string);
                n13 = j;
                n14 = n15;
            }
            Iterator iterator = hashMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry entry = (Map.Entry)iterator.next();
                if (hashSet.contains(entry.getKey())) continue;
                int[] nArray4 = (int[])entry.getValue();
                stringBuilder.append(nArray4[0]).append(',').append(nArray4[1]).append(',').append(nArray4[2]).append(',').append(nArray4[3]).append(',').append(nArray4[4]).append(',');
                iterator.remove();
            }
        }
        for (int[] nArray5 : hashMap.values()) {
            stringBuilder.append(nArray5[0]).append(',').append(nArray5[1]).append(',').append(nArray5[2]).append(',').append(nArray5[3]).append(',').append(nArray5[4]).append(',');
        }
        return stringBuilder.toString();
    }

    public static String getScaledRegionRLE(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        if (engine == null) {
            return "";
        }
        int[] nArray = engine.getPixels();
        if (nArray == null) {
            return "";
        }
        if (n7 < 1) {
            n7 = 1;
        }
        if (n5 < 1 || n6 < 1) {
            return "";
        }
        int n8 = 160;
        int n9 = 144;
        if (n < 0) {
            n = 0;
        }
        if (n2 > n9) {
            n2 = n9;
        }
        if (n2 <= n) {
            return "";
        }
        int n10 = n2 - n;
        int n11 = (n8 + n7 - 1) / n7;
        int n12 = (n10 + n7 - 1) / n7;
        if (n11 < 1) {
            n11 = 1;
        }
        if (n12 < 1) {
            n12 = 1;
        }
        HashMap<String, int[]> hashMap = new HashMap<String, int[]>();
        StringBuilder stringBuilder = new StringBuilder(4096);
        for (int i = 0; i < n6; ++i) {
            int n13;
            int n14;
            int n15;
            int n16 = (i * 2 + 1) * n12 / (n6 * 2);
            if (n16 >= n12) {
                n16 = n12 - 1;
            }
            if ((n15 = n + n16 * n7) >= n2) {
                n15 = n2 - 1;
            }
            int n17 = n15 * n8;
            int n18 = n4 + i;
            HashSet<String> hashSet = new HashSet<String>();
            int n19 = 0;
            int n20 = n14 = n11 <= 1 ? 0 : n11 / (n5 * 2);
            if (n14 >= n11) {
                n14 = n11 - 1;
            }
            if ((n13 = n14 * n7) >= n8) {
                n13 = n8 - 1;
            }
            int n21 = nArray[n17 + n13] & 0xFFFFFF;
            for (int j = 1; j <= n5; ++j) {
                int n22;
                int n23;
                int n24;
                if (j < n5) {
                    n24 = (j * 2 + 1) * n11 / (n5 * 2);
                    if (n24 >= n11) {
                        n24 = n11 - 1;
                    }
                    if ((n23 = n24 * n7) >= n8) {
                        n23 = n8 - 1;
                    }
                    n22 = nArray[n17 + n23] & 0xFFFFFF;
                } else {
                    n22 = -1;
                }
                if (n22 == n21) continue;
                n24 = n3 + n19;
                n23 = j - n19;
                String string = n24 + "|" + n23 + "|" + n21;
                int[] nArray2 = (int[])hashMap.get(string);
                if (nArray2 != null && nArray2[5] == i - 1) {
                    nArray2[3] = nArray2[3] + 1;
                    nArray2[5] = i;
                } else {
                    if (nArray2 != null) {
                        stringBuilder.append(nArray2[0]).append(',').append(nArray2[1]).append(',').append(nArray2[2]).append(',').append(nArray2[3]).append(',').append(nArray2[4]).append(',');
                        hashMap.remove(string);
                    }
                    int[] nArray3 = new int[]{n24, n18, n23, 1, n21, i};
                    hashMap.put(string, nArray3);
                }
                hashSet.add(string);
                n19 = j;
                n21 = n22;
            }
            Iterator iterator = hashMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry entry = (Map.Entry)iterator.next();
                if (hashSet.contains(entry.getKey())) continue;
                int[] nArray4 = (int[])entry.getValue();
                stringBuilder.append(nArray4[0]).append(',').append(nArray4[1]).append(',').append(nArray4[2]).append(',').append(nArray4[3]).append(',').append(nArray4[4]).append(',');
                iterator.remove();
            }
        }
        for (int[] nArray5 : hashMap.values()) {
            stringBuilder.append(nArray5[0]).append(',').append(nArray5[1]).append(',').append(nArray5[2]).append(',').append(nArray5[3]).append(',').append(nArray5[4]).append(',');
        }
        return stringBuilder.toString();
    }

    public static void setButton(int n, int n2, boolean bl) {
        if (engine == null) {
            return;
        }
        engine.setButton(n, n2, bl);
    }

    public static void setPadMask(int n, int n2) {
        if (engine == null) {
            return;
        }
        engine.setPadMask(n, n2);
    }

    public static void reset() {
        if (engine != null) {
            engine.reset();
        }
    }

    public static void pauseAudio() {
        if (engine != null) {
            try {
                engine.pauseAudio();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    public static void destroy() {
        if (engine != null) {
            try {
                engine.destroy();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            engine = null;
        }
    }

    public static String getLastError() {
        return lastError;
    }

    public static String getRomDir() {
        try {
            String string = System.getProperty("user.home");
            File file = new File(string, "Zomboid" + File.separator + "kbm_gb_roms");
            return file.getAbsolutePath();
        }
        catch (Throwable throwable) {
            return "";
        }
    }

    public static String listRoms() {
        try {
            File file2 = new File(KBMGameBoy.getRomDir());
            if (!file2.isDirectory()) {
                return "";
            }
            File[] fileArray = file2.listFiles((file, string) -> {
                String string2 = string.toLowerCase();
                return string2.endsWith(".gb") || string2.endsWith(".gbc");
            });
            if (fileArray == null || fileArray.length == 0) {
                return "";
            }
            Arrays.sort(fileArray, Comparator.comparing(File::getName));
            StringBuilder stringBuilder = new StringBuilder();
            for (int i = 0; i < fileArray.length; ++i) {
                if (i > 0) {
                    stringBuilder.append('|');
                }
                stringBuilder.append(fileArray[i].getName());
            }
            return stringBuilder.toString();
        }
        catch (Throwable throwable) {
            return "";
        }
    }

    public static String resolveRomPath(String string) {
        if (string == null || string.isEmpty()) {
            return "";
        }
        File file = new File(string);
        if (file.isAbsolute() && file.exists()) {
            return file.getAbsolutePath();
        }
        File file2 = new File(KBMGameBoy.getRomDir(), string);
        return file2.getAbsolutePath();
    }

    public static String probeAudio() {
        try {
            AudioFormat audioFormat = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 44100.0f, 8, 2, 2, 44100.0f, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(info)) {
                return "fail: format not supported";
            }
            SourceDataLine sourceDataLine = AudioSystem.getSourceDataLine(audioFormat);
            sourceDataLine.open(audioFormat, 8192);
            sourceDataLine.start();
            sourceDataLine.drain();
            sourceDataLine.stop();
            sourceDataLine.close();
            return "ok";
        }
        catch (Throwable throwable) {
            return "fail: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
    }

    private static String summarize(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String string = throwable.getClass().getSimpleName();
        String string2 = throwable.getMessage();
        return string2 == null ? string : string + ": " + string2;
    }

    static {
        lastError = "";
    }
}
