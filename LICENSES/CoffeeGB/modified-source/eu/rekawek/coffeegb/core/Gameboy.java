/* Coffee GB - Copyright (c) 2017 Tomasz Rekawek, MIT license.
 * KnoxBox modification 2026: firmware-free bootstrap SKIP only.
 * See LICENSES/CoffeeGB for the original license and modification details. */
package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.Registers;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.Console;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.Genie;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.gpu.StatRegister;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.Bios;
import eu.rekawek.coffeegb.core.memory.BiosShadow;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.memory.Mmu;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.serial.SerialPort;
import eu.rekawek.coffeegb.core.sgb.Background;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.timer.Timer;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;

public class Gameboy
implements Runnable,
Serializable,
Originator<Gameboy>,
Closeable {
    public static final int TICKS_PER_SEC = 0x400000;
    public static final int TICKS_PER_FRAME = 69905;
    private final Cartridge cartridge;
    private final BiosShadow biosShadow;
    private final Gpu gpu;
    private final StatRegister statRegister;
    private final Mmu mmu;
    private final Ram oamRam;
    private final Cpu cpu;
    private final InterruptManager interruptManager;
    private final Timer timer;
    private final Dma dma;
    private final Hdma hdma;
    private final Display display;
    private final Sound sound;
    private final SerialPort serialPort;
    private final Joypad joypad;
    private final SpeedMode speedMode;
    private final SuperGameboy superGameboy;
    private final EventBus sgbBus;
    private final Background background;
    private final VRamTransfer vRamTransfer;
    private final SgbDisplay sgbDisplay;
    private final Genie gameGenie;
    private transient Console console;
    private volatile transient boolean doStop;
    private boolean requestedScreenRefresh;
    private boolean lcdDisabled;
    private volatile transient boolean doPause;
    private volatile transient boolean paused;

    public Gameboy(Rom rom) {
        this(new GameboyConfiguration(rom));
    }

    public Gameboy(GameboyConfiguration gameboyConfiguration) {
        if (gameboyConfiguration.bootstrapMode != BootstrapMode.SKIP) {
            throw new UnsupportedOperationException("KnoxBox supports firmware-free bootstrap SKIP only");
        }
        boolean bl = gameboyConfiguration.gameboyType == GameboyType.CGB;
        boolean bl2 = gameboyConfiguration.gameboyType == GameboyType.SGB;
        this.speedMode = new SpeedMode(bl);
        this.interruptManager = new InterruptManager(bl);
        this.timer = new Timer(this.interruptManager, this.speedMode);
        this.mmu = new Mmu(bl);
        this.display = new Display(bl);
        this.gameGenie = new Genie(this.mmu, bl);
        this.sgbBus = new EventBusImpl(null, null, false);
        this.sgbDisplay = new SgbDisplay(gameboyConfiguration.rom, this.sgbBus, bl2, gameboyConfiguration.displaySgbBorder);
        this.vRamTransfer = new VRamTransfer(this.sgbBus);
        this.superGameboy = new SuperGameboy(this.sgbBus);
        this.background = new Background(this.sgbBus);
        this.oamRam = new Ram(65024, 160);
        this.dma = new Dma(this.getAddressSpace(), this.oamRam, this.speedMode);
        this.statRegister = new StatRegister(this.interruptManager);
        this.gpu = new Gpu(this.display, this.dma, this.oamRam, this.vRamTransfer, this.statRegister, bl);
        this.statRegister.init(this.gpu);
        this.hdma = new Hdma(this.getAddressSpace());
        this.sound = new Sound(bl);
        this.joypad = new Joypad(this.interruptManager, this.sgbBus, bl2);
        this.serialPort = new SerialPort(this.interruptManager, bl, this.speedMode);
        this.cartridge = gameboyConfiguration.batteryData != null ? new Cartridge(gameboyConfiguration.rom, new MemoryBattery(gameboyConfiguration.batteryData)) : new Cartridge(gameboyConfiguration.rom, gameboyConfiguration.supportBatterySave);
        Bios bios = new Bios();
        this.biosShadow = new BiosShadow(bios, this.cartridge);
        this.mmu.addAddressSpace(this.biosShadow);
        this.mmu.addAddressSpace(this.gpu);
        this.mmu.addAddressSpace(this.statRegister);
        this.mmu.addAddressSpace(this.joypad);
        this.mmu.addAddressSpace(this.interruptManager);
        this.mmu.addAddressSpace(this.serialPort);
        this.mmu.addAddressSpace(this.timer);
        this.mmu.addAddressSpace(this.dma);
        this.mmu.addAddressSpace(this.sound);
        if (bl) {
            this.mmu.addAddressSpace(this.speedMode);
            this.mmu.addAddressSpace(this.hdma);
        }
        this.mmu.indexSpaces();
        this.cpu = new Cpu(this.getAddressSpace(), this.interruptManager, this.gpu, this.speedMode, this.display);
        this.interruptManager.disableInterrupts(false);
        if (gameboyConfiguration.bootstrapMode == BootstrapMode.FAST_FORWARD) {
            while (this.cpu.getRegisters().getPC() != 256) {
                this.tick();
            }
        } else if (gameboyConfiguration.bootstrapMode == BootstrapMode.SKIP) {
            this.biosShadow.setByte(65360, 0);
            Registers registers = this.cpu.getRegisters();
            if (bl) {
                registers.setAF(4480);
                registers.setBC(0);
                registers.setDE(65366);
                registers.setHL(13);
            } else {
                registers.setAF(432);
                registers.setBC(19);
                registers.setDE(216);
                registers.setHL(333);
            }
            registers.setSP(65534);
            registers.setPC(256);
        }
    }

    public void init(EventBus eventBus, SerialEndpoint serialEndpoint, Console console) {
        this.console = console;
        if (console != null) {
            console.setGameboy(this);
        }
        this.joypad.init(eventBus);
        this.display.init(eventBus);
        this.sound.init(eventBus);
        this.serialPort.init(serialEndpoint);
        this.background.init(eventBus);
        this.sgbDisplay.init(eventBus);
        this.gameGenie.init(eventBus);
        this.cartridge.init(eventBus);
    }

    @Override
    public void run() {
        this.doStop = false;
        while (!this.doStop) {
            if (this.doPause) {
                this.haltIfNeeded();
            }
            this.tick();
        }
    }

    private synchronized void haltIfNeeded() {
        this.paused = true;
        this.notifyAll();
        while (this.doPause && !this.doStop) {
            try {
                this.wait(10L);
            }
            catch (InterruptedException interruptedException) {
                throw new RuntimeException(interruptedException);
            }
        }
        this.paused = false;
    }

    public synchronized void stop() {
        this.doStop = true;
        this.notifyAll();
    }

    public boolean tick() {
        boolean bl = false;
        Mode mode = this.tickSubsystems();
        if (mode != null) {
            this.hdma.onGpuUpdate(mode);
        }
        if (!this.lcdDisabled && !this.gpu.isLcdEnabled()) {
            this.lcdDisabled = true;
            this.display.frameIsReady();
            this.hdma.onLcdSwitch(false);
            bl = true;
        } else if (mode == Mode.VBlank) {
            this.requestedScreenRefresh = true;
            this.display.frameIsReady();
            this.vRamTransfer.frameIsReady();
            bl = true;
        }
        if (this.lcdDisabled && this.gpu.isLcdEnabled()) {
            this.lcdDisabled = false;
            this.hdma.onLcdSwitch(true);
        } else if (this.requestedScreenRefresh && mode == Mode.OamSearch) {
            this.requestedScreenRefresh = false;
        }
        if (this.console != null) {
            this.console.tick();
        }
        return bl;
    }

    private Mode tickSubsystems() {
        this.timer.tick();
        if (this.hdma.isTransferInProgress()) {
            this.hdma.tick();
        } else {
            this.cpu.tick();
        }
        this.dma.tick();
        this.sound.tick();
        this.serialPort.tick();
        this.joypad.tick();
        Mode mode = this.gpu.tick();
        this.statRegister.tick();
        return mode;
    }

    public AddressSpace getAddressSpace() {
        return this.gameGenie;
    }

    public Cpu getCpu() {
        return this.cpu;
    }

    public SpeedMode getSpeedMode() {
        return this.speedMode;
    }

    public Gpu getGpu() {
        return this.gpu;
    }

    public Sound getSound() {
        return this.sound;
    }

    public synchronized void pause() {
        this.doPause = true;
        while (!this.paused && !this.doStop) {
            try {
                this.wait(10L);
            }
            catch (InterruptedException interruptedException) {
                throw new RuntimeException(interruptedException);
            }
        }
    }

    public synchronized void resume() {
        this.doPause = false;
        this.notifyAll();
        while (this.paused && !this.doStop) {
            try {
                this.wait(10L);
            }
            catch (InterruptedException interruptedException) {
                throw new RuntimeException(interruptedException);
            }
        }
    }

    public boolean isPaused() {
        return this.paused;
    }

    @Override
    public Memento<Gameboy> saveToMemento() {
        return new GameboyMemento(this.biosShadow.saveToMemento(), this.cartridge.saveToMemento(), this.gpu.saveToMemento(), this.statRegister.saveToMemento(), this.mmu.saveToMemento(), this.oamRam.saveToMemento(), this.cpu.saveToMemento(), this.interruptManager.saveToMemento(), this.timer.saveToMemento(), this.dma.saveToMemento(), this.hdma.saveToMemento(), this.display.saveToMemento(), this.sound.saveToMemento(), this.serialPort.saveToMemento(), this.joypad.saveToMemento(), this.speedMode.saveToMemento(), this.superGameboy.saveToMemento(), this.background.saveToMemento(), this.vRamTransfer.saveToMemento(), this.sgbDisplay.saveToMemento(), this.gameGenie.saveToMemento(), this.requestedScreenRefresh, this.lcdDisabled);
    }

    @Override
    public void restoreFromMemento(Memento<Gameboy> memento) {
        if (!(memento instanceof GameboyMemento)) {
            throw new IllegalArgumentException();
        }
        GameboyMemento gameboyMemento = (GameboyMemento)memento;
        this.biosShadow.restoreFromMemento(gameboyMemento.biosShadowMemento());
        this.cartridge.restoreFromMemento(gameboyMemento.cartridgeMemento());
        this.gpu.restoreFromMemento(gameboyMemento.gpuMemento());
        this.statRegister.restoreFromMemento(gameboyMemento.statRegisterMemento());
        this.mmu.restoreFromMemento(gameboyMemento.mmuMemento());
        this.oamRam.restoreFromMemento(gameboyMemento.oamRamMemento());
        this.cpu.restoreFromMemento(gameboyMemento.cpuMemento());
        this.interruptManager.restoreFromMemento(gameboyMemento.interruptManagerMemento());
        this.timer.restoreFromMemento(gameboyMemento.timerMemento());
        this.dma.restoreFromMemento(gameboyMemento.dmaMemento());
        this.hdma.restoreFromMemento(gameboyMemento.hdmaMemento());
        this.display.restoreFromMemento(gameboyMemento.displayMemento());
        this.sound.restoreFromMemento(gameboyMemento.soundMemento());
        this.serialPort.restoreFromMemento(gameboyMemento.serialPortMemento());
        this.joypad.restoreFromMemento(gameboyMemento.joypadMemento());
        this.speedMode.restoreFromMemento(gameboyMemento.speedModeMemento());
        this.superGameboy.restoreFromMemento(gameboyMemento.superGameboyMemento());
        this.background.restoreFromMemento(gameboyMemento.backgroundMemento());
        this.vRamTransfer.restoreFromMemento(gameboyMemento.vRamTransferMemento());
        this.sgbDisplay.restoreFromMemento(gameboyMemento.sgbDisplayMemento());
        this.gameGenie.restoreFromMemento(gameboyMemento.genieMemento());
        this.requestedScreenRefresh = gameboyMemento.requestScreenRefresh();
        this.lcdDisabled = gameboyMemento.lcdDisabled();
    }

    @Override
    public void close() {
        this.cartridge.flushBattery();
        this.sgbBus.close();
    }

    public static class GameboyConfiguration {
        private final Rom rom;
        private GameboyType gameboyType;
        private BootstrapMode bootstrapMode = BootstrapMode.SKIP;
        private byte[] batteryData;
        private boolean supportBatterySave = true;
        private boolean displaySgbBorder = true;

        public GameboyConfiguration(File file) throws IOException {
            this(new Rom(file));
        }

        public GameboyConfiguration(Rom rom) {
            this.rom = rom;
            this.gameboyType = rom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB ? GameboyType.DMG : GameboyType.CGB;
        }

        public GameboyConfiguration setGameboyType(GameboyType gameboyType) {
            this.gameboyType = gameboyType;
            return this;
        }

        public GameboyType getGameboyType() {
            return this.gameboyType;
        }

        public GameboyConfiguration setDisplaySgbBorder(boolean bl) {
            this.displaySgbBorder = bl;
            return this;
        }

        public GameboyConfiguration setBootstrapMode(BootstrapMode bootstrapMode) {
            this.bootstrapMode = bootstrapMode;
            return this;
        }

        public BootstrapMode getBootstrapMode() {
            return this.bootstrapMode;
        }

        public GameboyConfiguration setBatteryData(byte[] byArray) {
            this.batteryData = byArray;
            return this;
        }

        public GameboyConfiguration setSupportBatterySave(boolean bl) {
            this.supportBatterySave = bl;
            return this;
        }

        public Gameboy build() {
            return new Gameboy(this);
        }

        public Rom getRom() {
            return this.rom;
        }
    }

    public static enum BootstrapMode {
        NORMAL,
        FAST_FORWARD,
        SKIP;

    }

    private record GameboyMemento(Memento<BiosShadow> biosShadowMemento, Memento<Cartridge> cartridgeMemento, Memento<Gpu> gpuMemento, Memento<StatRegister> statRegisterMemento, Memento<Mmu> mmuMemento, Memento<Ram> oamRamMemento, Memento<Cpu> cpuMemento, Memento<InterruptManager> interruptManagerMemento, Memento<Timer> timerMemento, Memento<Dma> dmaMemento, Memento<Hdma> hdmaMemento, Memento<Display> displayMemento, Memento<Sound> soundMemento, Memento<SerialPort> serialPortMemento, Memento<Joypad> joypadMemento, Memento<SpeedMode> speedModeMemento, Memento<SuperGameboy> superGameboyMemento, Memento<Background> backgroundMemento, Memento<VRamTransfer> vRamTransferMemento, Memento<SgbDisplay> sgbDisplayMemento, Memento<Genie> genieMemento, boolean requestScreenRefresh, boolean lcdDisabled) implements Memento<Gameboy>
    {
    }
}
