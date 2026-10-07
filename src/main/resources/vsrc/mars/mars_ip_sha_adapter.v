//======================================================================
// mars_ip_sha_adapter.v
//
// The one-action MARS talks to an IP through ONE request word and ONE
// response word:
//
//   ip_req  = {strobe, len[15:0], msg[1023:0]}     a one-cycle pulse
//   ip_resp = digest[255:0]                        sampled ip_lat cycles later
//
// There is no valid line and no tag, because the schedule already knows WHEN
// the answer is due: [ip_lat] is declared in the Coq context and the wait is
// compiled into the action.  Two obligations follow for whoever attaches an
// IP, and this adapter is where they are discharged:
//
//   1. the answer must be ready by [ip_lat] cycles after the strobe -- so
//      ip_lat must cover the block count of the LONGEST message, and
//   2. it must still be on the wire at that cycle -- so the adapter HOLDS the
//      last answer rather than presenting it for a window.
//
// Measured on secworks/sha256_core + mars_sha256_glue: 68 cycles for a
// one-block message (36 bytes), 135 for two (64, 68, 100).  Hence ip_lat=140.
//
// DECLARED_LAT is here only so the bench can check obligation 1 rather than
// assume it; nothing in the datapath reads it.
//======================================================================

module mars_ip_sha_adapter #(parameter integer DECLARED_LAT = 140) (
    input  wire           CLK,
    input  wire           RST_N,
    input  wire [1040:0]  ip_req,
    output wire [255:0]   ip_resp
);

  wire          strobe  = ip_req[1040];
  wire [15:0]   req_len = ip_req[1039:1024];
  wire [1023:0] req_msg = ip_req[1023:0];

  reg           active, req_tgl, waiting, strobe_d;
  wire          strobe_rise = strobe && ~strobe_d;
  reg [15:0]    len_q;
  reg [1023:0]  msg_q;
  reg [255:0]   held;

  wire [255:0]  res;
  wire          valid, tag;

  mars_sha256_glue glue (
      .CLK(CLK), .RST_N(RST_N),
      .sha_active(active), .sha_msg(msg_q), .sha_len(len_q), .sha_req(req_tgl),
      .sha_res(res), .sha_valid(valid), .sha_tag(tag)
  );

  assign ip_resp = held;

  integer cyc = 0;
  integer strobe_cyc = 0;
  always @(posedge CLK) cyc <= cyc + 1;

  always @(posedge CLK) begin
    if (!RST_N) begin
      active <= 1'b0; req_tgl <= 1'b0; waiting <= 1'b0; strobe_d <= 1'b0;
      len_q <= 16'd0; msg_q <= 1024'd0; held <= 256'd0;
    end else begin
      strobe_d <= strobe;
      if (strobe_rise) begin
        if (waiting) $display("FAIL: SHA request at cycle %0d while one is in flight", cyc);
        len_q      <= req_len;
        msg_q      <= req_msg;
        active     <= 1'b1;
        req_tgl    <= ~req_tgl;
        waiting    <= 1'b1;
        strobe_cyc <= cyc;
      end else if (waiting && valid) begin
        // Dropping [active] makes the glue clear its valid, so the next
        // request cannot see this one's.
        held    <= res;
        active  <= 1'b0;
        waiting <= 1'b0;
        if (cyc - strobe_cyc > DECLARED_LAT)
          $display("FAIL: SHA answered after %0d cycles, ip_lat is %0d",
                   cyc - strobe_cyc, DECLARED_LAT);
      end
    end
  end

endmodule
