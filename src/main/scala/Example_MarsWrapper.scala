package trustformer

import sys.process._

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam, BaseModule}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS}
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy.AddressSet
import org.chipsalliance.diplomacy._
import freechips.rocketchip.regmapper.{HasRegMap, RegField}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.resources._
import freechips.rocketchip.tilelink.MemoryOpCategories.wr

// DOC include start: Example_Mars params
case class Example_MarsParams(
  address: BigInt = 0x4000,
)
// DOC include end: Example_Mars params

// DOC include start: Example_Mars key
case object Example_MarsKey extends Field[Option[Example_MarsParams]](None)
// DOC include end: Example_Mars key

class Example_MarsIO() extends Bundle {
  val CLK = Input(Clock())
  val RST_N = Input(Bool())
  val in_cmd_arg = Output(Bool()) // the module's `ready`
  val in_cmd_out = Input(UInt(17.W)) // [16] is valid, [15:0] the command
  val in_param_pub_in_ctx_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_ctx_out = Input(UInt(256.W))
  val in_param_pub_in_ctxlen_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_ctxlen_out = Input(UInt(16.W))
  val in_param_pub_in_dig_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_dig_out = Input(UInt(256.W))
  val in_param_pub_in_idx_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_idx_out = Input(UInt(16.W))
  val in_param_pub_in_nlen_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_nlen_out = Input(UInt(16.W))
  val in_param_pub_in_nonce_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_nonce_out = Input(UInt(256.W))
  val in_param_pub_in_pt_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_pt_out = Input(UInt(16.W))
  val in_param_pub_in_regsel_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_pub_in_regsel_out = Input(UInt(32.W))
  val out_param_pub_out_cap_arg = Output(UInt(16.W))
  val out_param_pub_out_cap_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_pub_out_dout_arg = Output(UInt(256.W))
  val out_param_pub_out_dout_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_pub_out_failure_arg = Output(UInt(1.W))
  val out_param_pub_out_failure_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_pub_out_pcr0_arg = Output(UInt(256.W))
  val out_param_pub_out_pcr0_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_pub_out_pcr1_arg = Output(UInt(256.W))
  val out_param_pub_out_pcr1_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_pub_out_rc_arg = Output(UInt(16.W))
  val out_param_pub_out_rc_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_pub_out_snap_arg = Output(UInt(256.W))
  val out_param_pub_out_snap_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_pub_out_st_arg = Output(UInt(1.W))
  val out_param_pub_out_st_out = Input(Bool()) // ack, latched into a register nothing reads
  val in_param_sec_in_init_req_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_sec_in_init_req_out = Input(UInt(1.W))
  val in_param_sec_in_ps_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_sec_in_ps_out = Input(UInt(256.W))
  val ip_req_sec_ip_hmac_arg = Output(UInt(785.W))
  val ip_req_sec_ip_hmac_out = Input(Bool()) // ack, latched into a register nothing reads
  val ip_req_sec_ip_sha_arg = Output(UInt(1041.W))
  val ip_req_sec_ip_sha_out = Input(Bool()) // ack, latched into a register nothing reads
  val ip_resp_sec_ip_hmac_arg = Output(Bool()) // hardwired 1'b1, unused
  val ip_resp_sec_ip_hmac_out = Input(UInt(256.W))
  val ip_resp_sec_ip_sha_arg = Output(Bool()) // hardwired 1'b1, unused
  val ip_resp_sec_ip_sha_out = Input(UInt(256.W))
}

class Example_MarsTopIO extends Bundle {
  val example_Mars_busy = Output(Bool())
}

trait HasExample_MarsTopIO {
  def io: Example_MarsTopIO
}

// The ports that stay off the bus, seen from Example_MarsPlatform, which drives
// the Outputs. It is hand-written: src/main/scala/platform/.
class Example_MarsPlatformIO extends Bundle {
  val in_param_sec_in_init_req = Output(UInt(1.W)) // secret input, sampled in the accept cycle
  val in_param_sec_in_ps = Output(UInt(256.W)) // secret input, sampled in the accept cycle
  val ip_req_sec_ip_hmac = Input(UInt(785.W)) // IP request: {strobe, payload}
  val ip_req_sec_ip_sha = Input(UInt(1041.W)) // IP request: {strobe, payload}
  val ip_resp_sec_ip_hmac = Output(UInt(256.W)) // IP answer, read ip_lat cycles after the strobe
  val ip_resp_sec_ip_sha = Output(UInt(256.W)) // IP answer, read ip_lat cycles after the strobe
  val out_param_pub_out_cap = Input(UInt(16.W)) // public output, read-only
  val out_param_pub_out_dout = Input(UInt(256.W)) // public output, read-only
  val out_param_pub_out_failure = Input(UInt(1.W)) // public output, read-only
  val out_param_pub_out_pcr0 = Input(UInt(256.W)) // public output, read-only
  val out_param_pub_out_pcr1 = Input(UInt(256.W)) // public output, read-only
  val out_param_pub_out_rc = Input(UInt(16.W)) // public output, read-only
  val out_param_pub_out_snap = Input(UInt(256.W)) // public output, read-only
  val out_param_pub_out_st = Input(UInt(1.W)) // public output, read-only
}

// DOC include start: Example_Mars blackbox
class Example_Mars() extends BlackBox(Map()) with HasBlackBoxResource {
  val io = IO(new Example_MarsIO())
  addResource("/vsrc/Example_Mars.v")
}
// DOC include end: Example_Mars blackbox

// DOC include start: Example_Mars router
class Example_MarsTL(params: Example_MarsParams, beatBytes: Int)(implicit p: Parameters) extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("example_mars", Seq("ucbbar,example_mars"))
  val node = TLRegisterNode(Seq(AddressSet(params.address, 4096-1)), device, "reg/control", beatBytes=beatBytes)

  override lazy val module = new Example_MarsImpl
  class Example_MarsImpl extends Impl with HasExample_MarsTopIO {
    val io = IO(new Example_MarsTopIO)
    withClockAndReset(clock, reset) {
      val cmd = Wire(new DecoupledIO(UInt(16.W)))
      val cmd_reg = RegInit(0.U(16.W))
      val cmd_pending = RegInit(false.B)
      val busy = Wire(Bool())
      val in_param_pub_in_ctx_reg_0 = RegInit(0.U(32.W))
      val in_param_pub_in_ctx_reg_1 = RegInit(0.U(32.W))
      val in_param_pub_in_ctx_reg_2 = RegInit(0.U(32.W))
      val in_param_pub_in_ctx_reg_3 = RegInit(0.U(32.W))
      val in_param_pub_in_ctx_reg_4 = RegInit(0.U(32.W))
      val in_param_pub_in_ctx_reg_5 = RegInit(0.U(32.W))
      val in_param_pub_in_ctx_reg_6 = RegInit(0.U(32.W))
      val in_param_pub_in_ctx_reg_7 = RegInit(0.U(32.W))
      val in_param_pub_in_ctxlen_reg = RegInit(0.U(16.W))
      val in_param_pub_in_dig_reg_0 = RegInit(0.U(32.W))
      val in_param_pub_in_dig_reg_1 = RegInit(0.U(32.W))
      val in_param_pub_in_dig_reg_2 = RegInit(0.U(32.W))
      val in_param_pub_in_dig_reg_3 = RegInit(0.U(32.W))
      val in_param_pub_in_dig_reg_4 = RegInit(0.U(32.W))
      val in_param_pub_in_dig_reg_5 = RegInit(0.U(32.W))
      val in_param_pub_in_dig_reg_6 = RegInit(0.U(32.W))
      val in_param_pub_in_dig_reg_7 = RegInit(0.U(32.W))
      val in_param_pub_in_idx_reg = RegInit(0.U(16.W))
      val in_param_pub_in_nlen_reg = RegInit(0.U(16.W))
      val in_param_pub_in_nonce_reg_0 = RegInit(0.U(32.W))
      val in_param_pub_in_nonce_reg_1 = RegInit(0.U(32.W))
      val in_param_pub_in_nonce_reg_2 = RegInit(0.U(32.W))
      val in_param_pub_in_nonce_reg_3 = RegInit(0.U(32.W))
      val in_param_pub_in_nonce_reg_4 = RegInit(0.U(32.W))
      val in_param_pub_in_nonce_reg_5 = RegInit(0.U(32.W))
      val in_param_pub_in_nonce_reg_6 = RegInit(0.U(32.W))
      val in_param_pub_in_nonce_reg_7 = RegInit(0.U(32.W))
      val in_param_pub_in_pt_reg = RegInit(0.U(16.W))
      val in_param_pub_in_regsel_reg = RegInit(0.U(32.W))

      val impl_io = {
        val impl = Module(new Example_Mars())
        impl.io
      } 
      
      impl_io.CLK := clock
      impl_io.RST_N := !reset.asBool

      // Accept one command at a time from the bus, then present it to the
      // module until it takes it -- and drop `valid` immediately afterwards:
      // `ready` is constant 1 for single-cycle modules, so a held `valid`
      // would re-execute the action every cycle.
      cmd.ready := !cmd_pending
      when (cmd.fire) {
        cmd_reg := cmd.bits
        cmd_pending := true.B
      } .elsewhen (cmd_pending && impl_io.in_cmd_arg) {
        cmd_pending := false.B
      }
      impl_io.in_cmd_out := Cat(cmd_pending, cmd_reg)
      busy := cmd_pending || !impl_io.in_cmd_arg
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_ctx_out := Cat(in_param_pub_in_ctx_reg_7, in_param_pub_in_ctx_reg_6, in_param_pub_in_ctx_reg_5, in_param_pub_in_ctx_reg_4, in_param_pub_in_ctx_reg_3, in_param_pub_in_ctx_reg_2, in_param_pub_in_ctx_reg_1, in_param_pub_in_ctx_reg_0)
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_ctxlen_out := in_param_pub_in_ctxlen_reg
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_dig_out := Cat(in_param_pub_in_dig_reg_7, in_param_pub_in_dig_reg_6, in_param_pub_in_dig_reg_5, in_param_pub_in_dig_reg_4, in_param_pub_in_dig_reg_3, in_param_pub_in_dig_reg_2, in_param_pub_in_dig_reg_1, in_param_pub_in_dig_reg_0)
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_idx_out := in_param_pub_in_idx_reg
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_nlen_out := in_param_pub_in_nlen_reg
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_nonce_out := Cat(in_param_pub_in_nonce_reg_7, in_param_pub_in_nonce_reg_6, in_param_pub_in_nonce_reg_5, in_param_pub_in_nonce_reg_4, in_param_pub_in_nonce_reg_3, in_param_pub_in_nonce_reg_2, in_param_pub_in_nonce_reg_1, in_param_pub_in_nonce_reg_0)
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_pt_out := in_param_pub_in_pt_reg
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_pub_in_regsel_out := in_param_pub_in_regsel_reg
      impl_io.out_param_pub_out_cap_out := false.B
      impl_io.out_param_pub_out_dout_out := false.B
      impl_io.out_param_pub_out_failure_out := false.B
      impl_io.out_param_pub_out_pcr0_out := false.B
      impl_io.out_param_pub_out_pcr1_out := false.B
      impl_io.out_param_pub_out_rc_out := false.B
      impl_io.out_param_pub_out_snap_out := false.B
      impl_io.out_param_pub_out_st_out := false.B
      val platform = Module(new Example_MarsPlatform()(p))
      impl_io.in_param_sec_in_init_req_out := platform.io.in_param_sec_in_init_req
      impl_io.in_param_sec_in_ps_out := platform.io.in_param_sec_in_ps
      platform.io.ip_req_sec_ip_hmac := impl_io.ip_req_sec_ip_hmac_arg
      impl_io.ip_req_sec_ip_hmac_out := false.B
      platform.io.ip_req_sec_ip_sha := impl_io.ip_req_sec_ip_sha_arg
      impl_io.ip_req_sec_ip_sha_out := false.B
      impl_io.ip_resp_sec_ip_hmac_out := platform.io.ip_resp_sec_ip_hmac
      impl_io.ip_resp_sec_ip_sha_out := platform.io.ip_resp_sec_ip_sha
      platform.io.out_param_pub_out_cap := impl_io.out_param_pub_out_cap_arg
      platform.io.out_param_pub_out_dout := impl_io.out_param_pub_out_dout_arg
      platform.io.out_param_pub_out_failure := impl_io.out_param_pub_out_failure_arg
      platform.io.out_param_pub_out_pcr0 := impl_io.out_param_pub_out_pcr0_arg
      platform.io.out_param_pub_out_pcr1 := impl_io.out_param_pub_out_pcr1_arg
      platform.io.out_param_pub_out_rc := impl_io.out_param_pub_out_rc_arg
      platform.io.out_param_pub_out_snap := impl_io.out_param_pub_out_snap_arg
      platform.io.out_param_pub_out_st := impl_io.out_param_pub_out_st_arg
      io.example_Mars_busy := busy
// DOC include start: Example_Mars instance regmap
      node.regmap(
        0x00 -> Seq(RegField.r(1, busy)),
        0x04 -> Seq(RegField.w(16, cmd)),
        0x08 -> Seq(RegField(32, in_param_pub_in_ctx_reg_0)),
        0x0C -> Seq(RegField(32, in_param_pub_in_ctx_reg_1)),
        0x10 -> Seq(RegField(32, in_param_pub_in_ctx_reg_2)),
        0x14 -> Seq(RegField(32, in_param_pub_in_ctx_reg_3)),
        0x18 -> Seq(RegField(32, in_param_pub_in_ctx_reg_4)),
        0x1C -> Seq(RegField(32, in_param_pub_in_ctx_reg_5)),
        0x20 -> Seq(RegField(32, in_param_pub_in_ctx_reg_6)),
        0x24 -> Seq(RegField(32, in_param_pub_in_ctx_reg_7)),
        0x28 -> Seq(RegField(16, in_param_pub_in_ctxlen_reg)),
        0x2C -> Seq(RegField(32, in_param_pub_in_dig_reg_0)),
        0x30 -> Seq(RegField(32, in_param_pub_in_dig_reg_1)),
        0x34 -> Seq(RegField(32, in_param_pub_in_dig_reg_2)),
        0x38 -> Seq(RegField(32, in_param_pub_in_dig_reg_3)),
        0x3C -> Seq(RegField(32, in_param_pub_in_dig_reg_4)),
        0x40 -> Seq(RegField(32, in_param_pub_in_dig_reg_5)),
        0x44 -> Seq(RegField(32, in_param_pub_in_dig_reg_6)),
        0x48 -> Seq(RegField(32, in_param_pub_in_dig_reg_7)),
        0x4C -> Seq(RegField(16, in_param_pub_in_idx_reg)),
        0x50 -> Seq(RegField(16, in_param_pub_in_nlen_reg)),
        0x54 -> Seq(RegField(32, in_param_pub_in_nonce_reg_0)),
        0x58 -> Seq(RegField(32, in_param_pub_in_nonce_reg_1)),
        0x5C -> Seq(RegField(32, in_param_pub_in_nonce_reg_2)),
        0x60 -> Seq(RegField(32, in_param_pub_in_nonce_reg_3)),
        0x64 -> Seq(RegField(32, in_param_pub_in_nonce_reg_4)),
        0x68 -> Seq(RegField(32, in_param_pub_in_nonce_reg_5)),
        0x6C -> Seq(RegField(32, in_param_pub_in_nonce_reg_6)),
        0x70 -> Seq(RegField(32, in_param_pub_in_nonce_reg_7)),
        0x74 -> Seq(RegField(16, in_param_pub_in_pt_reg)),
        0x78 -> Seq(RegField(32, in_param_pub_in_regsel_reg)),
        0x7C -> Seq(RegField.r(16, impl_io.out_param_pub_out_cap_arg)),
        0x80 -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(31, 0))),
        0x84 -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(63, 32))),
        0x88 -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(95, 64))),
        0x8C -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(127, 96))),
        0x90 -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(159, 128))),
        0x94 -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(191, 160))),
        0x98 -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(223, 192))),
        0x9C -> Seq(RegField.r(32, impl_io.out_param_pub_out_dout_arg(255, 224))),
        0xA0 -> Seq(RegField.r(1, impl_io.out_param_pub_out_failure_arg)),
        0xA4 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(31, 0))),
        0xA8 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(63, 32))),
        0xAC -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(95, 64))),
        0xB0 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(127, 96))),
        0xB4 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(159, 128))),
        0xB8 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(191, 160))),
        0xBC -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(223, 192))),
        0xC0 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr0_arg(255, 224))),
        0xC4 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(31, 0))),
        0xC8 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(63, 32))),
        0xCC -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(95, 64))),
        0xD0 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(127, 96))),
        0xD4 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(159, 128))),
        0xD8 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(191, 160))),
        0xDC -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(223, 192))),
        0xE0 -> Seq(RegField.r(32, impl_io.out_param_pub_out_pcr1_arg(255, 224))),
        0xE4 -> Seq(RegField.r(16, impl_io.out_param_pub_out_rc_arg)),
        0xE8 -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(31, 0))),
        0xEC -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(63, 32))),
        0xF0 -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(95, 64))),
        0xF4 -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(127, 96))),
        0xF8 -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(159, 128))),
        0xFC -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(191, 160))),
        0x100 -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(223, 192))),
        0x104 -> Seq(RegField.r(32, impl_io.out_param_pub_out_snap_arg(255, 224))),
        0x108 -> Seq(RegField.r(1, impl_io.out_param_pub_out_st_arg))
          ) 
// DOC include end: Example_Mars instance regmap
    }
  }
}


// DOC include start: Example_Mars lazy trait
trait CanHavePeripheryExample_Mars { this: BaseSubsystem =>
  private val portName = "example_mars"

  private val pbus = locateTLBusWrapper(PBUS)

  // Only build if we are using the TL (nonAXI4) version
  val (example_Mars_busy, example_Mars_clock) = p(Example_MarsKey) match {
    case Some(params) => {

      // Define the clock source node for the Example_Mars module.
      val example_MarsClockNode = pbus.fixedClockNode

      // Define the type of clock crossing required between the pbus and the Example_Mars module.
      val example_MarsCrossing = SynchronousCrossing()


      // Instantiate the Example_Mars module (either TL, AXI4, or HLS variant)
      val example_Mars = {
        val example_Mars = lazymodule.LazyModule(new Example_MarsTL(params, pbus.beatBytes)(p))
        // Connect the Example_Mars's clock input to our determined Example_MarsClockNode.
        example_Mars.clockNode := example_MarsClockNode
        // Couple the Example_Mars to the pbus, inserting the necessary clock crossing logic.
        pbus.coupleTo(portName) {
          // TLInwardClockCrossingHelper handles crossing details for TileLink.
          TLInwardClockCrossingHelper("Example_Mars_crossing", example_Mars, example_Mars.node)(example_MarsCrossing) :=
          TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
        }
        example_Mars
      }
      // Expose the Example_Mars's busy signal.
      val example_Mars_busy = lazymodule.InModuleBody {
        val busy = IO(Output(Bool())).suggestName("example_Mars_busy")
        busy := example_Mars.module.io.example_Mars_busy
        busy
      }
      // Return the busy signal (always needed if Example_Mars exists) and the optional external clock input.
      // The Option[Clock] allows the IOBinder (WithExample_MarsIOPunchthrough) to conditionally
      // create the top-level clock input only when `externallyClocked` is true.
      // The busy signal is Some(busy) because the entire Example_Mars peripheral itself is optional based on Example_MarsKey.
      (Some(example_Mars_busy), None)
    }
    // If Example_MarsKey is None, the Example_Mars peripheral is not instantiated. Return None for both signals.
    case None => (None, None)
  }
}
// DOC include end: Example_Mars lazy trait

// DOC include start: Example_Mars config fragment
class WithExample_Mars(address: BigInt = 0x4000) extends Config((site, here, up) => {
  case Example_MarsKey => {
    Some(Example_MarsParams(address))
  }
})
// DOC include end: Example_Mars config fragment
