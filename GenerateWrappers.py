#!/usr/bin/env python3
"""Generate a Chisel MMIO wrapper for each Trustformer-generated Verilog module.

For every `src/main/resources/vsrc/*.v` this emits
`src/main/scala/<Module>Wrapper.scala` from `WrapperTemplate.txt`, and writes back
`src/main/resources/regmap/<Module>.json` with the addresses it used.

Port convention
---------------
The module's ports are the Koika *external functions* declared in
`coq/TypedSynthesis.v` (`Sigma` / `ext_fn_specs`).  `cuttlec` names the argument
the module passes out `<fn>_arg` and the value handed back `<fn>_out`, which
hides the direction of the protocol -- so classify by the function *name*, never
by the port width (widths change with the spec; the older width heuristic broke
the moment an action code stopped being 32 bits wide):

    in_cmd        : bits_t 1 ~> maybe (bits_t A)
        in_cmd_arg  (out, 1)    the module's `ready`
        in_cmd_out  (in,  A+1)  {valid, command}; taken on `ready && valid`
    in_param_<x>  : bits_t 1 ~> inputs_t x
        in_param_<x>_arg (out, 1)   constant 1'b1, carries nothing
        in_param_<x>_out (in,  W)   the argument, sampled in the accept cycle
    out_param_<y> : outputs_t y ~> bits_t 1
        out_param_<y>_arg (out, W)  the result
        out_param_<y>_out (in,  1)  an ack latched into a register nothing reads

Two facts drive the generated logic:
  * arguments are latched combinationally in the cycle the command is accepted,
    so they need *holding registers* -- the MMIO sequence is "write args, then
    write the command register" (paper, 05_design/01_functional_spec.tex).
  * `ready` is constant 1 whenever the action fits in one cycle, so a `valid`
    held high for N cycles executes the action N times.  The wrapper drops
    `valid` after a single `ready && valid`, and never waits for `ready` to fall.
"""

import json
import os
import re
import sys

VSRC_DIR = os.path.join("src", "main", "resources", "vsrc")
REGMAP_DIR = os.path.join("src", "main", "resources", "regmap")
SCALA_DIR = os.path.join("src", "main", "scala")
TEMPLATE = "WrapperTemplate.txt"

STATUS_KEY = "<status>"
MAX_REG_WIDTH = 64  # one RegField must fit in a bus beat


def substitute(template, name, lines):
    """Replace {{name}} with `lines`, indented to the placeholder's own column."""
    placeholder = "{{%s}}" % name
    out = []
    for line in template.splitlines():
        if placeholder in line:
            indent = line[: len(line) - len(line.lstrip())]
            out.extend(indent + l if l else l for l in lines)
        else:
            out.append(line)
    return "\n".join(out) + "\n"


class UnsupportedModule(Exception):
    pass


def parse_ports(path):
    """-> (module_name, {port_name: (direction, width)})"""
    with open(path) as f:
        content = f.read()
    content = " ".join(line.split("//")[0] for line in content.splitlines())
    content = re.sub(r"/\*.*?\*/", "", content, flags=re.DOTALL)

    match = re.search(r"\bmodule\s+(\w+)\s*\((.*?)\)\s*;", content)
    if not match:
        raise UnsupportedModule("no module definition found")
    module_name = match.group(1)

    ports = {}
    for decl in match.group(2).split(","):
        decl = decl.strip()
        m = re.match(r"(input|output)\s+wire\s*(?:\[(\d+):0\])?\s*(\w+)$", decl)
        if not m:
            raise UnsupportedModule("cannot parse port declaration %r" % decl)
        direction, msb, name = m.group(1), m.group(2), m.group(3)
        ports[name] = (direction, int(msb) + 1 if msb else 1)
    return module_name, ports


def classify(ports):
    """Split the port map into the three external-function families.

    -> (cmd_width, [(name, width)] inputs, [(name, width)] outputs)
    """
    if "in_cmd_arg" not in ports or "in_cmd_out" not in ports:
        raise UnsupportedModule(
            "no in_cmd_arg/in_cmd_out pair -- not a Trustformer-generated interface"
        )

    def expect(name, direction, width=None):
        if name not in ports:
            raise UnsupportedModule("missing port %s" % name)
        got_dir, got_width = ports[name]
        if got_dir != direction:
            raise UnsupportedModule(
                "port %s is %s, expected %s" % (name, got_dir, direction)
            )
        if width is not None and got_width != width:
            raise UnsupportedModule(
                "port %s is %d bits, expected %d" % (name, got_width, width)
            )
        return got_width

    expect("in_cmd_arg", "output", 1)
    cmd_width = expect("in_cmd_out", "input") - 1  # the extra bit is `valid`
    if cmd_width < 1 or cmd_width > MAX_REG_WIDTH:
        raise UnsupportedModule("unsupported command width %d" % cmd_width)

    inputs, outputs = [], []
    for name in sorted(ports):
        if name in ("CLK", "RST_N", "in_cmd_arg", "in_cmd_out"):
            continue
        if name.startswith("in_param_") and name.endswith("_out"):
            base = name[: -len("_out")]
            expect(base + "_arg", "output", 1)
            inputs.append((base, expect(name, "input")))
        elif name.startswith("out_param_") and name.endswith("_arg"):
            base = name[: -len("_arg")]
            expect(base + "_out", "input", 1)
            outputs.append((base, expect(name, "output")))
        elif name.startswith("in_param_") and name.endswith("_arg"):
            continue  # checked above, alongside its _out
        elif name.startswith("out_param_") and name.endswith("_out"):
            continue  # checked above, alongside its _arg
        else:
            raise UnsupportedModule("unexpected port %s" % name)

    for base, width in inputs + outputs:
        if width > MAX_REG_WIDTH:
            raise UnsupportedModule(
                "%s is %d bits, wider than one register" % (base, width)
            )
    return cmd_width, inputs, outputs


def load_or_build_regmap(base_name, inputs, outputs):
    """Read the checked-in register map, filling in any missing entry."""
    path = os.path.join(REGMAP_DIR, base_name + ".json")
    regmap = {}
    if os.path.exists(path):
        with open(path) as f:
            regmap = json.load(f)

    wanted = [STATUS_KEY, "in_cmd"] + [b for b, _ in inputs] + [b for b, _ in outputs]
    used = {
        int(regmap[k]["address"], 0)
        for k in regmap
        if regmap.get(k, {}).get("address", "").strip()
    }
    next_addr = 0
    for key in wanted:
        if key in regmap and regmap[key].get("address", "").strip():
            continue
        while next_addr in used:
            next_addr += 4
        regmap[key] = {"address": "0x%02X" % next_addr}
        used.add(next_addr)

    os.makedirs(REGMAP_DIR, exist_ok=True)
    with open(path, "w") as f:
        json.dump(regmap, f, indent=4)
        f.write("\n")
    return regmap


def generate(file_path):
    module_name, ports = parse_ports(file_path)
    cmd_width, inputs, outputs = classify(ports)
    base_name = os.path.splitext(os.path.basename(file_path))[0]
    if base_name != module_name:
        raise UnsupportedModule(
            "file %s declares module %s; they must match so that the BlackBox "
            "resource resolves" % (os.path.basename(file_path), module_name)
        )
    regmap = load_or_build_regmap(base_name, inputs, outputs)

    bundle, chisel, conn, regs = [], [], [], []

    def addr(key):
        a = regmap.get(key, {}).get("address", "").strip()
        return a or None

    # -- command channel ---------------------------------------------------
    bundle += [
        "val in_cmd_arg = Output(Bool()) // the module's `ready`",
        "val in_cmd_out = Input(UInt(%d.W)) // [%d] is valid, [%d:0] the command"
        % (cmd_width + 1, cmd_width, cmd_width - 1),
    ]
    chisel += [
        "val cmd = Wire(new DecoupledIO(UInt(%d.W)))" % cmd_width,
        "val cmd_reg = RegInit(0.U(%d.W))" % cmd_width,
        "val cmd_pending = RegInit(false.B)",
        "val busy = Wire(Bool())",
    ]
    conn += [
        "// Accept one command at a time from the bus, then present it to the",
        "// module until it takes it -- and drop `valid` immediately afterwards:",
        "// `ready` is constant 1 for single-cycle modules, so a held `valid`",
        "// would re-execute the action every cycle.",
        "cmd.ready := !cmd_pending",
        "when (cmd.fire) {",
        "  cmd_reg := cmd.bits",
        "  cmd_pending := true.B",
        "} .elsewhen (cmd_pending && impl_io.in_cmd_arg) {",
        "  cmd_pending := false.B",
        "}",
        "impl_io.in_cmd_out := Cat(cmd_pending, cmd_reg)",
        "busy := cmd_pending || !impl_io.in_cmd_arg",
    ]
    if addr("in_cmd"):
        regs.append("%s -> Seq(RegField.w(%d, cmd))," % (addr("in_cmd"), cmd_width))

    # -- input arguments ---------------------------------------------------
    for base, width in inputs:
        bundle += [
            "val %s_arg = Output(Bool()) // hardwired 1'b1, unused" % base,
            "val %s_out = Input(UInt(%d.W))" % (base, width),
        ]
        chisel.append("val %s_reg = RegInit(0.U(%d.W))" % (base, width))
        conn += [
            "// held, not pulsed: the module samples this in the accept cycle",
            "impl_io.%s_out := %s_reg" % (base, base),
        ]
        if addr(base):
            regs.append("%s -> Seq(RegField(%d, %s_reg))," % (addr(base), width, base))

    # -- outputs -----------------------------------------------------------
    for base, width in outputs:
        bundle += [
            "val %s_arg = Output(UInt(%d.W))" % (base, width),
            "val %s_out = Input(Bool()) // ack, latched into a register nothing reads"
            % base,
        ]
        conn.append("impl_io.%s_out := false.B" % base)
        if addr(base):
            regs.append(
                "%s -> Seq(RegField.r(%d, impl_io.%s_arg)),"
                % (addr(base), width, base)
            )

    # -- status ------------------------------------------------------------
    if addr(STATUS_KEY):
        regs.append("%s -> Seq(RegField.r(1, busy))," % addr(STATUS_KEY))

    regs = sorted(regs, key=lambda line: int(line.split(" ")[0], 0))

    with open(TEMPLATE) as f:
        template = f.read()
    out = (
        substitute(template, "ModuleIODefsBundle", bundle)
    )
    out = substitute(out, "ModuleIODefsChisel", chisel)
    out = substitute(out, "ModuleIOConnections", conn)
    out = substitute(out, "ModuleRegFields", [l.rstrip(",") if i == len(regs) - 1 else l for i, l in enumerate(regs)])
    out = (
        out.replace("{{ModuleBusySignal}}", "busy")
        .replace("{{ModuleName}}", module_name)
        .replace("{{moduleName}}", module_name[0].lower() + module_name[1:])
        .replace("{{modulename}}", module_name.lower())
    )

    os.makedirs(SCALA_DIR, exist_ok=True)
    scala_path = os.path.join(SCALA_DIR, base_name + "Wrapper.scala")
    with open(scala_path, "w") as f:
        f.write(out)
    print("  wrote %s" % scala_path)
    print(
        "  registers: "
        + ", ".join(
            "%s@%s" % (k, regmap[k]["address"])
            for k in [STATUS_KEY, "in_cmd"]
            + [b for b, _ in inputs]
            + [b for b, _ in outputs]
        )
    )


def main(argv):
    targets = argv[1:] or sorted(
        os.path.join(VSRC_DIR, f)
        for f in os.listdir(VSRC_DIR)
        if f.endswith(".v")
    )
    failures = 0
    for path in targets:
        print("==> %s" % path)
        try:
            generate(path)
        except UnsupportedModule as e:
            print("  skipped: %s" % e)
            failures += 1
    return 1 if failures and len(targets) == failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
