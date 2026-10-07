//======================================================================
// mars_ip_hmac_adapter.v
//
// The HMAC half of the one-action IP interface; see mars_ip_sha_adapter.v for
// the contract.  The request word is
//
//   ip_req = {strobe, len[15:0], key[255:0], msg[511:0]}
//
// Measured on mars_hmac_glue over secworks/sha256_core: 269 cycles for every
// MARS message length (13, 32, 42), because each of the two passes is exactly
// two blocks.  Hence ip_lat=275.
//======================================================================

module mars_ip_hmac_adapter #(parameter integer DECLARED_LAT = 275) (
    input  wire          CLK,
    input  wire          RST_N,
    input  wire [784:0]  ip_req,
    output wire [255:0]  ip_resp
);

  wire         strobe  = ip_req[784];
  wire [15:0]  req_len = ip_req[783:768];
  wire [255:0] req_key = ip_req[767:512];
  wire [511:0] req_msg = ip_req[511:0];

  reg          active, req_tgl, waiting, strobe_d;
  wire         strobe_rise = strobe && ~strobe_d;
  reg [15:0]   len_q;
  reg [255:0]  key_q;
  reg [511:0]  msg_q;
  reg [255:0]  held;

  wire [255:0] res;
  wire         valid, tag;

  mars_hmac_glue glue (
      .CLK(CLK), .RST_N(RST_N),
      .hmac_active(active), .hmac_key(key_q), .hmac_msg(msg_q),
      .hmac_len(len_q), .hmac_req(req_tgl),
      .hmac_res(res), .hmac_valid(valid), .hmac_tag(tag)
  );

  assign ip_resp = held;

  integer cyc = 0;
  integer strobe_cyc = 0;
  always @(posedge CLK) cyc <= cyc + 1;

  always @(posedge CLK) begin
    if (!RST_N) begin
      active <= 1'b0; req_tgl <= 1'b0; waiting <= 1'b0; strobe_d <= 1'b0;
      len_q <= 16'd0; key_q <= 256'd0; msg_q <= 512'd0; held <= 256'd0;
    end else begin
      strobe_d <= strobe;
      if (strobe_rise) begin
        if (waiting) $display("FAIL: HMAC request at cycle %0d while one is in flight", cyc);
        len_q      <= req_len;
        key_q      <= req_key;
        msg_q      <= req_msg;
        active     <= 1'b1;
        req_tgl    <= ~req_tgl;
        waiting    <= 1'b1;
        strobe_cyc <= cyc;
      end else if (waiting && valid) begin
        held    <= res;
        active  <= 1'b0;
        waiting <= 1'b0;
        if (cyc - strobe_cyc > DECLARED_LAT)
          $display("FAIL: HMAC answered after %0d cycles, ip_lat is %0d",
                   cyc - strobe_cyc, DECLARED_LAT);
      end
    end
  end

endmodule
