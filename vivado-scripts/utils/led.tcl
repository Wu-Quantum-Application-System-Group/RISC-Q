create_bd_port -dir O ledR
create_bd_port -dir O ledB
set ledR_pin [get_bd_pins -quiet ${TOP}/io_ledR]
set ledB_pin [get_bd_pins -quiet ${TOP}/io_ledB]
if {[llength $ledR_pin] > 0} {
    connect_bd_net [get_bd_pins ${ledR_pin}] [get_bd_ports ledR]
}
if {[llength $ledB_pin] > 0} {
    connect_bd_net [get_bd_pins ${ledB_pin}] [get_bd_ports ledB]
}
