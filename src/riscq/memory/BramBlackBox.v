
//  Xilinx True Dual Port RAM Byte Write, Write First Dual Clock RAM
//  This code implements a parameterizable true dual port memory (both ports can read and write).
//  The behavior of this RAM is when data is written, the new memory contents at the write
//  address are presented on the output port.

module BramBlackBox #(
  parameter NB_COL = 4,                           // Specify number of columns (number of bytes)
  parameter COL_WIDTH = 9,                        // Specify column width (byte width, typically 8 or 9)
  parameter RAM_DEPTH = 1024,                     // Specify RAM depth (number of entries)
  parameter RAM_PERFORMANCE = "HIGH_PERFORMANCE"  // Select "HIGH_PERFORMANCE" or "LOW_LATENCY"
) (
  input [clogb2(RAM_DEPTH-1)-1:0] addra,   // Port A address bus, width determined from RAM_DEPTH
  input [clogb2(RAM_DEPTH-1)-1:0] addrb,   // Port B address bus, width determined from RAM_DEPTH
  input [(NB_COL*COL_WIDTH)-1:0] dina,   // Port A RAM input data
  input [(NB_COL*COL_WIDTH)-1:0] dinb,   // Port B RAM input data
  input clka,                            // Port A clock
  input clkb,                            // Port B clock
  input [NB_COL-1:0] wea,                // Port A write enable
  input [NB_COL-1:0] web,                // Port B write enable
  input ena,                             // Port A RAM Enable, for additional power savings, disable BRAM when not in use
  input enb,                             // Port B RAM Enable, for additional power savings, disable BRAM when not in use
  input rsta,                            // Port A output reset (does not affect memory contents)
  input rstb,                            // Port B output reset (does not affect memory contents)
  input regcea,                          // Port A output register enable
  input regceb,                          // Port B output register enable
  output [(NB_COL*COL_WIDTH)-1:0] douta, // Port A RAM output data
  output [(NB_COL*COL_WIDTH)-1:0] doutb  // Port B RAM output data
);

  reg [(NB_COL*COL_WIDTH)-1:0] BRAM [RAM_DEPTH-1:0];
  reg [(NB_COL*COL_WIDTH)-1:0] ram_data_a = {(NB_COL*COL_WIDTH){1'b0}};
  reg [(NB_COL*COL_WIDTH)-1:0] ram_data_b = {(NB_COL*COL_WIDTH){1'b0}};

  // Initialize the memory to all zeros to match hardware. (The Xilinx template's optional INIT_FILE
  // memory-preload branch was removed: it is unused here, and SpinalSim's mem-init path-rewriter only
  // handles a quoted-literal filename — a parameter argument makes it corrupt the netlist for Verilator.)
  integer ram_index;
  initial
    for (ram_index = 0; ram_index < RAM_DEPTH; ram_index = ram_index + 1)
      BRAM[ram_index] = {(NB_COL*COL_WIDTH){1'b0}};

  generate
  genvar i;
     for (i = 0; i < NB_COL; i = i+1) begin: byte_write
       always @(posedge clka)
         if (ena)
           if (wea[i]) begin
             BRAM[addra][(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= dina[(i+1)*COL_WIDTH-1:i*COL_WIDTH];
             ram_data_a[(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= dina[(i+1)*COL_WIDTH-1:i*COL_WIDTH];
           end else begin
             ram_data_a[(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= BRAM[addra][(i+1)*COL_WIDTH-1:i*COL_WIDTH];
           end

       always @(posedge clkb)
         if (enb)
           if (web[i]) begin
             BRAM[addrb][(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= dinb[(i+1)*COL_WIDTH-1:i*COL_WIDTH];
             ram_data_b[(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= dinb[(i+1)*COL_WIDTH-1:i*COL_WIDTH];
           end else begin
             ram_data_b[(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= BRAM[addrb][(i+1)*COL_WIDTH-1:i*COL_WIDTH];
           end
     end
  endgenerate

  //  The following code generates HIGH_PERFORMANCE (use output register) or LOW_LATENCY (no output register)
  generate
    if (RAM_PERFORMANCE == "LOW_LATENCY") begin: no_output_register

      // The following is a 1 clock cycle read latency at the cost of a longer clock-to-out timing
       assign douta = ram_data_a;
       assign doutb = ram_data_b;

    end else begin: output_register

      // The following is a 2 clock cycle read latency with improve clock-to-out timing

      reg [(NB_COL*COL_WIDTH)-1:0] douta_reg = {(NB_COL*COL_WIDTH){1'b0}};
      reg [(NB_COL*COL_WIDTH)-1:0] doutb_reg = {(NB_COL*COL_WIDTH){1'b0}};

      always @(posedge clka)
        if (rsta)
          douta_reg <= {(NB_COL*COL_WIDTH){1'b0}};
        else if (regcea)
          douta_reg <= ram_data_a;

      always @(posedge clkb)
        if (rstb)
          doutb_reg <= {(NB_COL*COL_WIDTH){1'b0}};
        else if (regceb)
          doutb_reg <= ram_data_b;

      assign douta = douta_reg;
      assign doutb = doutb_reg;

    end
  endgenerate

  //  The following function calculates the address width based on specified RAM depth
  function integer clogb2;
    input integer depth;
      for (clogb2=0; depth>0; clogb2=clogb2+1)
        depth = depth >> 1;
  endfunction

endmodule

// The following is an instantiation template for xilinx_true_dual_port_write_first_byte_write_2_clock_ram
/*
  //  Xilinx True Dual Port RAM Byte Write Write-First Dual Clock RAM
  xilinx_true_dual_port_write_first_byte_write_2_clock_ram #(
    .NB_COL(4),                           // Specify number of columns (number of bytes)
    .COL_WIDTH(9),                        // Specify column width (byte width, typically 8 or 9)
    .RAM_DEPTH(1024),                     // Specify RAM depth (number of entries)
    .RAM_PERFORMANCE("HIGH_PERFORMANCE"), // Select "HIGH_PERFORMANCE" or "LOW_LATENCY" 
    .INIT_FILE("")                        // Specify name/location of RAM initialization file if using one (leave blank if not)
  ) your_instance_name (
    .addra(addra),   // Port A address bus, width determined from RAM_DEPTH
    .addrb(addrb),   // Port B address bus, width determined from RAM_DEPTH
    .dina(dina),     // Port A RAM input data, width determined from NB_COL*COL_WIDTH
    .dinb(dinb),     // Port B RAM input data, width determined from NB_COL*COL_WIDTH
    .clka(clka),     // Port A clock
    .clkb(clkb),     // Port B clock
    .wea(wea),       // Port A write enable, width determined from NB_COL
    .web(web),       // Port B write enable, width determined from NB_COL
    .ena(ena),       // Port A RAM Enable, for additional power savings, disable port when not in use
    .enb(enb),       // Port B RAM Enable, for additional power savings, disable port when not in use
    .rsta(rsta),     // Port A output reset (does not affect memory contents)
    .rstb(rstb),     // Port B output reset (does not affect memory contents)
    .regcea(regcea), // Port A output register enable
    .regceb(regceb), // Port B output register enable
    .douta(douta),   // Port A RAM output data, width determined from NB_COL*COL_WIDTH
    .doutb(doutb)    // Port B RAM output data, width determined from NB_COL*COL_WIDTH
  );
*/
							
						