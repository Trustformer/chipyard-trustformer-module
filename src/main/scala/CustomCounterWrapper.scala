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

// DOC include start: CustomCounter params
case class CustomCounterParams(
  address: BigInt = 0x4000,
)
// DOC include end: CustomCounter params

// DOC include start: CustomCounter key
case object CustomCounterKey extends Field[Option[CustomCounterParams]](None)
// DOC include end: CustomCounter key

class CustomCounterIO() extends Bundle {
  val CLK = Input(Clock())
  val RST_N = Input(Bool())
  val read_command_arg = Output(Bool())
val read_command_out = Input(UInt((32 + 1).W)) // [32] is valid bit, [31:0] is command
val read_arg_arg = Output(Bool())
val read_arg_out = Input(UInt((32 + 1).W)) // [32] is valid bit, [31:0] is command
val write_result_arg = Output(UInt(32.W))
val write_result_out = Input(Bool())

}

class CustomCounterTopIO extends Bundle {
  val customCounter_busy = Output(Bool())
}

trait HasCustomCounterTopIO {
  def io: CustomCounterTopIO
}

// DOC include start: CustomCounter blackbox
class CustomCounter() extends BlackBox(Map()) with HasBlackBoxResource {
  val io = IO(new CustomCounterIO())
  addResource("/vsrc/CustomCounter.v")
}
// DOC include end: CustomCounter blackbox

// DOC include start: CustomCounter router
class CustomCounterTL(params: CustomCounterParams, beatBytes: Int)(implicit p: Parameters) extends ClockSinkDomain(ClockSinkParameters())(p) {
  val device = new SimpleDevice("customcounter", Seq("ucbbar,customcounter"))
  val node = TLRegisterNode(Seq(AddressSet(params.address, 4096-1)), device, "reg/control", beatBytes=beatBytes)

  override lazy val module = new CustomCounterImpl
  class CustomCounterImpl extends Impl with HasCustomCounterTopIO {
    val io = IO(new CustomCounterTopIO)
    withClockAndReset(clock, reset) {
      val read_command_arg = Wire(Bool())
val read_command_out = Wire(new DecoupledIO(UInt(32.W)))
val read_arg_arg = Wire(Bool())
val read_arg_out = Wire(new DecoupledIO(UInt(32.W)))
val write_result_arg = Wire(new DecoupledIO(UInt(32.W)))
val status = Wire(Bool())


      val impl_io = {
        val impl = Module(new CustomCounter())
        impl.io
      } 
      
      impl_io.CLK := clock
      impl_io.RST_N := !reset.asBool

      read_command_arg := impl_io.read_command_arg
read_command_out.ready := impl_io.read_command_arg
impl_io.read_command_out := Cat(read_command_out.valid, read_command_out.bits)
read_arg_arg := impl_io.read_arg_arg
read_arg_out.ready := impl_io.read_arg_arg
impl_io.read_arg_out := Cat(read_arg_out.valid, read_arg_out.bits)
write_result_arg.bits := impl_io.write_result_arg
write_result_arg.valid := true.B
impl_io.write_result_out := write_result_arg.ready
impl_io.write_result_out := write_result_arg.ready
status := io.customCounter_busy

      io.customCounter_busy := !(true.B && read_command_arg && read_arg_arg)
// DOC include start: CustomCounter instance regmap
      node.regmap(
        0x04 -> Seq(RegField.w(32, read_command_out)),
0x08 -> Seq(RegField.w(32, read_arg_out)),
0x0C -> Seq(RegField.r(32, write_result_arg)),
0x00 -> Seq(RegField.r(1, status))
          ) 
// DOC include end: CustomCounter instance regmap
    }
  }
}


// DOC include start: CustomCounter lazy trait
trait CanHavePeripheryCustomCounter { this: BaseSubsystem =>
  private val portName = "customcounter"

  private val pbus = locateTLBusWrapper(PBUS)

  // Only build if we are using the TL (nonAXI4) version
  val (customCounter_busy, customCounter_clock) = p(CustomCounterKey) match {
    case Some(params) => {

      // Define the clock source node for the CustomCounter module.
      val customCounterClockNode = pbus.fixedClockNode

      // Define the type of clock crossing required between the pbus and the CustomCounter module.
      val customCounterCrossing = SynchronousCrossing()


      // Instantiate the CustomCounter module (either TL, AXI4, or HLS variant)
      val customCounter = {
        val customCounter = lazymodule.LazyModule(new CustomCounterTL(params, pbus.beatBytes)(p))
        // Connect the CustomCounter's clock input to our determined CustomCounterClockNode.
        customCounter.clockNode := customCounterClockNode
        // Couple the CustomCounter to the pbus, inserting the necessary clock crossing logic.
        pbus.coupleTo(portName) {
          // TLInwardClockCrossingHelper handles crossing details for TileLink.
          TLInwardClockCrossingHelper("CustomCounter_crossing", customCounter, customCounter.node)(customCounterCrossing) :=
          TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
        }
        customCounter
      }
      // Expose the CustomCounter's busy signal.
      val customCounter_busy = lazymodule.InModuleBody {
        val busy = IO(Output(Bool())).suggestName("customCounter_busy")
        busy := customCounter.module.io.customCounter_busy
        busy
      }
      // Return the busy signal (always needed if CustomCounter exists) and the optional external clock input.
      // The Option[Clock] allows the IOBinder (WithCustomCounterIOPunchthrough) to conditionally
      // create the top-level clock input only when `externallyClocked` is true.
      // The busy signal is Some(busy) because the entire CustomCounter peripheral itself is optional based on CustomCounterKey.
      (Some(customCounter_busy), None)
    }
    // If CustomCounterKey is None, the CustomCounter peripheral is not instantiated. Return None for both signals.
    case None => (None, None)
  }
}
// DOC include end: CustomCounter lazy trait

// DOC include start: CustomCounter config fragment
class WithCustomCounter(address: BigInt = 0x4000) extends Config((site, here, up) => {
  case CustomCounterKey => {
    Some(CustomCounterParams(address))
  }
})
// DOC include end: CustomCounter config fragment
