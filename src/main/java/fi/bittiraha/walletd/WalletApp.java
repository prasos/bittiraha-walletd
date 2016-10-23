package fi.bittiraha.walletd;

import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import net.minidev.json.*;
import com.google.common.collect.ImmutableList;
import org.bitcoinj.utils.BriefLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.io.File;

/**
 * This class extends WalletAppKit to add some extra data to the wallet file.
 * For the moment it stores an identifier to txid map for the RPC extension
 * sendonce.
 */
public class WalletApp extends WalletAppKit {
  private static final Logger log = LoggerFactory.getLogger(WalletApp.class);
  AccountManager manager;
  public Map<String,String> sendonceMap;

  public WalletApp(NetworkParameters params, File directory, String filePrefix) {
    super(params,directory,filePrefix);
    BriefLogFormatter.init();
    manager = new AccountManager();
    sendonceMap = Collections.checkedMap(new HashMap<String,String>(),String.class,String.class);
  }

  protected class AccountManager extends JSONObject implements WalletExtension {
    public void deserializeWalletExtension(Wallet containingWallet, byte[] data) {
      Object parsed = JSONValue.parse(data);
      if (parsed instanceof JSONObject) {
        this.merge(parsed);
      } else {
        log.warn("Unable to decode AccountManager Extension data.");
      }
      try {
        sendonceMap.putAll((Map) this.get("sendonceMap"));
      } catch (ClassCastException e) {
        throw new ClassCastException("sendonceMap is corrupt. Please rebuild the wallet.");
      }
    }
    public String getWalletExtensionID() {
      return "fi.bittiraha.walletd.WalletApp";
    }
    public boolean isWalletExtensionMandatory() {
      // FIXME, set this to true when this module actually does something
      return false;
    }
    public byte[] serializeWalletExtension() {
      log.info("Serializing Extension data...");
      this.put("sendonceMap",sendonceMap);
      return this.toJSONString(JSONStyle.MAX_COMPRESS).getBytes();
    }
  }

  @Override
  protected List<WalletExtension> provideWalletExtensions() throws Exception {
    return ImmutableList.of((WalletExtension)manager);
  }

  @Override
  protected void onSetupCompleted() {
    this.wallet().addOrUpdateExtension(manager);
  }
  
  public Map<String,Object> getAccountMap() {
    return manager;
  }

}
