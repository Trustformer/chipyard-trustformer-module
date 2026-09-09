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

// DOC include start: Example_LockboxTries params
case class Example_LockboxTriesParams(
  address: BigInt = 0x4000,
)
// DOC include end: Example_LockboxTries params

// DOC include start: Example_LockboxTries key
case object Example_LockboxTriesKey extends Field[Option[Example_LockboxTriesParams]](None)
// DOC include end: Example_LockboxTries key

class Example_LockboxTriesIO() extends Bundle {
  val CLK = Input(Clock())
  val RST_N = Input(Bool())
  val in_cmd_arg = Output(Bool()) // the module's `ready`
  val in_cmd_out = Input(UInt(17.W)) // [16] is valid, [15:0] the command
  val in_param_fs_in_pin_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_fs_in_pin_out = Input(UInt(32.W))
  val in_param_fs_in_secret_arg = Output(Bool()) // hardwired 1'b1, unused
  val in_param_fs_in_secret_out = Input(UInt(32.W))
  val out_param_fs_out_secret_arg = Output(UInt(32.W))
  val out_param_fs_out_secret_out = Input(Bool()) // ack, latched into a register nothing reads
  val out_param_fs_out_status_arg = Output(UInt(32.W))
  val out_param_fs_out_status_out = Input(Bool()) // ack, latched into a register nothing reads
}

class Example_LockboxTriesTopIO extends Bundle {
  val example_LockboxTries_busy = Output(Bool())
}

trait HasExample_LockboxTriesTopIO {
  def io: Example_LockboxTriesTopIO
}

// DOC include start: Example_LockboxTries blackbox
class Example_LockboxTries() extends BlackBox(Map()) with HasBlackBoxResource {
  val io = IO(new Example_LockboxTriesIO())
  addResource("/vsrc/Example_LockboxTries.v")
}
// DOC include end: Example_LockboxTries blackbox

// DOC include start: Example_LockboxTries router
class Example_LockboxTriesTL(params: Example_LockboxTriesParams, beatBytes: Int)(implicit p: Parameters) extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("example_lockboxtries", Seq("ucbbar,example_lockboxtries"))
  val node = TLRegisterNode(Seq(AddressSet(params.address, 4096-1)), device, "reg/control", beatBytes=beatBytes)

  override lazy val module = new Example_LockboxTriesImpl
  class Example_LockboxTriesImpl extends Impl with HasExample_LockboxTriesTopIO {
    val io = IO(new Example_LockboxTriesTopIO)
    withClockAndReset(clock, reset) {
      val cmd = Wire(new DecoupledIO(UInt(16.W)))
      val cmd_reg = RegInit(0.U(16.W))
      val cmd_pending = RegInit(false.B)
      val busy = Wire(Bool())
      val in_param_fs_in_pin_reg = RegInit(0.U(32.W))
      val in_param_fs_in_secret_reg = RegInit(0.U(32.W))

      val impl_io = {
        val impl = Module(new Example_LockboxTries())
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
      impl_io.in_param_fs_in_pin_out := in_param_fs_in_pin_reg
      // held, not pulsed: the module samples this in the accept cycle
      impl_io.in_param_fs_in_secret_out := in_param_fs_in_secret_reg
      impl_io.out_param_fs_out_secret_out := false.B
      impl_io.out_param_fs_out_status_out := false.B
      io.example_LockboxTries_busy := busy
// DOC include start: Example_LockboxTries instance regmap
      node.regmap(
        0x00 -> Seq(RegField.r(1, busy)),
        0x04 -> Seq(RegField.w(16, cmd)),
        0x08 -> Seq(RegField(32, in_param_fs_in_pin_reg)),
        0x0C -> Seq(RegField(32, in_param_fs_in_secret_reg)),
        0x10 -> Seq(RegField.r(32, impl_io.out_param_fs_out_status_arg)),
        0x14 -> Seq(RegField.r(32, impl_io.out_param_fs_out_secret_arg))
          ) 
// DOC include end: Example_LockboxTries instance regmap
    }
  }
}


// DOC include start: Example_LockboxTries lazy trait
trait CanHavePeripheryExample_LockboxTries { this: BaseSubsystem =>
  private val portName = "example_lockboxtries"

  private val pbus = locateTLBusWrapper(PBUS)

  // Only build if we are using the TL (nonAXI4) version
  val (example_LockboxTries_busy, example_LockboxTries_clock) = p(Example_LockboxTriesKey) match {
    case Some(params) => {

      // Define the clock source node for the Example_LockboxTries module.
      val example_LockboxTriesClockNode = pbus.fixedClockNode

      // Define the type of clock crossing required between the pbus and the Example_LockboxTries module.
      val example_LockboxTriesCrossing = SynchronousCrossing()


      // Instantiate the Example_LockboxTries module (either TL, AXI4, or HLS variant)
      val example_LockboxTries = {
        val example_LockboxTries = lazymodule.LazyModule(new Example_LockboxTriesTL(params, pbus.beatBytes)(p))
        // Connect the Example_LockboxTries's clock input to our determined Example_LockboxTriesClockNode.
        example_LockboxTries.clockNode := example_LockboxTriesClockNode
        // Couple the Example_LockboxTries to the pbus, inserting the necessary clock crossing logic.
        pbus.coupleTo(portName) {
          // TLInwardClockCrossingHelper handles crossing details for TileLink.
          TLInwardClockCrossingHelper("Example_LockboxTries_crossing", example_LockboxTries, example_LockboxTries.node)(example_LockboxTriesCrossing) :=
          TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
        }
        example_LockboxTries
      }
      // Expose the Example_LockboxTries's busy signal.
      val example_LockboxTries_busy = lazymodule.InModuleBody {
        val busy = IO(Output(Bool())).suggestName("example_LockboxTries_busy")
        busy := example_LockboxTries.module.io.example_LockboxTries_busy
        busy
      }
      // Return the busy signal (always needed if Example_LockboxTries exists) and the optional external clock input.
      // The Option[Clock] allows the IOBinder (WithExample_LockboxTriesIOPunchthrough) to conditionally
      // create the top-level clock input only when `externallyClocked` is true.
      // The busy signal is Some(busy) because the entire Example_LockboxTries peripheral itself is optional based on Example_LockboxTriesKey.
      (Some(example_LockboxTries_busy), None)
    }
    // If Example_LockboxTriesKey is None, the Example_LockboxTries peripheral is not instantiated. Return None for both signals.
    case None => (None, None)
  }
}
// DOC include end: Example_LockboxTries lazy trait

// DOC include start: Example_LockboxTries config fragment
class WithExample_LockboxTries(address: BigInt = 0x4000) extends Config((site, here, up) => {
  case Example_LockboxTriesKey => {
    Some(Example_LockboxTriesParams(address))
  }
})
// DOC include end: Example_LockboxTries config fragment
