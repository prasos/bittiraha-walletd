#!/bin/sh
java -cp build/jar/Walletd.jar:lib/* fi.bittiraha.walletd.Main 2>&1 | ts | tee debug.log
