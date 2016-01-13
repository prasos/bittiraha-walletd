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

Requirements are moreutils, java, jdk and ant. On ubuntu:

	sudo aptitude install moreutils default-jre default-jdk ant

On OS X install JDK manually. moreutils and ant can be installed with homebrew

	brew install ant moreutils

Running requires a somewhat complex command, so the codebase includes run.sh script for that.
To start walletd, run:

	`sh run.sh`

Walletd supports both mainnet and testnet. Default configuration will run both at once.
Wallet file for mainnet is `mainnet.wallet` and for testnet `testnet.wallet`.

## Configuration
There are two configuration files. One for mainnet `mainnet.conf` and one for testnet `testnet.conf`.
They will not be created automatically. a config file expressing the default settings would look like this
```
# start determines whether this network will be started. 1 means start, 0 means don't
start=1

# sendUnconfirmedChange determines whether walletd will consider unconfirmed change outputs spendable.
# 1 means they are spendable, 0 means they're not. You can use this to deal with malleability attack and
# to make your transactions look less risky to accept as 0-conf.
sendUnconfirmedChange=1

# targetCoinCount and targetCoinAmount control automatic splitting of change, which is helpful if sendUncofirmedChange
# is disabled. If you wish to disable the feature, set targetCoinCount to 0. Basically, walletd will split the change
# into more than one output when change is more than targetCoinAmount and there are less than targetCoinCount outputs
# of at least targetCoinAmount coins present in the wallet. Outputs smaller than targetCoinAmount will be counted as
# fractional outputs.
targetCoinCount=8
targetCoinAmount=0.5
```

## Working with the Wallet

If you wish to backup the wallet file itself, you can simply make a copy of the wallet file `mainnet.wallet`. The file
is in the default bitcoinj protobuf format and usable with many other bitcoinj tools.

The repository also includes a tool for manipulating bitcoinj wallet files. It's useful when you need to work with
the wallet file directly. For example if you want to make a backup of the wallet's seed or restore a wallet from
a seed. This tool was gratefully swiped from the bitcoinj repository.

### Creating wallet

Mainnet:
```
sh wallet-tool.sh create --wallet=mainnet.wallet
```

Testnet:
```
sh wallet-tool.sh create --wallet=testnet.wallet --net=TEST
```

These commands will create files mainnet.wallet and testnet.wallet, which will contain unencrypted wallet data.

### Backing up the wallet seed

Mainnet wallet: `sh wallet-tool.sh dump --wallet=mainnet.wallet --dump-privkeys | grep Seed`
Testnet wallet: `sh wallet-tool.sh dump --wallet=testnet.wallet --dump-privkeys | grep Seed`

You will see the seed in two formats. You only need one of them. One is a list of 12 words and the other is a
long hexadecimal string of random letters and nunmbers. It's also a good idea to store the wallet creation date with
the seed.

### Resetting the wallet

This cannot be done when walletd is running. Stop it first.

Mainnet:
```
sh wallet-tool.sh reset --wallet=mainnet.wallet
rm mainnet.spvchain
```
Testnet:
```
sh wallet-tool.sh reset --wallet=testnet.wallet
rm testnet.spvchain
```

After this you need to start walletd again. It'll take a little while to be usable again.

### Restoring wallet from Seed

This should be done before starting walletd for the first time.

Mainnet:
```
sh wallet-tool.sh create --wallet=mainnet.wallet --seed 'this is where you write the seed' --date 'YYYY/MM/DD'
```

Testnet:
```
sh wallet-tool.sh create --wallet=testnet.wallet --seed 'this is where you write the seed' --date 'YYYY/MM/DD'
```

These commands can't be just copy&pasted, you'll need to edit them to contain your seed. The date is for wallet
creation date. Walletd will scan the network for transactions related to this wallet starting from that date. Date is
not required, but syncing with the network is going to take much longer if you don't supply a date.

When you start walletd after this, it'll synchronize with the blockchain. Make sure you delete the spvchain file first,
if it exists.

## Implemented Bitcoind RPC calls

Please refer to Bitcoind documentation for how to use these.
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

## TODO Bitcoind RPC calls
These RPC calls are on my list of things to implement when I've got the time or I actually need them.
```
listtransactions
listsinceblock
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

