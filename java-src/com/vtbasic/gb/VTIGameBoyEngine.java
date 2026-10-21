/* Copyright (c) 2026 Grymlock.
 * SPDX-License-Identifier: LicenseRef-KnoxBox-Original-Content
 */
package com.vtbasic.gb;

import com.vtbasic.gb.VTIGameBoyEngine$AudioWorker;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import java.io.File;

public class VTIGameBoyEngine {
    public static final int SCREEN_WIDTH = 160;
    public static final int SCREEN_HEIGHT = 144;
    public static final int BTN_A = 1;
    public static final int BTN_B = 2;
    public static final int BTN_SELECT = 4;
    public static final int BTN_START = 8;
    public static final int BTN_UP = 16;
    public static final int BTN_DOWN = 32;
    public static final int BTN_LEFT = 64;
    public static final int BTN_RIGHT = 128;
    private static final int[] DMG_PALETTE_ARGB = new int[]{-1640230, -6698874, -12355223, -16441558};
    private Gameboy gameboy;
    private EventBus bus;
    private final int[] pixelBuffer = new int[23040];
    private boolean romLoaded = false;
    private String romPath = null;
    private int pad1Mask = 0;
    private int prevPad1Mask = 0;
    private boolean isCgb = false;
    private VTIGameBoyEngine$AudioWorker audioWorker;
    private Thread audioThread;

    public VTIGameBoyEngine() {
        this.fillTestPattern();
    }

    public boolean loadROM(String string) {
        if (string == null || string.isEmpty()) {
            return false;
        }
        try {
            this.destroy();
            File file = new File(string);
            Rom rom = new Rom(file);
            Rom.GameboyColorFlag gameboyColorFlag = rom.getGameboyColorFlag();
            this.isCgb = gameboyColorFlag == Rom.GameboyColorFlag.CGB || gameboyColorFlag == Rom.GameboyColorFlag.UNIVERSAL;
            Gameboy.GameboyConfiguration gameboyConfiguration = new Gameboy.GameboyConfiguration(rom);
            gameboyConfiguration.setGameboyType(this.isCgb ? GameboyType.CGB : GameboyType.DMG);
            this.gameboy = new Gameboy(gameboyConfiguration);
            this.bus = new EventBusImpl(null, null, false);
            if (this.isCgb) {
                this.bus.register(gbcFrameReadyEvent -> {
                    int[] nArray = gbcFrameReadyEvent.pixels();
                    for (int i = 0; i < nArray.length && i < this.pixelBuffer.length; ++i) {
                        this.pixelBuffer[i] = 0xFF000000 | Display.GbcFrameReadyEvent.translateGbcRgb(nArray[i]);
                    }
                }, Display.GbcFrameReadyEvent.class);
            } else {
                this.bus.register(dmgFrameReadyEvent -> {
                    int[] nArray = dmgFrameReadyEvent.pixels();
                    for (int i = 0; i < nArray.length && i < this.pixelBuffer.length; ++i) {
                        int n = nArray[i] & 3;
                        this.pixelBuffer[i] = DMG_PALETTE_ARGB[n];
                    }
                }, Display.DmgFrameReadyEvent.class);
            }
            this.audioWorker = new VTIGameBoyEngine$AudioWorker(this.bus);
            this.audioThread = new Thread((Runnable)this.audioWorker, "VTIGameBoy-audio");
            this.audioThread.setDaemon(true);
            this.gameboy.init(this.bus, SerialEndpoint.NULL_ENDPOINT, null);
            this.audioThread.start();
            this.romLoaded = true;
            this.romPath = string;
            return true;
        }
        catch (Throwable throwable) {
            System.err.println("[VTIGameBoyEngine] loadROM failed: " + String.valueOf(throwable));
            throwable.printStackTrace();
            this.romLoaded = false;
            return false;
        }
    }

    public boolean isRomLoaded() {
        return this.romLoaded;
    }

    public String getRomPath() {
        return this.romPath;
    }

    public boolean isCgb() {
        return this.isCgb;
    }

    public void runFrame() {
        if (this.gameboy == null || !this.romLoaded) {
            return;
        }
        try {
            this.applyControllerMask();
            int n = 209715;
            for (int i = 0; i < n && !this.gameboy.tick(); ++i) {
            }
        }
        catch (Throwable throwable) {
            System.err.println("[VTIGameBoyEngine] runFrame failed: " + String.valueOf(throwable));
        }
    }

    public int[] getPixels() {
        return this.pixelBuffer;
    }

    public void setPadMask(int n, int n2) {
        if (n == 1) {
            this.pad1Mask = n2;
        }
    }

    public void setButton(int n, int n2, boolean bl) {
        if (n != 1) {
            return;
        }
        this.pad1Mask = bl ? (this.pad1Mask |= n2) : (this.pad1Mask &= ~n2);
    }

    public void reset() {
        if (this.romPath != null) {
            String string = this.romPath;
            this.loadROM(string);
        }
    }

    public void pauseAudio() {
        if (this.audioWorker != null) {
            this.audioWorker.requestStop();
        }
    }

    public void destroy() {
        try {
            this.pauseAudio();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (this.audioThread != null) {
            try {
                this.audioThread.join(500L);
            }
            catch (InterruptedException interruptedException) {
                // empty catch block
            }
            this.audioThread = null;
        }
        this.audioWorker = null;
        if (this.gameboy != null) {
            try {
                this.gameboy.close();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            this.gameboy = null;
        }
        if (this.bus != null) {
            try {
                this.bus.close();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            this.bus = null;
        }
        this.romLoaded = false;
        this.romPath = null;
    }

    private void applyControllerMask() {
        int n = this.pad1Mask ^ this.prevPad1Mask;
        if (n == 0) {
            return;
        }
        this.emitIfChanged(n, 1, Button.A);
        this.emitIfChanged(n, 2, Button.B);
        this.emitIfChanged(n, 4, Button.SELECT);
        this.emitIfChanged(n, 8, Button.START);
        this.emitIfChanged(n, 16, Button.UP);
        this.emitIfChanged(n, 32, Button.DOWN);
        this.emitIfChanged(n, 64, Button.LEFT);
        this.emitIfChanged(n, 128, Button.RIGHT);
        this.prevPad1Mask = this.pad1Mask;
    }

    private void emitIfChanged(int n, int n2, Button button) {
        if ((n & n2) == 0) {
            return;
        }
        if ((this.pad1Mask & n2) != 0) {
            this.bus.post(new ButtonPressEvent(button));
        } else {
            this.bus.post(new ButtonReleaseEvent(button));
        }
    }

    private void fillTestPattern() {
        for (int i = 0; i < 144; ++i) {
            for (int j = 0; j < 160; ++j) {
                int n = (j / 8 + i / 8) % 4;
                this.pixelBuffer[i * 160 + j] = DMG_PALETTE_ARGB[n];
            }
        }
    }
}
