# bittiraha-walletd
Lightweight Bitcoin RPC compatible HD wallet

This project is meant as a drop-in replacement for bitcoind for use in lightweight servers.
For the moment, there is no support for bitcoind accounts. Parameters with account names are ignored.

If you need to access walletd from the command-line, one option is to use bitcoin-cli from bitcoind.

## Compiling
Developed with jdk 1.7.
All dependencies are included as .jar files under lib directory. A pull request with a working maven integration welcome.

Build process currently uses ant. To build, you can run the build script.
`sh build.sh`

## Running

Running requires a somewhat complex command, so the codebase includes run.sh script for that.
To start walletd, run:
`sh run.sh`

Walletd supports both mainnet and testnet. Default configuration will run both at once.
Wallet file for mainnet is `mainnet.wallet` and for testnet `testnet.wallet`.

## Configuration
There are two configuration files. One for mainnet `mainnet.conf` and one for testnet `testnet.conf`.
They will not be created automatically.

Currently supported configuration options are `start` and `sendUnconfirmedChange`. They both default to 1.
if you add `start=0` to either `testnet.conf` or `mainnet.conf`, that completely disables running that network.
`sendUnconfirmedChange=0` means that the wallet will not send coins forward before they have at least one confirmation. It also means no unconfirmed coins, even own change coins, will be counted towards balances reported by getinfo or getbalance.

## Implemented RPC calls
```
getinfo
getnewaddress
getaccountaddress (alias for getnewaddress)
getunconfirmedbalance
getbalance
sendtoaddress "bitcoinaddress" amount
sendmany "ignored" {"address":amount,...}
sendfrom "ignored" "bitcoinaddress" amount
validateaddress "bitcoinaddress"
listunspent (minconf maxconf ["address",...])
```
## RPC extensions:

### sendonce
`sendonce "identifier" {"address":amount,...}`

Sendonce is similar to sendmany. However, the difference is that it will only ever do one send per identifier used.
There will be no error if the same identifier is used again. It will simply be a no-op that returns the txid for the
sending transaction that was created the first time.

If you're building a service that automatically sends out bitcoins, this can be useful as the last resort defense
against bugs that cause multiple sends when only one is intended. Just remember that this method alone is
NOT sufficient.

