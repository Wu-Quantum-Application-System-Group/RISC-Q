# ---- Create the Vivado project and add the top RTL (+ ROM init) ------------------------------------
# Only the top is added here; ClockInterface.v is added *after* the top is packaged as IP (so it stays a
# plain BD module reference and is not swept into the user IP). The .bin is the register-file ROM init
# that PulseTableSoc.v `$readmemb`s — it must accompany the sources into synthesis.

create_project $PRJ $BUILD_DIR -part $PART -force

add_files $SOURCE_PATH/$TOP_MODULE.v
foreach b [glob -nocomplain $SOURCE_PATH/*.bin] { add_files $b }

set_property top $TOP_MODULE [current_fileset]
update_compile_order -fileset sources_1
