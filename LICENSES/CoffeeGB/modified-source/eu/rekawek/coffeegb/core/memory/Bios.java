/* Coffee GB - Copyright (c) 2017 Tomasz Rekawek, MIT license.
 * KnoxBox modification 2026: firmware-free bootstrap SKIP only.
 * See LICENSES/CoffeeGB for the original license and modification details. */
package eu.rekawek.coffeegb.core.memory;
import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.GameboyType;
/** Disabled address space used by firmware-free SKIP initialization. */
public class Bios implements AddressSpace {
    public Bios() {}
    public Bios(GameboyType type) {
        throw new UnsupportedOperationException("Boot firmware is not included in KnoxBox");
    }
    public boolean accepts(int address) { return false; }
    public void setByte(int address, int value) {}
    public int getByte(int address) {
        throw new UnsupportedOperationException("Disabled boot firmware has no readable bytes");
    }
}
