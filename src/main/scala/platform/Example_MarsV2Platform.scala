package trustformer

import chisel3._
import org.chipsalliance.cde.config.Parameters

// MarsV2's platform (coq/Examples/MarsV2/Spec.v): the Mars seed, init request and
// SHA-256/HMAC adapters from Example_MarsPlatform, plus the fault input, tied low.

class Example_MarsV2Platform(implicit p: Parameters) extends Module {
  val io = IO(new Example_MarsV2PlatformIO)

  io.in_param_sec_in_ps := p(MarsSeedKey).U(256.W)
  // Like a boot sequence: _MARS_Init may run once per reset.
  io.in_param_sec_in_init_req := ~io.out_param_pub_out_st
  // The platform's fault detector drives this; this SoC ties it low.
  io.in_param_sec_in_fault := 0.U

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
