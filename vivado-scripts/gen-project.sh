#!/bin/bash

SCRIPT_PATH="$(realpath "$0")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"

TOP_MODULE=$1

mkdir -p ./build/$TOP_MODULE

cp -r $SCRIPT_DIR/utils ./build/$TOP_MODULE/
cd build/$TOP_MODULE

vivado -nojournal -nolog -mode tcl -source ./utils/riscq-project.tcl -tclarg $TOP_MODULE