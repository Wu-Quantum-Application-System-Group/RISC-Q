//  Xilinx UltraRAM True Dual Port Mode - Byte write.  This code implements 
//  a parameterizable UltraRAM block with write/read on both ports in 
//  No change behavior on both the ports . The behavior of this RAM is 
//  when data is written, the output of RAM is unchanged w.r.t each port. 
//  Only when write is inactive data corresponding to the address is 
//  presented on the output port.
//
module xilinx_ultraram_true_dual_port_bytewrite #(
  parameter AWIDTH  = 12,  // Address Width
  parameter NUM_COL = 9,   // Number of columns
  parameter DWIDTH  = 72,  // Data Width, (Byte * NUM_COL) 
  parameter NBPIPE  = 3    // Number of pipeline Registers
 ) ( 
    input clk,                    // Clock
    // Port A
    input [NUM_COL-1:0] wea,      // Write Enable
    input mem_ena,                // Memory Enable
    input [DWIDTH-1:0] dina,      // Data Input  
    input [AWIDTH-1:0] addra,     // Address Input
    output reg [DWIDTH-1:0] douta,// Data Output

    // Port B
    input [NUM_COL-1:0] web,      // Write Enable
    input mem_enb,                // Memory Enable
    input [DWIDTH-1:0] dinb,      // Data Input  
    input [AWIDTH-1:0] addrb,     // Address Input
    output reg [DWIDTH-1:0] doutb // Data Output
   );

(* ram_style = "ultra" *)
reg [DWIDTH-1:0] mem[(1<<AWIDTH)-1:0];        // Memory Declaration

reg [DWIDTH-1:0] memrega;              
reg [DWIDTH-1:0] mem_pipe_rega[NBPIPE-1:0];    // Pipelines for memory
reg mem_en_pipe_rega[NBPIPE:0];                // Pipelines for memory enable  

reg [DWIDTH-1:0] memregb;              
reg [DWIDTH-1:0] mem_pipe_regb[NBPIPE-1:0];    // Pipelines for memory
reg mem_en_pipe_regb[NBPIPE:0];                // Pipelines for memory enable  

integer          i;
localparam CWIDTH = DWIDTH/NUM_COL;

// RAM : Read has one latency, Write has one latency as well.
always @ (posedge clk)
begin
 if(mem_ena) 
  begin
  for(i = 0;i<NUM_COL;i=i+1) 
	 if(wea[i])
    mem[addra][i*CWIDTH +: CWIDTH] <= dina[i*CWIDTH +: CWIDTH];
  end     
end

always @ (posedge clk)
begin
 if(mem_ena)
  if(~|wea)
    memrega <= mem[addra];
end

// The enable of the RAM goes through a pipeline to produce a
// series of pipelined enable signals required to control the data
// pipeline.
always @ (posedge clk)
begin
mem_en_pipe_rega[0] <= mem_ena;
 for (i=0; i<NBPIPE; i=i+1)
  mem_en_pipe_rega[i+1] <= mem_en_pipe_rega[i];
end

// RAM output data goes through a pipeline.
always @ (posedge clk)
begin
 if (mem_en_pipe_rega[0])
  mem_pipe_rega[0] <= memrega;
end

always @ (posedge clk)
begin
 for (i = 0; i < NBPIPE-1; i = i+1)
  if (mem_en_pipe_rega[i+1])
    mem_pipe_rega[i+1] <= mem_pipe_rega[i];
end

always @ (posedge clk)
begin
 if (mem_en_pipe_rega[NBPIPE])
  douta <= mem_pipe_rega[NBPIPE-1];
end 

// RAM : Read has one latency, Write has one latency as well.
always @ (posedge clk)
begin
 if(mem_enb) 
  begin
  for(i=0;i<NUM_COL;i=i+1)
	 if(web[i])
    mem[addrb][i*CWIDTH +: CWIDTH] <= dinb[i*CWIDTH +: CWIDTH];
  end     
end

always @ (posedge clk)
begin
 if(mem_enb)
  if(~|web)
    memregb <= mem[addrb];
end

// The enable of the RAM goes through a pipeline to produce a
// series of pipelined enable signals required to control the data
// pipeline.
always @ (posedge clk)
begin
mem_en_pipe_regb[0] <= mem_enb;
 for (i=0; i<NBPIPE; i=i+1)
  mem_en_pipe_regb[i+1] <= mem_en_pipe_regb[i];
end

// RAM output data goes through a pipeline.
always @ (posedge clk)
begin
 if (mem_en_pipe_regb[0])
  mem_pipe_regb[0] <= memregb;
end

always @ (posedge clk)
begin
 for (i = 0; i < NBPIPE-1; i = i+1)
  if (mem_en_pipe_regb[i+1])
    mem_pipe_regb[i+1] <= mem_pipe_regb[i];
end

always @ (posedge clk)
begin
 if (mem_en_pipe_regb[NBPIPE])
  doutb <= mem_pipe_regb[NBPIPE-1];
end 

endmodule
/* 
// The following is an instantation template for
// xilinx_ultraram_true_dual_port_bytewrite

   xilinx_ultraram_true_dual_port_bytewrite # (
                                             .NUM_COL(NUM_COL), 
                                             .AWIDTH(AWIDTH),
                                             .DWIDTH(DWIDTH),
                                             .NBPIPE(NBPIPE)
                                            )
                      your_instance_name    (
                                             clk(clk),   
                                             wea(wea),    
                                             mem_ena(mem_ena),
                                             dina(dina), 
                                             addra(addra),
                                             douta(douta),
                                             web(web),    
                                             mem_enb(mem_enb),
                                             dinb(dinb), 
                                             addrb(addrb),
                                             doutb(doutb)
                                            );
*/                                           
						
						