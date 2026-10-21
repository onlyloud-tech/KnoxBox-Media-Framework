# Coffee GB modifications for KnoxBox Game Boy

Modified October 1–2, 2026 for the GB/GBC-only KnoxBox integration. Coffee GB's MIT notice is preserved in `LICENSE.txt`; upstream project: https://github.com/trekawek/coffee-gb.

## SGB palette removal

`SgbDisplay` no longer initializes the SGB preset-palette lookup, and SGB display mode is explicitly unsupported. `sgb-palettes.bin`, `DefinedPalettes.class` and its anonymous map class were removed. Normal DMG and native GBC rendering paths remain unchanged.

## Boot-firmware removal

`bios/dmg_boot.bin`, `bios/cgb_boot.bin` and `bios/sgb_boot.bin` were removed. `Gameboy` accepts firmware-free `SKIP` bootstrap only and rejects `NORMAL` and `FAST_FORWARD` before engine construction. `Bios` is a disabled address space with no firmware loader or data. Existing post-boot register setup remains intact.

The changed `SgbDisplay.java`, `Gameboy.java`, and `Bios.java` files are under `modified-source/`.
