//======================================================================
// mars_sha256_glue.v
//
// The adapter between the verified MARS module's sha_* port and
// secworks/sha256_core.  MVP.md section 5.3 / section 9 A6/A8: this is TCB,
// so it is deliberately small and does nothing but sequence and pad blocks.
//
// Two facts about the core drive this, both read out of sha256_core.v rather
// than assumed:
//
//   1. It does NOT pad.  It takes 512-bit blocks with init (first) / next
//      (subsequent), so the padding is the caller's job -- section 9 A8.
//
//   2. digest_valid is set in CTRL_DONE and cleared only when the next
//      init/next starts (L505/L516/L541), so it STAYS HIGH between requests.
//      The module binds responses by TAG rather than by two-phase arming, so
//      that is tolerable; what is not is answering with the wrong tag.
//
// MARS hashes exactly four message lengths and no others: 36, 64, 68 and 100
// bytes (MVP.md section 3).  The padding is therefore a four-way case rather
// than a byte-indexed shifter, and the block count follows from the length.
// A length outside that set produces no request -- better a stall the test
// bench notices than a silently mis-padded hash.
//======================================================================

module mars_sha256_glue (
    input  wire           CLK,
    input  wire           RST_N,

    input  wire           sha_active,
    input  wire [1023:0]  sha_msg,
    input  wire [15:0]    sha_len,
    input  wire           sha_req,

    output reg  [255:0]   sha_res,
    output reg            sha_valid,
    output reg            sha_tag
);

  localparam S_IDLE = 2'd0,
             S_B1   = 2'd1,
             S_B2   = 2'd2;

  // Only 36 bytes fits in a single block: 36 + 1 + 8 = 45 <= 64.
  wire one_block = (sha_len == 16'd36);

  // First block: the whole 512 bits for a two-block message, or the message
  // plus its padding for the one-block case.
  function [511:0] block1(input [1023:0] m, input [15:0] len);
    case (len)
      16'd36:  block1 = {m[1023:736], 8'h80, 152'd0, 64'd288};
      default: block1 = m[1023:512];
    endcase
  endfunction

  // Second block: whatever is left of the message, then 0x80, zeros, and the
  // total message length in BITS as a 64-bit big-endian integer.
  function [511:0] block2(input [1023:0] m, input [15:0] len);
    case (len)
      16'd64:  block2 = {                8'h80, 440'd0, 64'd512};
      16'd68:  block2 = {m[511:480],     8'h80, 408'd0, 64'd544};
      16'd100: block2 = {m[511:224],     8'h80, 152'd0, 64'd800};
      default: block2 = 512'd0;
    endcase
  endfunction

  reg  [1:0]   state;
  reg          last_req;
  reg  [511:0] blk;
  reg          core_init, core_next;

  wire         core_ready;
  wire         core_dv;
  wire [255:0] core_digest;

  sha256_core core (
      .clk(CLK), .reset_n(RST_N),
      .init(core_init), .next(core_next),
      .mode(1'b1),                       // 1 = SHA-256, 0 = SHA-224
      .block(blk),
      .ready(core_ready), .digest(core_digest), .digest_valid(core_dv)
  );

  wire known_len   = (sha_len == 16'd36) || (sha_len == 16'd64)
                  || (sha_len == 16'd68) || (sha_len == 16'd100);
  wire new_request = sha_active && (sha_req != last_req) && known_len;

  always @(posedge CLK) begin
    if (!RST_N) begin
      state     <= S_IDLE;
      last_req  <= 1'b0;
      core_init <= 1'b0;
      core_next <= 1'b0;
      sha_res   <= 256'd0;
      sha_valid <= 1'b0;
      sha_tag   <= 1'b0;
      blk       <= 512'd0;
    end else begin
      core_init <= 1'b0;
      core_next <= 1'b0;

      // GLUE_OMIT_DEASSERT removes exactly this.  Since the module binds
      // responses by TAG, a glue that merely holds valid high is still
      // CORRECT -- its tags are honest -- and oracle/run-stage3.sh asserts the
      // module keeps working under it.  What is not tolerable is a wrong tag;
      // see GLUE_STALE_TAG below.
`ifndef GLUE_OMIT_DEASSERT
      if (!sha_active)
        sha_valid <= 1'b0;
`endif

      case (state)
        S_IDLE:
          if (new_request && core_ready) begin
            last_req  <= sha_req;
            sha_valid <= 1'b0;
            blk       <= block1(sha_msg, sha_len);
            core_init <= 1'b1;
            state     <= S_B1;
          end

        S_B1:
          // ready falls on init and rises again when the block is absorbed.
          if (core_ready && !core_init) begin
            if (one_block) begin
              sha_res   <= core_digest;
`ifdef GLUE_STALE_TAG
              sha_tag   <= ~last_req;
`else
              sha_tag   <= last_req;
`endif
              sha_valid <= 1'b1;
              state     <= S_IDLE;
            end else begin
              blk       <= block2(sha_msg, sha_len);
              core_next <= 1'b1;
              state     <= S_B2;
            end
          end

        S_B2:
          if (core_ready && !core_next) begin
            sha_res   <= core_digest;
`ifdef GLUE_STALE_TAG
            // A misbehaving TCB: answer with the tag of the PREVIOUS request.
            // Two-phase arming could not distinguish this; the request tag can,
            // so the module must enter failure mode rather than latch a result
            // it cannot bind to its own request.
            sha_tag   <= ~last_req;
`else
            sha_tag   <= last_req;   // echo the request we served
`endif
            sha_valid <= 1'b1;
            state     <= S_IDLE;
          end
      endcase
    end
  end

endmodule
