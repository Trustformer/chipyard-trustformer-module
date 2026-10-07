# The wire interface of a Trustformer-generated module

Ground truth is `coq/Backend/Lowering.v` in the Trustformer repo (`Sigma`,
`ext_fn_specs`, `rule_cmd_guard`, `rules`), with the port classes from
`coq/Contract.v`. This file exists so nobody has to re-derive it from the Coq.

A generated module's ports are the Kôika **external functions** of the synthesis
harness. `cuttlec` names the value the module passes *out* `<fn>_arg` and the
value handed *back* to it `<fn>_out` — which reads backwards for most of the
families, so **classify ports by name, never by width or by the `_arg`/`_out`
suffix**.

| external function | `_arg` (module output) | `_out` (module input) |
| --- | --- | --- |
| `in_cmd : bits_t 1 ~> maybe (bits_t A)` | `ready`, 1 bit | `{valid, command}`, `A+1` bits |
| `in_param_<c>_<x> : bits_t 1 ~> inputs_t x` | constant `1'b1`, carries nothing | the argument, width of `x` |
| `out_param_<c>_<y> : outputs_t y ~> bits_t 1` | the result, width of `y` | an ack, 1 bit |
| `ip_req_sec_<p> : bits_t (1+R) ~> bits_t 1` | `{strobe, payload}`, `1+R` bits | an ack, 1 bit |
| `ip_resp_sec_<p> : bits_t 1 ~> inputs_t r` | constant `1'b1`, carries nothing | the IP's answer, width of `r` |

`A` is `tf_action_reg_size` — the width of the action encoding (16 in the
examples), *not* the data width. `R` is the IP's `ip_req_sz`, and `r` the input
variable that carries IP `p`'s answer.

## Port classes

`<c>` is the port's confidentiality class from the design's `TFSchedContext`,
`pub` or `sec`. `coq/Contract.v` defines it: `pub` is attacker-visible and
memory-mappable, `sec` is everything else. IP links are always `sec`.

So **only `pub` ports may be memory-mapped**. A `sec` port carries, or may carry,
a secret — a seed, a key, a request to a crypto IP — and is driven by the
platform, never by software.

## Protocol

1. **A command is taken on any cycle where `ready && valid` and the command word
   equals one of the action encodings.** Nothing else marks acceptance:
   `cmd_ack` exists as an internal register but is not a port, and `rule_busy`,
   the only rule that writes it, has no effect anything reads.
2. **Arguments are latched in the accept cycle**, combinationally, straight off
   `in_param_<c>_<x>_out`. They must therefore be *stable before* the command is
   issued — hence the software sequence "write all arguments, then write the
   command register", and hence holding registers in the wrapper. The `_arg` side
   of an input is a hardwired `1'b1`; it is not a "sample now" strobe. This holds
   for `sec` inputs too: the platform must present them before the command.
3. **`ready` is 1 whenever the module can take a command.** An action that
   completes in its accept cycle never drops it, so a `valid` held high for N
   cycles is N executions of the action. An action that runs longer drops
   `ready` until it retires. Either way the environment must drop `valid` after
   observing one `ready && valid`, and must **not** wait for `ready` to fall.
4. **Results persist.** `out_param_<c>_<y>_arg` is driven from an output register
   that is only rewritten by an action that writes `y`; an action that does not
   write it leaves the previous value visible. A stale-looking read is the
   specified behaviour, not a leak introduced by the wrapper.
5. **The ack inputs are ignorable.** `out_param_<c>_<y>_out` and
   `ip_req_sec_<p>_out` are latched into registers nothing reads. The generated
   wrapper ties them to `false.B`.
6. **An IP request is a one-cycle strobe, and its answer is read exactly
   `ip_lat` cycles later.** There is no valid line: the design declares `ip_lat`
   and the wait is compiled into the action. Whoever attaches the IP must make the
   answer ready by then *and* still present on `ip_resp_sec_<p>_out` at that
   cycle — an adapter that holds the last answer does both.

## What the generated wrapper does with this

`GenerateWrappers.py` emits, per module:

* a `cmd_pending` register: the bus write is accepted when no command is pending,
  `valid` is then held until the module takes it and dropped in the same cycle
  (rule 3);
* one holding register per `in_param_pub_<x>` (rule 2), exposed as a RW MMIO
  register;
* one read-only MMIO register per `out_param_pub_<y>`, wired straight to `_arg`;
* `busy = cmd_pending || !ready`, exposed as bit 0 of the status register and as the
  module's top-level `_busy` output;
* if the module has any `sec` port or IP link, a `<Module>PlatformIO` bundle and
  an instance of `<Module>Platform` (below).

## The platform module

Everything that may not touch the bus — `sec` inputs and outputs, IP requests and
answers — is collected into `<Module>PlatformIO`, together with a read-only view
of the `pub` outputs. The wrapper instantiates `<Module>Platform` and connects it.

That module is **hand-written**, per design, in
`src/main/scala/platform/<Module>Platform.scala`, in package `trustformer`:

```scala
class <Module>Platform(implicit p: Parameters) extends Module {
  val io = IO(new <Module>PlatformIO)
  // drive every Output: secret inputs, IP answers
}
```

It is where a design's own platform lives: where its secret inputs come from,
which IP sits behind each link, and any config it needs (its own `Field`s and
config fragments, read through `p`). The generator never writes or reads it; it
refuses a module that needs one and does not have it. A secret input the platform
leaves undriven is a build error, because firtool rejects an output that is not
fully initialized.

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
  wrapper concatenates onto `in_param_pub_<x>_out`;
* an output of width *W* becomes `ceil(W/32)` read-only registers, each a slice of
  `out_param_pub_<y>_arg`;
* a command of width *A* becomes `ceil(A/32)` registers of which only the **last** is
  the trigger — writing it is what launches the command, so the lower words have to
  be written first. For the usual `A <= 32` there is a single register and this is
  just "write the command".
