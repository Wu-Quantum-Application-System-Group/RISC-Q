#!/bin/bash

SCRIPT_PATH="$(realpath "$0")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"

TOP_MODULE=$1
PROJ_NAME=$2

if [ -z "$PROJ_NAME" ]; then
    PROJ_NAME=$TOP_MODULE
fi

mkdir -p ./build/$TOP_MODULE/$PROJ_NAME

cp -r $SCRIPT_DIR/utils ./build/$TOP_MODULE/$PROJ_NAME/
cd build/$TOP_MODULE/$PROJ_NAME

vivado -nojournal -nolog -mode tcl -source ./utils/riscq-project.tcl -tclarg $TOP_MODULE $PROJ_NAME