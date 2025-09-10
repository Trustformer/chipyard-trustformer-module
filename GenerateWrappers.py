import os
import re
import json
import sys

interactive = "ask"
if len(sys.argv) > 1 and sys.argv[1] == "--not-interactive":
    interactive = "no"

def is_interactive_mode():
    global interactive
    if interactive == "ask":
        print(f"Warning: Register information is missing.")
        user_input = input(f"Would you like to generate register maps interactively? (y/N): ")
        if user_input.lower() == 'y':
            interactive = "yes"
        else:
            interactive = "no"
    return interactive.lower() == "yes"

vsrc_dir = os.path.join("src", "main", "resources", "vsrc")
scala_dir = os.path.join("src", "main", "scala")

def gen_for_file(file_path):
    with open(file_path, "r") as vf: 
        content = vf.read()
        # Remove single-line comments
        content = ' '.join([line.split('//')[0] for line in content.splitlines()])
        # Remove multi-line comments
        content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
        
        # Extract module name and port list
        match = re.search(r'\bmodule\s+(\w+)\s*\((.*?)\)\s*;', content)
        if match:
            module_name = match.group(1)
            port_list = match.group(2).strip()
        else:
            print(f"Error: No module definition found in {file_path}.\n")
            return
        
        base_name = os.path.splitext(file)[0]
        
        # Parse the port list
        ports = [p.strip() for p in port_list.split(',')]
        ports = [p for p in ports if p != "input wire[0:0] CLK" and p != "input wire[0:0] RST_N"]

        # Create tuples for ports by matching name prefixes and suffixes _out and _arg
        port_dict = {}
        for port in ports:
            if port.startswith("input wire") or port.startswith("output wire"):
                port_name = port.split()[2]
                width = 1
                width_match = re.search(r'\[(\d+):0\]', port)
                if width_match:
                    width = int(width_match.group(1)) + 1
                prefix = None
                if port_name.endswith("_out"):
                    prefix = port_name[:-4]
                    port_dict.setdefault(prefix, {})["out"] = port_name
                    port_dict[prefix]["out_width"] = width
                elif port_name.endswith("_arg"):
                    prefix = port_name[:-4]
                    port_dict.setdefault(prefix, {})["arg"] = port_name
                    port_dict[prefix]["arg_width"] = width

        print(f"Port Tuples: {port_dict}")

        # Read the register map
        regmap_file_path = os.path.join("src", "main", "resources", "regmap", base_name + ".json")
        regmap = {}
        try:
            with open(regmap_file_path, "r") as regmap_file:
                regmap = json.load(regmap_file)
        except:
            if not is_interactive_mode():
                print(f"Warning: Register map for {base_name} not found. Skipping file.")
                return
            offset = input(f"Enter offset for the meta 'status' register in {base_name} (e.g. 0x00) or leave empty if port should not be mapped: ")
            if offset.strip():
                regmap["<status>"] = {"address": offset}
        
        # Check if all ports are in the register map
        for port_prefix, port_types in port_dict.items():
            if port_prefix not in regmap:
                if not is_interactive_mode():
                    print(f"Warning: Port {port_prefix} not found in register map for {base_name}. Skipping file.")
                    return
                offset = input(f"Enter offset for port {port_prefix} in {base_name} (e.g. 0x00) or leave empty if port should not be mapped: ")
                regmap[port_prefix] = {"address": offset}
        
        # Write the register map back to the file
        with open(regmap_file_path, "w") as regmap_file:
            json.dump(regmap, regmap_file, indent=4)

        print(f"Register Map: {regmap}")

        # save wire size information in port_dict
        # generate the lines to fill the template
        # Note: Output is never with valid flag, input is always with valid flag
        #       Throw error if a port width is not 1, 32, or 33 (for now)
        #       Then the arg & w=1 is the ready flag for an input (belongs to matching out)
        #            the out & w=33 is the valid flag + data for an input
        #            the arg & w=32 is the data for an output
        #            the out & w=1 is the ready flag for an output (belongs to matching arg), i.e. when the reg was read
        #       Throw errors on all other combinations than the above for now
        # the busy flag is the not of all ready flags (arg & w=1) anded 
        #     maybe the inverse of the busy flag should be asked whether to expose it as a register eg. at 0x00
        io_defs_bundle = ""
        io_defs_chisel = ""
        io_connections = ""
        regs_def = ""
        busy_signal = "true.B"

        for port_prefix, port_types in port_dict.items():
            if port_types.get("arg_width") not in [1, 32, 33]:
                print(f"Error: Unsupported arg width for port {port_prefix} in {base_name}.")
                continue
            if port_types.get("out_width") not in [1, 32, 33]:
                print(f"Error: Unsupported out width for port {port_prefix} in {base_name}.")
                continue

            if not port_types.get("arg") or not port_types.get("out"):
                print(f"Error: Port {port_prefix} in {base_name} must have both arg and out defined.")
                return

            if port_types.get("arg_width") == 1:
                io_defs_bundle += f"val {port_types['arg']} = Output(Bool())\n"
                io_defs_chisel += f"val {port_types['arg']} = Wire(Bool())\n"
                io_connections += f"{port_types['arg']} := impl_io.{port_types['arg']}\n"
                io_connections += f"{port_types['out']}.ready := impl_io.{port_types['arg']}\n"
                busy_signal += f" && {port_types['arg']}"
            elif port_types.get("arg_width") == 32:
                io_defs_bundle += f"val {port_types['arg']} = Output(UInt(32.W))\n"
                io_defs_chisel += f"val {port_types['arg']} = Wire(new DecoupledIO(UInt(32.W)))\n"
                io_connections += f"{port_types['arg']}.bits := impl_io.{port_types['arg']}\n"
                io_connections += f"{port_types['arg']}.valid := true.B\n"
                io_connections += f"impl_io.{port_types['out']} := {port_types['arg']}.ready\n"
                if regmap[port_prefix]['address'].strip():
                    regs_def += f"{regmap[port_prefix]['address']} -> Seq(RegField.r(32, {port_types['arg']})),\n"
            
            if port_types.get("out_width") == 1:
                io_defs_bundle += f"val {port_types['out']} = Input(Bool())\n"
                io_connections += f"impl_io.{port_types['out']} := {port_types['arg']}.ready\n"
            elif port_types.get("out_width") == 33:
                io_defs_bundle += f"val {port_types['out']} = Input(UInt((32 + 1).W)) // [32] is valid bit, [31:0] is command\n"
                io_defs_chisel += f"val {port_types['out']} = Wire(new DecoupledIO(UInt(32.W)))\n"
                io_connections += f"impl_io.{port_types['out']} := Cat({port_types['out']}.valid, {port_types['out']}.bits)\n"
                if regmap[port_prefix]['address'].strip():
                    regs_def += f"{regmap[port_prefix]['address']} -> Seq(RegField.w(32, {port_types['out']})),\n"

        # Special registers
        if "<status>" in regmap and regmap["<status>"]["address"].strip():
            io_defs_chisel += "val status = Wire(Bool())\n"
            io_connections += "status := io.{{moduleName}}_busy\n"
            regs_def += f"{regmap['<status>']['address']} -> Seq(RegField.r(1, status)),\n"

        scala_file_name = base_name + "Wrapper.scala"
        scala_file_path = os.path.join(scala_dir, scala_file_name)
        with open("WrapperTemplate.txt", "r") as template_file:
            with open(scala_file_path, "w") as f:
                wrapper_template = template_file.read()
                
                wrapper_template = wrapper_template.replace("{{ModuleIODefsBundle}}", io_defs_bundle)
                wrapper_template = wrapper_template.replace("{{ModuleIODefsChisel}}", io_defs_chisel)
                wrapper_template = wrapper_template.replace("{{ModuleIOConnections}}", io_connections)
                wrapper_template = wrapper_template.replace("{{ModuleRegFields}}", regs_def.rstrip(",\n"))
                wrapper_template = wrapper_template.replace("{{ModuleBusySignal}}", f"!({busy_signal})")

                wrapper_template = wrapper_template.replace("{{ModuleName}}", module_name)
                wrapper_template = wrapper_template.replace("{{moduleName}}", module_name[0].lower() + module_name[1:])
                wrapper_template = wrapper_template.replace("{{modulename}}", module_name.lower())
                f.write(wrapper_template)


for root, dirs, files in os.walk(vsrc_dir):
    for file in files:
        file_path = os.path.join(root, file)
        
        if file.endswith(".v"):
            gen_for_file(file_path)