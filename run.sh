#!/bin/sh
ant compile && ant jar && java -cp build/jar/Walletd.jar:lib/* fi.bittiraha.walletd.Test
