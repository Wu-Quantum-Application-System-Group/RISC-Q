ipx::package_project -root_dir ${BUILD_PREFIX}/ip -vendor user.org -library user -taxonomy /UserIP -import_files -set_current false -force -quiet
ipx::unload_core ${BUILD_PREFIX}/ip/component.xml
ipx::open_ipxact_file ${BUILD_PREFIX}/ip/component.xml

ipx::add_bus_parameter FREQ_HZ [ipx::get_bus_interfaces hostClk -of_objects [ipx::current_core]]
set_property value {100000000} [ipx::get_bus_parameters FREQ_HZ -of_objects [ipx::get_bus_interfaces hostClk -of_objects [ipx::current_core]]]
ipx::add_bus_parameter FREQ_HZ [ipx::get_bus_interfaces dspClk -of_objects [ipx::current_core]]
set_property value {500000000} [ipx::get_bus_parameters FREQ_HZ -of_objects [ipx::get_bus_interfaces dspClk -of_objects [ipx::current_core]]]

ipx::associate_bus_interfaces -busif S_AXIS -clock hostClk [ipx::current_core]
ipx::associate_bus_interfaces -busif S_AXIS -clock dspClk -remove [ipx::current_core]

for {set i 0} {$i < 4} {incr i} {
    ipx::associate_bus_interfaces -busif DAC${i}_AXIS -clock dspClk [ipx::current_core]
    ipx::associate_bus_interfaces -busif DAC${i}_AXIS -clock hostClk -remove [ipx::current_core]
    ipx::associate_bus_interfaces -busif ADC${i}_AXIS -clock dspClk [ipx::current_core] 
    ipx::associate_bus_interfaces -busif ADC${i}_AXIS -clock hostClk -remove [ipx::current_core]
}

ipx::add_bus_parameter POLARITY [ipx::get_bus_interfaces hostRst -of_objects [ipx::current_core]]
set_property value ACTIVE_HIGH [ipx::get_bus_parameters POLARITY -of_objects [ipx::get_bus_interfaces hostRst -of_objects [ipx::current_core]]]
ipx::add_bus_parameter POLARITY [ipx::get_bus_interfaces dspExtRst -of_objects [ipx::current_core]]
set_property value ACTIVE_HIGH [ipx::get_bus_parameters POLARITY -of_objects [ipx::get_bus_interfaces dspExtRst -of_objects [ipx::current_core]]]

ipx::merge_project_changes ports [ipx::current_core]
ipx::update_source_project_archive -component [ipx::current_core]
ipx::create_xgui_files [ipx::current_core]
ipx::update_checksums [ipx::current_core]
ipx::check_integrity [ipx::current_core]
ipx::save_core [ipx::current_core]
set_property  ip_repo_paths  ${BUILD_PREFIX}/ip [current_project]
update_ip_catalog