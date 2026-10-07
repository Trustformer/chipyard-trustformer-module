//======================================================================
// mars_hmac_glue.v
//
// HMAC-SHA256 for the MARS module's hmac_* port, built on the same
// secworks/sha256_core the hash path uses.
//
// Why it is built here rather than vendored: secworks/hmac_core computes a
// full 256-bit digest internally but its `tag` port is 128 bits wide
// (hmac_core.v L52), and hmac.v exposes only those.  MARS needs 32 bytes
// (PROFILE_LEN_SIGN), and editing vendored IP is not an option -- so the
// two-pass structure lives here instead.
//
// That puts HMAC inside the TCB, which is the cost.  It is bounded and
// checked: sim/tb_mars_v4.sv and sim/tb_mars_pcrextend.sv compare MARS_Quote's
// signature against the TCG reference emulator's byte for byte, so a wrong
// pad, a wrong length field or a swapped pass shows up immediately.
//
//   HMAC(K, m) = H( (K0 ^ opad) || H( (K0 ^ ipad) || m ) )
//
// with K0 = K padded to the 64-byte block.  MARS keys are exactly 32 bytes
// and its messages are 13, 32 or 42 bytes, all under 56 -- so each pass is
// exactly two blocks and no message ever needs a third.  The block count is
// therefore fixed and the padding is a small case, not a barrel shifter.
//======================================================================

module mars_hmac_glue (
    input  wire           CLK,
    input  wire           RST_N,

    input  wire           hmac_active,
    input  wire [255:0]   hmac_key,
    input  wire [511:0]   hmac_msg,
    input  wire [15:0]    hmac_len,
    input  wire           hmac_req,

    output reg  [255:0]   hmac_res,
    output reg            hmac_valid,
    output reg            hmac_tag
);

  localparam [7:0] IPAD = 8'h36;
  localparam [7:0] OPAD = 8'h5c;

  localparam S_IDLE = 3'd0,
             S_I1   = 3'd1,   // inner block 1: K0 ^ ipad
             S_I2   = 3'd2,   // inner block 2: message, padded
             S_O1   = 3'd3,   // outer block 1: K0 ^ opad
             S_O2   = 3'd4;   // outer block 2: inner digest, padded

  // K0 is the key followed by zero bytes, so the low half of K0 ^ pad is just
  // the pad byte repeated.
  wire [511:0] k_ipad = { hmac_key ^ {32{IPAD}}, {32{IPAD}} };
  wire [511:0] k_opad = { hmac_key ^ {32{OPAD}}, {32{OPAD}} };

  // Message padded into one block: m, 0x80, zeros, then the length of
  // (block || m) in BITS as a 64-bit big-endian integer.
  function [511:0] pad_msg(input [511:0] m, input [15:0] len);
    case (len)
      16'd13:  pad_msg = {m[511:408], 8'h80, 336'd0, 64'd616};  // (64+13)*8
      16'd32:  pad_msg = {m[511:256], 8'h80, 184'd0, 64'd768};  // (64+32)*8
      16'd42:  pad_msg = {m[511:176], 8'h80, 104'd0, 64'd848};  // (64+42)*8
      default: pad_msg = 512'd0;                                // unreachable
    endcase
  endfunction

  reg  [2:0]   state;
  reg          last_req;
  reg  [255:0] inner;
  reg  [511:0] block;
  reg          core_init, core_next;

  wire         core_ready;
  wire         core_dv;
  wire [255:0] core_digest;

  sha256_core core (
      .clk(CLK), .reset_n(RST_N),
      .init(core_init), .next(core_next),
      .mode(1'b1),
      .block(block),
      .ready(core_ready), .digest(core_digest), .digest_valid(core_dv)
  );

  wire new_request = hmac_active && (hmac_req != last_req);

  always @(posedge CLK) begin
    if (!RST_N) begin
      state      <= S_IDLE;
      last_req   <= 1'b0;
      core_init  <= 1'b0;
      core_next  <= 1'b0;
      hmac_res   <= 256'd0;
      hmac_valid <= 1'b0;
      hmac_tag   <= 1'b0;
      inner      <= 256'd0;
      block      <= 512'd0;
    end else begin
      core_init <= 1'b0;
      core_next <= 1'b0;

      // A7: the module took the result and went idle.
      if (!hmac_active)
        hmac_valid <= 1'b0;

      case (state)
        S_IDLE:
          if (new_request && core_ready) begin
            last_req   <= hmac_req;
            hmac_valid <= 1'b0;
            block      <= k_ipad;
            core_init  <= 1'b1;
            state      <= S_I1;
          end

        S_I1:
          if (core_ready && !core_init) begin
            block     <= pad_msg(hmac_msg, hmac_len);
            core_next <= 1'b1;
            state     <= S_I2;
          end

        S_I2:
          if (core_ready && !core_next) begin
            inner     <= core_digest;
            block     <= k_opad;
            core_init <= 1'b1;
            state     <= S_O1;
          end

        S_O1:
          if (core_ready && !core_init) begin
            // the inner digest is 32 bytes, so (64+32)*8 = 768
            block     <= {inner, 8'h80, 184'd0, 64'd768};
            core_next <= 1'b1;
            state     <= S_O2;
          end

        S_O2:
          if (core_ready && !core_next) begin
            hmac_res   <= core_digest;
            hmac_tag   <= last_req;
            hmac_valid <= 1'b1;
            state      <= S_IDLE;
          end
      endcase
    end
  end

endmodule
