package fi.bittiraha.walletd;

import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import net.minidev.json.*;
import com.google.common.collect.ImmutableList;
import java.util.*;
import java.io.File;

/**
 * This class extends WalletAppKit to add ability to tag individual addresses
 * with account names to emulate bitcoind's accounts. However, emulation in
 * this version is incomplete and only useful in searching for incoming txs.
 */
public class WalletAccountManager extends WalletAppKit {
  AccountManager manager;
  
  public WalletAccountManager(NetworkParameters params, File directory, String filePrefix) {
    super(params,directory,filePrefix);
    manager = new AccountManager();
  }

  protected class AccountManager extends JSONObject implements WalletExtension {
    public void deserializeWalletExtension(Wallet containingWallet, byte[] data) {
      Object parsed = JSONValue.parse(data);
      if (parsed instanceof JSONObject) {
        this.merge((JSONObject)parsed);
      } 
    }
    public String getWalletExtensionID() {
      return "fi.bittiraha.walletd.WalletAccountManager";
    }
    public boolean isWalletExtensionMandatory() {
      return true;
    }
    public byte[] serializeWalletExtension() {
      return this.toJSONString(JSONStyle.MAX_COMPRESS).getBytes();
    }
  }

  protected List<WalletExtension> provideWalletExtensions() throws Exception {
    return ImmutableList.of((WalletExtension)manager);
  }
  
  public Map<String,Object> getAccountMap() {
    return manager;
  }
}
