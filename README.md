# bittiraha-walletd
Lightweight Bitcoin RPC compatible HD wallet

This project is meant as a drop-in replacement for bitcoind for use in lightweight servers.
For the moment, there is no support for bitcoind accounts. Parameters with account names are ignored.

If you need to access walletd from the command-line, one option is to use bitcoin-cli from bitcoind.

## Implemented RPC calls
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

## RPC extensions:

### sendonce
sendonce "identifier" {"address":amount,...}

Sendonce is similar to sendmany. However, the difference is that it will only ever do one send per identifier used.
There will be no error if the same identifier is used again. It will simply be a no-op that returns the txid for the
sending transaction that was created the first time.

If you're building a service that automatically sends out bitcoins, this can be useful as the last resort defense
against bugs that cause multiple sends when only one is intended. Just remember that this method alone is
NOT sufficient.

