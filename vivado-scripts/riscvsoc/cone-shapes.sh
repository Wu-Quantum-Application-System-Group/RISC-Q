#!/bin/bash
# cone-shapes.sh <build-dir>… — mechanism-level shape counts over each build's cones_paths.tsv
# (DSP-side path families). The 3q dspband vehicle nearly meets its 1.8 ns bench target, so cone
# TNS is ~0 and the lever verdict is SHAPE-level: does the lever's target family leave the
# slack<0.15 window (n → 0 / worst margin up) without a new family appearing.
#   dcoffset-bcast   dcOffset_reg → 16-lane output adders
#   active-bcast     activeReg → lane output mux
#   tq-due           TimedQueue ptr→LUTRAM→32b compare → timeUp_reg/D
#   tq-ptr-fanout    TimedQueue FIFO ptr → consumer CE/WE outside timeUp
#   rfbuf-decode     cmd address reg → PulseParamBuffer CE
#   adc-dist / env-dist  ADC-pipe / envelope-bank distance families
for d in "$@"; do
  f="$d/cones_paths.tsv"
  [ -f "$f" ] || { echo "== $(basename $d): no cones_paths.tsv"; continue; }
  echo "== $(basename $d)  (worst slack | n)"
  awk -F'\t' 'NR>1 {
    ep=$5; sp=$6; shape="";
    if (sp ~ /dcOffset_reg/)                                shape="dcoffset-bcast";
    else if (sp ~ /activeReg/)                              shape="active-bcast";
    else if (ep ~ /Q\/timeUp_reg/)                          shape="tq-due";
    else if (sp ~ /logic_ptr/)                              shape="tq-ptr-fanout";
    else if (ep ~ /buf_1\// && ep ~ /\/CE$/)                shape="rfbuf-decode";
    else if (ep ~ /adc|adcSum|robs/ || sp ~ /adc/)          shape="adc-dist";
    else if (ep ~ /MemFiber|ADDR/)                          shape="env-dist";
    else                                                    shape="(other)";
    n[shape]++; if (!(shape in w) || $1 < w[shape]) w[shape]=$1;
  } END { for (s in n) printf "  %-16s %8.3f | %4d\n", s, w[s], n[s]; }' "$f" | sort -k2 -g
done
