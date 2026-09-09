# The wire interface of a Trustformer-generated module

Ground truth is `coq/TypedSynthesis.v` in the Trustformer repo (`Sigma`,
`ext_fn_specs`, `rule_cmd_guard`, `rules`). This file exists so nobody has to
re-derive it from the Coq.

A generated module's ports are the Kôika **external functions** of the synthesis
harness. `cuttlec` names the value the module passes *out* `<fn>_arg` and the
value handed *back* to it `<fn>_out` — which reads backwards for two of the three
families, so **classify ports by name, never by width or by the `_arg`/`_out`
suffix**.

| external function | `_arg` (module output) | `_out` (module input) |
| --- | --- | --- |
| `in_cmd : bits_t 1 ~> maybe (bits_t A)` | `ready`, 1 bit | `{valid, command}`, `A+1` bits |
| `in_param_<x> : bits_t 1 ~> inputs_t x` | constant `1'b1`, carries nothing | the argument, width of `x` |
| `out_param_<y> : outputs_t y ~> bits_t 1` | the result, width of `y` | an ack, 1 bit |

`A` is `tf_action_reg_size` — the width of the action encoding (16 in the
examples), *not* the data width.

## Protocol

1. **A command is taken on any cycle where `ready && valid` and the command word
   equals one of the action encodings.** Nothing else marks acceptance: `cmd_ack`
   exists as an internal register but is not a port, and `rule_busy`, which is the
   only rule that writes it, is dead code.
2. **Arguments are latched in the accept cycle**, combinationally, straight off
   `in_param_<x>_out`. They must therefore be *stable before* the command is
   issued — hence the software sequence "write all arguments, then write the
   command register", and hence holding registers in the wrapper. The `_arg` side
   of an input is a hardwired `1'b1`; it is not a "sample now" strobe.
3. **`ready` is constant `1` whenever the action fits in a single cycle**, which is
   true of seven of the eight shipped examples. A `valid` held high for N cycles is
   therefore N executions of the action. The environment must drop `valid` after
   observing one `ready && valid`, and must **not** wait for `ready` to fall — it may
   never fall.
4. **Results persist.** `out_param_<y>_arg` is driven from an output register that
   is only rewritten by an action that writes `y`; an action that does not write it
   leaves the previous value visible. A stale-looking read is the specified
   behaviour, not a leak introduced by the wrapper.
5. **The ack input is ignorable.** `out_param_<y>_out` is latched into `out_ack_<y>`,
   which nothing reads. The generated wrapper ties it to `false.B`.

## What the generated wrapper does with this

`GenerateWrappers.py` emits, per module:

* a `cmd_pending` register: the bus write is accepted when no command is pending,
  `valid` is then held until the module takes it and dropped in the same cycle
  (rule 3);
* one holding register per `in_param_<x>` (rule 2), exposed as a RW MMIO register;
* one read-only MMIO register per `out_param_<y>`, wired straight to `_arg`;
* `busy = cmd_pending || !ready`, exposed as bit 0 of the status register and as the
  module's top-level `_busy` output.

## Register widths

Nothing in the spec constrains a variable's width — `tfs_spec_states_size` and its
input/output counterparts return an arbitrary `nat`, and `tf_action_reg_size` sets
the command width independently. The bus does constrain it: `RegMapper` requires
`offset + field.width <= beatBytes * 8`, so a single register may not straddle a bus
word.

The generator therefore splits every value into 32-bit words at consecutive 4-byte
offsets. 32 bits at a 4-byte-aligned offset fits a word on any bus with
`beatBytes >= 4`, and it matches the `reg_read32` / `reg_write32` helpers on the
software side. Word *k* carries bits `[32k+31 : 32k]`; the last word is narrower when
the width is not a multiple of 32.

* an input of width *W* becomes `ceil(W/32)` read-write holding registers, which the
  wrapper concatenates onto `in_param_<x>_out`;
* an output of width *W* becomes `ceil(W/32)` read-only registers, each a slice of
  `out_param_<y>_arg`;
* a command of width *A* becomes `ceil(A/32)` registers of which only the **last** is
  the trigger — writing it is what launches the command, so the lower words have to
  be written first. For the usual `A <= 32` there is a single register and this is
  just "write the command".
