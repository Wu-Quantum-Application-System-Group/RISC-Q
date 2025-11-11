#!/bin/bash

SCRIPT_PATH="$(realpath "$0")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"

vivado -nojournal -nolog -mode tcl -source $SCRIPT_DIR/zcu216.tcl -tclarg $1