#!/usr/bin/env python3
"""Generate a Chisel MMIO wrapper for each Trustformer-generated Verilog module.

For every `src/main/resources/vsrc/*.v` this emits
`src/main/scala/<Module>Wrapper.scala` from `WrapperTemplate.txt`, and writes back
`src/main/resources/regmap/<Module>.json` with the addresses it used.

Port convention
---------------
The module's ports are the Koika *external functions* declared in
`coq/Backend/Lowering.v` (`Sigma` / `ext_fn_specs`).  `cuttlec` names the argument
the module passes out `<fn>_arg` and the value handed back `<fn>_out`, which
hides the direction of the protocol -- so classify by the function *name*, never
by the port width (widths change with the spec; the older width heuristic broke
the moment an action code stopped being 32 bits wide).  `<c>` is the port's
confidentiality class, `pub` or `sec` (`coq/Contract.v`, `port_class`):

    in_cmd           : bits_t 1 ~> maybe (bits_t A)
        in_cmd_arg  (out, 1)    the module's `ready`
        in_cmd_out  (in,  A+1)  {valid, command}; taken on `ready && valid`
    in_param_<c>_<x> : bits_t 1 ~> inputs_t x
        in_param_<c>_<x>_arg (out, 1)   constant 1'b1, carries nothing
        in_param_<c>_<x>_out (in,  W)   the argument, sampled in the accept cycle
    out_param_<c>_<y> : outputs_t y ~> bits_t 1
        out_param_<c>_<y>_arg (out, W)  the result
        out_param_<c>_<y>_out (in,  1)  an ack latched into a register nothing reads
    ip_req_sec_<p>   : bits_t (1 + R) ~> bits_t 1
        ip_req_sec_<p>_arg (out, 1+R)   {strobe, payload}
        ip_req_sec_<p>_out (in,  1)     an ack latched into a register nothing reads
    ip_resp_sec_<p>  : bits_t 1 ~> inputs_t r   (r: the input carrying p's answer)
        ip_resp_sec_<p>_arg (out, 1)    constant 1'b1, carries nothing
        ip_resp_sec_<p>_out (in,  W)    the answer, read ip_lat cycles after the strobe

Only `pub` ports are memory-mapped.  Every other port -- secret inputs and
outputs, IP links -- goes to `<Module>Platform`, a hand-written module in
`src/main/scala/platform/` that this script never writes; it also sees the
public outputs, read-only.

Two facts drive the generated logic:
  * arguments are latched combinationally in the cycle the command is accepted,
    so they need *holding registers* -- the MMIO sequence is "write args, then
    write the command register" (paper, 05_design/01_functional_spec.tex).
  * an action that completes in its accept cycle never drops `ready`, so a
    `valid` held high for N cycles executes it N times.  The wrapper drops
    `valid` after a single `ready && valid`, and never waits for `ready` to fall.
"""

import json
import os
import re
import sys

VSRC_DIR = os.path.join("src", "main", "resources", "vsrc")
REGMAP_DIR = os.path.join("src", "main", "resources", "regmap")
SCALA_DIR = os.path.join("src", "main", "scala")
PLATFORM_DIR = os.path.join(SCALA_DIR, "platform")
TEMPLATE = "WrapperTemplate.txt"

# (prefix, data port, partner port, destination).  The partner is always 1 bit
# and carries nothing; `_out` ports are module inputs, `_arg` ports outputs.
FAMILIES = [
    ("in_param_pub_", "_out", "_arg", "bus"),
    ("out_param_pub_", "_arg", "_out", "bus"),
    ("in_param_sec_", "_out", "_arg", "platform"),
    ("out_param_sec_", "_arg", "_out", "platform"),
    ("ip_req_sec_", "_arg", "_out", "platform"),
    ("ip_resp_sec_", "_out", "_arg", "platform"),
]
DIRECTION = {"_out": "input", "_arg": "output"}

STATUS_KEY = "<status>"

# A RegField may not span a bus word: RegMapper requires
# `off + field.width <= beatBytes * 8` (rocket-chip RegMapper.scala L114), and the
# word size is the bus' beatBytes, not something we control. Spec variables are
# arbitrary-width, so every value is split into 32-bit words at consecutive
# 4-byte offsets -- 32 bits at a 4-byte-aligned offset always fits a word for any
# beatBytes >= 4, and it matches the reg_read32/reg_write32 helpers on the
# software side. Word k holds bits [32k+31 : 32k]; the last word may be narrower.
CHUNK = 32
WINDOW_BYTES = 4096  # must match the AddressSet size in WrapperTemplate.txt


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
    """Split the port map by external-function family and confidentiality class.

    -> (cmd_width, inputs, outputs, platform): `inputs` and `outputs` are the
    bus-mapped `pub` ports as [(base, width)]; `platform` is [(base, data, width)]
    for every port that stays off the bus, `data` being its data-port suffix.
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
    if cmd_width < 1:
        raise UnsupportedModule("unsupported command width %d" % cmd_width)

    inputs, outputs, platform = [], [], []
    seen = {"CLK", "RST_N", "in_cmd_arg", "in_cmd_out"}
    for prefix, data, partner, dest in FAMILIES:
        for name in sorted(ports):
            if not (name.startswith(prefix) and name.endswith(data)):
                continue
            base = name[: -len(data)]
            width = expect(name, DIRECTION[data])
            expect(base + partner, DIRECTION[partner], 1)
            seen |= {name, base + partner}
            if dest == "platform":
                platform.append((base, data, width))
            elif data == "_out":
                inputs.append((base, width))
            else:
                outputs.append((base, width))

    for name in sorted(set(ports) - seen):
        if re.match(r"(in|out)_param_(?!pub_|sec_)", name):
            raise UnsupportedModule(
                "port %s has no pub/sec class -- the Verilog predates the port "
                "classes of coq/Contract.v; regenerate it" % name
            )
        raise UnsupportedModule("unexpected port %s" % name)

    return cmd_width, inputs, outputs, platform


def check_platform(module_name, platform):
    """The hand-written platform module must exist when anything stays off the bus."""
    if not platform:
        return
    path = os.path.join(PLATFORM_DIR, module_name + "Platform.scala")
    cls = re.compile(r"\bclass\s+%sPlatform\b" % re.escape(module_name))
    if os.path.exists(path):
        with open(path) as f:
            if cls.search(f.read()):
                return
    raise UnsupportedModule(
        "%d port(s) must stay off the bus (%s); write %s declaring `class "
        "%sPlatform(implicit p: Parameters) extends Module` with "
        "`val io = IO(new %sPlatformIO)` to drive them"
        % (
            len(platform),
            ", ".join(base for base, _, _ in platform),
            path,
            module_name,
            module_name,
        )
    )


def chunk_widths(width):
    """Split `width` into consecutive 32-bit words; the last may be narrower."""
    n = (width + CHUNK - 1) // CHUNK
    return [CHUNK] * (n - 1) + [width - CHUNK * (n - 1)]


def load_or_build_regmap(base_name, slots):
    """Read the checked-in register map, filling in and validating addresses.

    `slots` is [(key, nbytes)] in allocation order; a value that needs several
    32-bit words reserves that many consecutive 4-byte slots from its address.
    """
    path = os.path.join(REGMAP_DIR, base_name + ".json")
    regmap = {}
    if os.path.exists(path):
        with open(path) as f:
            regmap = json.load(f)

    # A renamed port would otherwise miss its pin and be re-addressed silently,
    # and a pinned secret port would look mapped when it is not.
    stale = sorted(set(regmap) - {key for key, _ in slots})
    if stale:
        raise UnsupportedModule(
            "%s pins %s, which match(es) no bus-mapped port; rename or remove "
            "the entries" % (path, ", ".join(stale))
        )

    def pinned(key):
        addr = regmap.get(key, {}).get("address", "").strip()
        return int(addr, 0) if addr else None

    # Reserve what the register map already pins, then fill the gaps.
    occupied = {}
    for key, nbytes in slots:
        addr = pinned(key)
        if addr is None:
            continue
        if addr % 4:
            raise UnsupportedModule(
                "address %s of %s is not 4-byte aligned" % (regmap[key]["address"], key)
            )
        for off in range(addr, addr + nbytes, 4):
            if off in occupied:
                raise UnsupportedModule(
                    "%s overlaps %s at offset 0x%02X" % (key, occupied[off], off)
                )
            occupied[off] = key

    next_addr = 0
    for key, nbytes in slots:
        if pinned(key) is not None:
            continue
        while any(
            (next_addr + off) in occupied for off in range(0, nbytes, 4)
        ):
            next_addr += 4
        regmap[key] = {"address": "0x%02X" % next_addr}
        for off in range(next_addr, next_addr + nbytes, 4):
            occupied[off] = key

    end = max(occupied) + 4 if occupied else 0
    if end > WINDOW_BYTES:
        raise UnsupportedModule(
            "register map needs %d bytes, more than the %d-byte window in "
            "WrapperTemplate.txt" % (end, WINDOW_BYTES)
        )

    os.makedirs(REGMAP_DIR, exist_ok=True)
    with open(path, "w") as f:
        json.dump(regmap, f, indent=4)
        f.write("\n")
    return regmap


def generate(file_path):
    module_name, ports = parse_ports(file_path)
    cmd_width, inputs, outputs, platform = classify(ports)
    base_name = os.path.splitext(os.path.basename(file_path))[0]
    if base_name != module_name:
        raise UnsupportedModule(
            "file %s declares module %s; they must match so that the BlackBox "
            "resource resolves" % (os.path.basename(file_path), module_name)
        )
    check_platform(module_name, platform)

    cmd_chunks = chunk_widths(cmd_width)
    in_chunks = {base: chunk_widths(width) for base, width in inputs}
    out_chunks = {base: chunk_widths(width) for base, width in outputs}

    slots = (
        [(STATUS_KEY, 4), ("in_cmd", 4 * len(cmd_chunks))]
        + [(b, 4 * len(in_chunks[b])) for b, _ in inputs]
        + [(b, 4 * len(out_chunks[b])) for b, _ in outputs]
    )
    regmap = load_or_build_regmap(base_name, slots)

    bundle, chisel, conn, regs = [], [], [], []

    def addr(key):
        a = regmap.get(key, {}).get("address", "").strip()
        return int(a, 0) if a else None

    def emit_reg(base_addr, word, line, note=""):
        regs.append("0x%02X -> Seq(%s),%s" % (base_addr + 4 * word, line, note))

    # -- command channel ---------------------------------------------------
    bundle += [
        "val in_cmd_arg = Output(Bool()) // the module's `ready`",
        "val in_cmd_out = Input(UInt(%d.W)) // [%d] is valid, [%d:0] the command"
        % (cmd_width + 1, cmd_width, cmd_width - 1),
    ]
    trigger_width = cmd_chunks[-1]
    chisel += [
        "val cmd = Wire(new DecoupledIO(UInt(%d.W)))" % trigger_width,
        "val cmd_reg = RegInit(0.U(%d.W))" % cmd_width,
        "val cmd_pending = RegInit(false.B)",
        "val busy = Wire(Bool())",
    ]
    # Words below the last one are plain holding registers; the last one is the
    # write that actually launches the command.
    for word in range(len(cmd_chunks) - 1):
        chisel.append(
            "val cmd_low_%d = RegInit(0.U(%d.W))" % (word, cmd_chunks[word])
        )
    if len(cmd_chunks) == 1:
        assemble = "cmd.bits"
    else:
        parts = ["cmd.bits"] + [
            "cmd_low_%d" % w for w in reversed(range(len(cmd_chunks) - 1))
        ]
        assemble = "Cat(%s)" % ", ".join(parts)
    conn += [
        "// Accept one command at a time from the bus, then present it to the",
        "// module until it takes it -- and drop `valid` immediately afterwards:",
        "// `ready` is constant 1 for single-cycle modules, so a held `valid`",
        "// would re-execute the action every cycle.",
        "cmd.ready := !cmd_pending",
        "when (cmd.fire) {",
        "  cmd_reg := %s" % assemble,
        "  cmd_pending := true.B",
        "} .elsewhen (cmd_pending && impl_io.in_cmd_arg) {",
        "  cmd_pending := false.B",
        "}",
        "impl_io.in_cmd_out := Cat(cmd_pending, cmd_reg)",
        "busy := cmd_pending || !impl_io.in_cmd_arg",
    ]
    if addr("in_cmd") is not None:
        for word in range(len(cmd_chunks) - 1):
            emit_reg(
                addr("in_cmd"), word, "RegField(%d, cmd_low_%d)" % (cmd_chunks[word], word)
            )
        emit_reg(
            addr("in_cmd"),
            len(cmd_chunks) - 1,
            "RegField.w(%d, cmd)" % trigger_width,
            "" if len(cmd_chunks) == 1 else " // writing this word launches the command",
        )

    # -- input arguments ---------------------------------------------------
    for base, width in inputs:
        widths = in_chunks[base]
        bundle += [
            "val %s_arg = Output(Bool()) // hardwired 1'b1, unused" % base,
            "val %s_out = Input(UInt(%d.W))" % (base, width),
        ]
        if len(widths) == 1:
            chisel.append("val %s_reg = RegInit(0.U(%d.W))" % (base, width))
            value = "%s_reg" % base
        else:
            for word, w in enumerate(widths):
                chisel.append("val %s_reg_%d = RegInit(0.U(%d.W))" % (base, word, w))
            value = "Cat(%s)" % ", ".join(
                "%s_reg_%d" % (base, w) for w in reversed(range(len(widths)))
            )
        conn += [
            "// held, not pulsed: the module samples this in the accept cycle",
            "impl_io.%s_out := %s" % (base, value),
        ]
        if addr(base) is not None:
            for word, w in enumerate(widths):
                name = "%s_reg" % base if len(widths) == 1 else "%s_reg_%d" % (base, word)
                emit_reg(addr(base), word, "RegField(%d, %s)" % (w, name))

    # -- outputs -----------------------------------------------------------
    for base, width in outputs:
        widths = out_chunks[base]
        bundle += [
            "val %s_arg = Output(UInt(%d.W))" % (base, width),
            "val %s_out = Input(Bool()) // ack, latched into a register nothing reads"
            % base,
        ]
        conn.append("impl_io.%s_out := false.B" % base)
        if addr(base) is not None:
            for word, w in enumerate(widths):
                if len(widths) == 1:
                    src = "impl_io.%s_arg" % base
                else:
                    src = "impl_io.%s_arg(%d, %d)" % (
                        base,
                        CHUNK * word + w - 1,
                        CHUNK * word,
                    )
                emit_reg(addr(base), word, "RegField.r(%d, %s)" % (w, src))

    # -- off the bus -------------------------------------------------------
    platform_io, platform_conn = [], []
    if platform:
        notes = {
            "in_param_sec_": "secret input, sampled in the accept cycle",
            "out_param_sec_": "secret output",
            "ip_req_sec_": "IP request: {strobe, payload}",
            "ip_resp_sec_": "IP answer, read ip_lat cycles after the strobe",
        }
        platform_conn.append("val platform = Module(new %sPlatform()(p))" % module_name)
        for base, data, width in platform:
            note = next(n for pre, n in notes.items() if base.startswith(pre))
            if data == "_out":
                bundle += [
                    "val %s_arg = Output(Bool()) // hardwired 1'b1, unused" % base,
                    "val %s_out = Input(UInt(%d.W))" % (base, width),
                ]
                platform_io.append("val %s = Output(UInt(%d.W)) // %s" % (base, width, note))
                platform_conn.append("impl_io.%s_out := platform.io.%s" % (base, base))
            else:
                bundle += [
                    "val %s_arg = Output(UInt(%d.W))" % (base, width),
                    "val %s_out = Input(Bool()) // ack, latched into a register nothing reads"
                    % base,
                ]
                platform_io.append("val %s = Input(UInt(%d.W)) // %s" % (base, width, note))
                platform_conn += [
                    "platform.io.%s := impl_io.%s_arg" % (base, base),
                    "impl_io.%s_out := false.B" % base,
                ]
        for base, width in outputs:
            platform_io.append("val %s = Input(UInt(%d.W)) // public output, read-only" % (base, width))
            platform_conn.append("platform.io.%s := impl_io.%s_arg" % (base, base))
        platform_io = (
            [
                "",
                "// The ports that stay off the bus, seen from %sPlatform, which drives"
                % module_name,
                "// the Outputs. It is hand-written: src/main/scala/platform/.",
                "class %sPlatformIO extends Bundle {" % module_name,
            ]
            + ["  " + line for line in platform_io]
            + ["}"]
        )

    # -- status ------------------------------------------------------------
    if addr(STATUS_KEY) is not None:
        emit_reg(addr(STATUS_KEY), 0, "RegField.r(1, busy)")

    regs = sorted(regs, key=lambda line: int(line.split(" ")[0], 0))
    # the last entry must not carry a trailing comma into node.regmap(...)
    regs[-1] = regs[-1].replace("),", ")", 1) if regs[-1].endswith(",") else regs[-1].replace("), //", ") //", 1)

    with open(TEMPLATE) as f:
        template = f.read()
    out = substitute(template, "ModuleIODefsBundle", bundle)
    out = substitute(out, "ModuleIODefsChisel", chisel)
    out = substitute(out, "ModuleIOConnections", conn)
    out = substitute(out, "ModuleRegFields", regs)
    out = substitute(out, "PlatformIOBundle", platform_io)
    out = substitute(out, "ModulePlatform", platform_conn)
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
    for key, nbytes in slots:
        a = regmap.get(key, {}).get("address", "").strip()
        words = nbytes // 4
        print(
            "    %-28s %s%s"
            % (key, a, "" if words == 1 else " .. 0x%02X (%d words)" % (int(a, 0) + nbytes - 4, words))
        )
    for base, _, width in platform:
        print("    %-28s off the bus, %d bits" % (base, width))


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
