# Chipyard Trustformer Module

Chipyard peripheral wrappers for Trustformer-generated Kôika modules.

* `INTERFACE.md` — the wire protocol the generated modules speak. Read this first.
* `GenerateWrappers.py` --- reads `src/main/resources/vsrc/*.v`, writes
  `src/main/scala/<Module>Wrapper.scala` from `WrapperTemplate.txt`, and writes back
  `src/main/resources/regmap/<Module>.json` with the addresses it used.
* `src/main/scala/platform/` — hand-written per-design platform modules, for the
  ports that may not be memory-mapped (`INTERFACE.md`, "The platform module").

## Register widths

Spec variables are arbitrary-width, and a `RegField` may not straddle a bus word
(`RegMapper` requires `offset + width <= beatBytes * 8`). Every value is therefore
split into 32-bit words at consecutive 4-byte offsets, which fits any bus with
`beatBytes >= 4` and lines up with the `reg_read32`/`reg_write32` helpers in
`chipyard/tests/mmio.h`. Word *k* holds bits `[32k+31 : 32k]`; the last word is
narrower when the width is not a multiple of 32.

An address in the register-map JSON is the address of word 0, and the port reserves
as many 4-byte slots as it needs from there. A value that spans several words is
read or written a word at a time; for the command register, writing the *last* word
is what launches the command, so the lower words must be written first.

## Adding a module

1. Copy the generated Verilog into `src/main/resources/vsrc/`. The file name must
   match the module name inside it, so that the `BlackBox` resource resolves.
2. Optionally write `src/main/resources/regmap/<Module>.json` to pin the register
   addresses (offsets from the peripheral's base). Any key you leave out is assigned
   the next free 4-byte slot. The keys are the names of the `pub` ports plus the
   meta-register `<status>`; a key that matches no `pub` port is an error, so a
   renamed port cannot silently move:
   ```json
   {
       "<status>":                    { "address": "0x00" },
       "in_cmd":                      { "address": "0x04" },
       "in_param_pub_fs_in_pin":      { "address": "0x08" },
       "out_param_pub_fs_out_status": { "address": "0x10" }
   }
   ```
3. If the module has `sec` ports or IP links, write
   `src/main/scala/platform/<Module>Platform.scala` to drive them. Its Verilog
   dependencies (IP cores, adapters) go in a subdirectory of
   `src/main/resources/vsrc/`, which the generator does not scan.
4. `python3 GenerateWrappers.py` (or pass specific `.v` paths).
5. Mix `trustformer.CanHavePeriphery<Module>` into
   `chipyard/generators/chipyard/src/main/scala/DigitalTop.scala`.
6. Add a config to
   `chipyard/generators/chipyard/src/main/scala/config/TrustformerConfigs.scala`:
   ```scala
   class TF<Module>Config extends Config(
     new trustformer.With<Module>(address=0x4000) ++
     new freechips.rocketchip.rocket.WithNHugeCores(1) ++
     new chipyard.config.AbstractConfig)
   ```

`CustomCounter.v` predates the current codegen and does not follow the
`in_cmd`/`in_param_*`/`out_param_*` convention; the generator skips it and its
checked-in wrapper is kept as-is.
