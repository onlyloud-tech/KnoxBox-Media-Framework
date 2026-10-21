/*
 * Coffee GB, Copyright (c) 2017 Tomasz Rekawek and contributors. MIT.
 * Modified for KnoxBox GB/GBC-only integration, 2026-10-01:
 * Remove SGB preset lookup and reject unsupported SGB display mode.
 * See the accompanying Coffee GB license and modification details.
 */
package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.sgb.Background;
import eu.rekawek.coffeegb.core.sgb.Commands;

public class SgbDisplay
implements Originator<SgbDisplay> {
    private static final int DMG_TILES_WIDTH = 20;
    private static final int DMG_TILES_HEIGHT = 18;
    private static final int DMG_WINDOW_X = 48;
    private static final int DMG_WINDOW_Y = 40;
    private volatile boolean sgbBorder;
    private final boolean sgb;
    private final EventBus sgbBus;
    private EventBus eventBus;
    private final int[] predefinedPalette;
    private final int[] sgbBuffer = new int[57344];
    private final int[] sgbMask = new int[57344];
    private final int[][] palettes = new int[4][4];
    private final int[][] systemPalettes = new int[512][4];
    private final int[] paletteMap = new int[360];
    private final int[][] attributeFiles = new int[45][360];
    private Commands.MaskEnCmd.GameboyScreenMask screenMask = Commands.MaskEnCmd.GameboyScreenMask.CANCEL;
    private int atfNumber = -1;

    public SgbDisplay(Rom rom, EventBus eventBus, boolean bl, boolean bl2) {
        this.sgbBorder = bl2;
        this.sgb = bl;
        this.sgbBus = eventBus;
        if (bl) {
            throw new UnsupportedOperationException("This KnoxBox build supports GB/GBC only, not SGB display mode");
        }
        // Unused while SGB is disabled; no firmware-derived preset data.
        this.predefinedPalette = new int[4];
    }

    public void init(EventBus eventBus) {
        this.palettes[0] = this.predefinedPalette;
        if (this.sgb) {
            this.eventBus = eventBus;
            eventBus.register(this::onSgbBackground, Background.SgbBackgroundReadyEvent.class);
            eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class);
            eventBus.register(setSgbBorder -> {
                this.sgbBorder = setSgbBorder.borderEnabled;
            }, SetSgbBorder.class);
            this.sgbBus.register(this::onAttrBlk, Commands.AttrBlkCmd.class);
            this.sgbBus.register(pal01Cmd -> {
                this.palettes[0] = pal01Cmd.getPalette0();
                this.palettes[1] = pal01Cmd.getPalette1();
            }, Commands.Pal01Cmd.class);
            this.sgbBus.register(pal03Cmd -> {
                this.palettes[0] = pal03Cmd.getPalette0();
                this.palettes[3] = pal03Cmd.getPalette3();
            }, Commands.Pal03Cmd.class);
            this.sgbBus.register(pal12Cmd -> {
                this.palettes[1] = pal12Cmd.getPalette1();
                this.palettes[2] = pal12Cmd.getPalette2();
            }, Commands.Pal12Cmd.class);
            this.sgbBus.register(pal23Cmd -> {
                this.palettes[2] = pal23Cmd.getPalette2();
                this.palettes[3] = pal23Cmd.getPalette3();
            }, Commands.Pal23Cmd.class);
            this.sgbBus.register(palTrnCmd -> {
                for (int i = 0; i < 512; ++i) {
                    this.systemPalettes[i] = palTrnCmd.getPalette(i);
                }
            }, Commands.PalTrnCmd.class);
            this.sgbBus.register(this::onPalSet, Commands.PalSetCmd.class);
            this.sgbBus.register(this::onAttrTransfer, Commands.AttrTrnCmd.class);
            this.sgbBus.register(this::onAttrSet, Commands.AttrSetCmd.class);
            this.sgbBus.register(maskEnCmd -> {
                this.screenMask = maskEnCmd.getScreenMask();
            }, Commands.MaskEnCmd.class);
        }
    }

    private void onAttrTransfer(Commands.AttrTrnCmd attrTrnCmd) {
        for (int i = 0; i < 45; ++i) {
            for (int j = 0; j < 360; ++j) {
                this.attributeFiles[i][j] = attrTrnCmd.getAttributeFile(i).getColor(j);
            }
        }
    }

    private void onPalSet(Commands.PalSetCmd palSetCmd) {
        for (int i = 0; i < 4; ++i) {
            this.palettes[i] = this.systemPalettes[palSetCmd.getPaletteIds()[i]];
        }
        if (palSetCmd.getApplyAtf()) {
            this.atfNumber = palSetCmd.getAtfNumber();
        }
        if (palSetCmd.getCancelMaskEn()) {
            this.screenMask = Commands.MaskEnCmd.GameboyScreenMask.CANCEL;
        }
    }

    private void onAttrSet(Commands.AttrSetCmd attrSetCmd) {
        if (attrSetCmd.getCancelMask()) {
            this.screenMask = Commands.MaskEnCmd.GameboyScreenMask.CANCEL;
        }
        this.atfNumber = attrSetCmd.getAttributeFileNumber();
    }

    private void onAttrBlk(Commands.AttrBlkCmd attrBlkCmd) {
        for (int i = 1; i <= attrBlkCmd.getDataSetsCount(); ++i) {
            Commands.AttrBlkCmd.DataSet dataSet = attrBlkCmd.getDataSet(i);
            for (int j = 0; j < 20; ++j) {
                for (int k = 0; k < 18; ++k) {
                    int n = j + k * 20;
                    if (dataSet.isOutside(j, k) && dataSet.changeColorsOutside()) {
                        this.paletteMap[n] = dataSet.paletteNumberOutside();
                    }
                    if (dataSet.isOnLine(j, k) && dataSet.changeLineColor()) {
                        this.paletteMap[n] = dataSet.paletteNumberLine();
                    }
                    if (dataSet.isOnLine(j, k) && dataSet.changeColorsInside() && !dataSet.changeColorsOutside()) {
                        this.paletteMap[n] = dataSet.paletteNumberInside();
                    }
                    if (dataSet.isOnLine(j, k) && !dataSet.changeColorsInside() && dataSet.changeColorsOutside()) {
                        this.paletteMap[n] = dataSet.paletteNumberOutside();
                    }
                    if (!dataSet.isInside(j, k) || !dataSet.changeColorsInside()) continue;
                    this.paletteMap[n] = dataSet.paletteNumberInside();
                }
            }
        }
    }

    private void onDmgFrame(Display.DmgFrameReadyEvent dmgFrameReadyEvent) {
        if (this.screenMask == Commands.MaskEnCmd.GameboyScreenMask.FREEZE) {
            return;
        }
        int n = this.sgbBorder ? 0 : 48;
        int n2 = this.sgbBorder ? 0 : 40;
        int n3 = this.sgbBorder ? 256 : 160;
        int n4 = this.sgbBorder ? 224 : 144;
        int[] nArray = new int[n3 * n4];
        int n5 = this.paletteMap[this.paletteMap.length - 1];
        if (this.atfNumber != -1) {
            int[] nArray2 = this.attributeFiles[this.atfNumber];
            n5 = nArray2[nArray2.length - 1];
        }
        for (int i = n; i < n + n3; ++i) {
            for (int j = n2; j < n2 + n4; ++j) {
                int n6;
                int n7;
                int n8 = this.sgbBuffer[i + j * 256];
                int n9 = this.sgbMask[i + j * 256];
                if (i >= 48 && i < 208 && j >= 40 && j < 184) {
                    n7 = i - 48;
                    int n10 = j - 40;
                    int n11 = n7 / 8;
                    int n12 = n10 / 8;
                    int n13 = n11 + n12 * 20;
                    int n14 = this.paletteMap[n13];
                    if (this.atfNumber >= 0) {
                        n14 = this.attributeFiles[this.atfNumber][n13];
                    }
                    int n15 = dmgFrameReadyEvent.pixels()[n7 + n10 * 160];
                    if (this.screenMask == Commands.MaskEnCmd.GameboyScreenMask.BLANK_COLOR0) {
                        n15 = 0;
                    }
                    if (n15 == 0) {
                        n14 = n5;
                    }
                    n6 = this.palettes[n14][n15];
                    if (this.screenMask == Commands.MaskEnCmd.GameboyScreenMask.BLANK_BLACK) {
                        n6 = 0;
                    }
                } else {
                    n6 = 0;
                }
                n7 = i - n + (j - n2) * n3;
                nArray[n7] = n9 == 0 ? Display.GbcFrameReadyEvent.translateGbcRgb(n6) : Display.GbcFrameReadyEvent.translateGbcRgb(n8);
            }
        }
        this.eventBus.post(new SgbFrameReadyEvent(nArray, this.sgbBorder));
    }

    private void onSgbBackground(Background.SgbBackgroundReadyEvent sgbBackgroundReadyEvent) {
        System.arraycopy(sgbBackgroundReadyEvent.buffer(), 0, this.sgbBuffer, 0, this.sgbBuffer.length);
        System.arraycopy(sgbBackgroundReadyEvent.mask(), 0, this.sgbMask, 0, this.sgbMask.length);
    }

    @Override
    public Memento<SgbDisplay> saveToMemento() {
        return new SgbDisplayMemento((int[])this.sgbBuffer.clone(), (int[])this.sgbMask.clone(), (int[][])this.palettes.clone(), (int[][])this.systemPalettes.clone(), (int[])this.paletteMap.clone(), (int[][])this.attributeFiles.clone(), this.screenMask, this.atfNumber);
    }

    @Override
    public void restoreFromMemento(Memento<SgbDisplay> memento) {
        if (!(memento instanceof SgbDisplayMemento)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        SgbDisplayMemento sgbDisplayMemento = (SgbDisplayMemento)memento;
        if (this.sgbBuffer.length != sgbDisplayMemento.sgbBuffer.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.sgbMask.length != sgbDisplayMemento.sgbMask.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.palettes.length != sgbDisplayMemento.palettes.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.systemPalettes.length != sgbDisplayMemento.systemPalettes.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.paletteMap.length != sgbDisplayMemento.paletteMap.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.attributeFiles.length != sgbDisplayMemento.attributeFiles.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        System.arraycopy(sgbDisplayMemento.sgbBuffer, 0, this.sgbBuffer, 0, this.sgbBuffer.length);
        System.arraycopy(sgbDisplayMemento.sgbMask, 0, this.sgbMask, 0, this.sgbMask.length);
        SgbDisplay.arraycopy2(sgbDisplayMemento.palettes, this.palettes);
        SgbDisplay.arraycopy2(sgbDisplayMemento.systemPalettes, this.systemPalettes);
        System.arraycopy(sgbDisplayMemento.paletteMap, 0, this.paletteMap, 0, this.paletteMap.length);
        SgbDisplay.arraycopy2(sgbDisplayMemento.attributeFiles, this.attributeFiles);
        this.screenMask = sgbDisplayMemento.screenMask;
        this.atfNumber = sgbDisplayMemento.atfNumber;
    }

    private static void arraycopy2(int[][] nArray, int[][] nArray2) {
        if (nArray.length != nArray2.length) {
            throw new IllegalArgumentException("Array length doesn't match");
        }
        for (int i = 0; i < nArray.length; ++i) {
            if (nArray[i] == null) {
                nArray2[i] = null;
                continue;
            }
            if (nArray[i].length != nArray2[i].length) {
                throw new IllegalArgumentException("Array length doesn't match at i=" + i);
            }
            System.arraycopy(nArray[i], 0, nArray2[i], 0, nArray[i].length);
        }
    }

    public record SetSgbBorder(boolean borderEnabled) implements Event
    {
    }

    public record SgbFrameReadyEvent(int[] buffer, boolean includeBorder) implements Event
    {
        public void toRgb(int[] nArray, boolean bl) {
            System.arraycopy(this.buffer, 0, nArray, 0, this.buffer.length);
        }
    }

    private record SgbDisplayMemento(int[] sgbBuffer, int[] sgbMask, int[][] palettes, int[][] systemPalettes, int[] paletteMap, int[][] attributeFiles, Commands.MaskEnCmd.GameboyScreenMask screenMask, int atfNumber) implements Memento<SgbDisplay>
    {
    }
}
