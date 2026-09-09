# Chipyard Trustformer Module

Chipyard peripheral wrappers for Trustformer-generated Kôika modules.

* `INTERFACE.md` — the wire protocol the generated modules speak. Read this first.
* `GenerateWrappers.py` — reads `src/main/resources/vsrc/*.v`, writes
  `src/main/scala/<Module>Wrapper.scala` from `WrapperTemplate.txt`, and writes back
  `src/main/resources/regmap/<Module>.json` with the addresses it used.

## Adding a module

1. Copy the generated Verilog into `src/main/resources/vsrc/`. The file name must
   match the module name inside it, so that the `BlackBox` resource resolves.
2. Optionally write `src/main/resources/regmap/<Module>.json` to pin the register
   addresses (offsets from the peripheral's base). Any key you leave out is assigned
   the next free 4-byte slot. The keys are the external-function names plus the
   meta-register `<status>`:
   ```json
   {
       "<status>":                { "address": "0x00" },
       "in_cmd":                  { "address": "0x04" },
       "in_param_fs_in_pin":      { "address": "0x08" },
       "out_param_fs_out_status": { "address": "0x10" }
   }
   ```
3. `python3 GenerateWrappers.py` (or pass specific `.v` paths).
4. Mix `trustformer.CanHavePeriphery<Module>` into
   `chipyard/generators/chipyard/src/main/scala/DigitalTop.scala`.
5. Add a config to
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
