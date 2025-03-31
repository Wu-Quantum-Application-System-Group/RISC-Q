#!/bin/bash

TARGET=mmsoc

find . -iname "riscq_bd_wrapper.bit" | xargs -I{} cp {} ./$TARGET.bit
find . -iname "riscq_bd.hwh" | xargs -I{} cp {} ./$TARGET.hwh
tar -czvf $TARGET.tgz $TARGET.bit $TARGET.hwh
rm $TARGET.bit $TARGET.hwh
mv $TARGET.tgz ~/