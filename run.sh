#!/bin/sh
/bin/mv debug.log debug.log.old
java -cp build/jar/Walletd.jar:lib/* fi.bittiraha.walletd.Main 2>&1 | ts | tee debug.log
