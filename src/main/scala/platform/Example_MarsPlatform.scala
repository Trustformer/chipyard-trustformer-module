package trustformer

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field, Config}

// The one-action MARS's platform (coq/Examples/Mars/Spec.v, spec section 5.8):
// the Primary Seed, the init request, and the SHA-256 and HMAC IPs behind its
// two IP links. The IP Verilog is a copy of external/ in the Trustformer repo.

case object MarsSeedKey extends Field[BigInt]

// "Here are thirty two secret bytes": the seed the TCG reference emulator and
// sim/tb_mars_v4.sv use, so the digests match theirs.
class WithMarsSeed(
  seed: BigInt = BigInt("4865726520617265207468697274792074776f20736563726574206279746573", 16)
) extends Config((site, here, up) => {
  case MarsSeedKey => seed
})

class MarsShaAdapter extends BlackBox with HasBlackBoxResource {
  override def desiredName = "mars_ip_sha_adapter"
  val io = IO(new Bundle {
    val CLK = Input(Clock())
    val RST_N = Input(Bool())
    val ip_req = Input(UInt(1041.W))
    val ip_resp = Output(UInt(256.W))
  })
  Seq("mars_ip_sha_adapter", "mars_sha256_glue", "sha256_core", "sha256_k_constants", "sha256_w_mem")
    .foreach(f => addResource(s"/vsrc/mars/$f.v"))
}

class MarsHmacAdapter extends BlackBox with HasBlackBoxResource {
  override def desiredName = "mars_ip_hmac_adapter"
  val io = IO(new Bundle {
    val CLK = Input(Clock())
    val RST_N = Input(Bool())
    val ip_req = Input(UInt(785.W))
    val ip_resp = Output(UInt(256.W))
  })
  Seq("mars_ip_hmac_adapter", "mars_hmac_glue", "sha256_core", "sha256_k_constants", "sha256_w_mem")
    .foreach(f => addResource(s"/vsrc/mars/$f.v"))
}

class Example_MarsPlatform(implicit p: Parameters) extends Module {
  val io = IO(new Example_MarsPlatformIO)

  io.in_param_sec_in_ps := p(MarsSeedKey).U(256.W)
  // Like a boot sequence: _MARS_Init may run once per reset. out_st turns 1
  // when it completes, and only reset clears it.
  io.in_param_sec_in_init_req := ~io.out_param_pub_out_st

  val sha = Module(new MarsShaAdapter)
  sha.io.CLK := clock
  sha.io.RST_N := !reset.asBool
  sha.io.ip_req := io.ip_req_sec_ip_sha
  io.ip_resp_sec_ip_sha := sha.io.ip_resp

  val hmac = Module(new MarsHmacAdapter)
  hmac.io.CLK := clock
  hmac.io.RST_N := !reset.asBool
  hmac.io.ip_req := io.ip_req_sec_ip_hmac
  io.ip_resp_sec_ip_hmac := hmac.io.ip_resp
}
