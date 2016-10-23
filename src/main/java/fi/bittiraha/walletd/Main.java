package fi.bittiraha.walletd;

import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.params.MainNetParams;
import fi.bittiraha.util.ConfigFile;
import fi.bittiraha.walletd.WalletRPC;

public class Main {
  public static void main(String[] args) throws Exception {
    (new WalletRPC(18332,"testnet",TestNet3Params.get())).start();
    (new WalletRPC(8332,"mainnet",MainNetParams.get())).start();
  }
}
