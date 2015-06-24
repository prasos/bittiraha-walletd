package fi.bittiraha.walletd;

import fi.bittiraha.walletd.JSONRPC2Handler;
import fi.bittiraha.walletd.WalletAccountManager;

import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;

import java.text.*;
import java.util.*;
import java.math.BigDecimal;

import com.thetransactioncompany.jsonrpc2.*;
import com.thetransactioncompany.jsonrpc2.server.*;
import net.minidev.json.*;

import org.bitcoinj.core.*;
import org.bitcoinj.store.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.utils.BriefLogFormatter;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

import com.google.common.base.Joiner;
import java.io.File;
import static com.google.common.base.Preconditions.checkNotNull;

public class WalletRPC extends Thread implements RequestHandler {
  private NetworkParameters params;
  private String filePrefix;
  private int port;
  private WalletAccountManager kit;
  private Map account;
  private JSONRPC2Handler server;
  private Coin paytxfee;

  public WalletRPC(int port, String filePrefix, NetworkParameters params) {
    this.filePrefix = filePrefix;
    this.params = params;
    this.port = port;
    this.paytxfee = Coin.parseCoin("0.00020011");
  }

  public void run() {
    try {
      System.out.println(filePrefix + " wallet starting.");
      server = new JSONRPC2Handler(port, this);
      kit = new WalletAccountManager(params, new File("."), filePrefix);
  
      kit.startAsync();
      kit.awaitRunning();
    
      System.out.println(filePrefix + " wallet running.");
    }
    catch (Exception e) {
      e.printStackTrace();
    }
  }

  public String[] handledRequests() {
    return new String[]{
      "getinfo",
      "getnewaddress",
      "getaccountaddress",
      "getunconfirmedbalance",
      "getbalance",
      "sendtoaddress",
      "sendmany",
      "validateaddress"};
  }
    
  private String getnewaddress() {
    return kit.wallet().freshReceiveKey().toAddress(params).toString();
  }


  private String sendmany(Map<String,Object> paylist) throws InsufficientMoneyException, AddressFormatException {
    Transaction tx = new Transaction(params);
    Iterator<Map.Entry<String,Object>> entries = paylist.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String,Object> entry = entries.next();
      Address target = new Address(params, entry.getKey());
      Coin value = Coin.parseCoin(entry.getValue().toString());
      tx.addOutput(value,target);
    }
    Wallet.SendRequest req = Wallet.SendRequest.forTx(tx);
    req.feePerKb = paytxfee;
    Wallet.SendResult result = kit.wallet().sendCoins(req);
    return result.tx.getHash().toString();
  }

  private String sendtoaddress(String address, String amount) throws InsufficientMoneyException, AddressFormatException {
    Address target = new Address(params, address);
    Coin value = Coin.parseCoin(amount);
    Wallet.SendRequest req = Wallet.SendRequest.to(target,value);
    req.feePerKb = paytxfee;
    Wallet.SendResult result = kit.wallet().sendCoins(req);
    return result.tx.getHash().toString();
  }

  private BigDecimal getbalance() {
    BigDecimal satoshis = new BigDecimal(kit.wallet().getBalance().value);
    return new BigDecimal("0.00000001").multiply(satoshis);
  }

  private BigDecimal getunconfirmedbalance() {
    BigDecimal satoshis = new BigDecimal(kit.wallet().getBalance(Wallet.BalanceType.ESTIMATED).value);
    return new BigDecimal("0.00000001").multiply(satoshis);
  }

  private Object validateaddress(String address) {
    JSONObject result = new JSONObject();
    try {
      Address validated = new Address(params,address);
      result.put("isvalid",true);
      result.put("address",validated.toString());
      List<Address> addresses = kit.wallet().getIssuedReceiveAddresses();
      result.put("ismine",addresses.contains(validated));
    } catch (AddressFormatException e) {
      result.put("isvalid",false);
    } 
    return result;
  }

  private Object getinfo() throws BlockStoreException {
    JSONObject info = new JSONObject();
    StoredBlock chainHead = kit.store().getChainHead();
//      info.put("version",null);
//      info.put("protocolversion",null);
//      info.put("walletversion",null);
    info.put("balance",getbalance());
    info.put("blocks",chainHead.getHeight());
//      info.put("timeoffset",null);
    info.put("connections",kit.peerGroup().numConnectedPeers());
    info.put("difficulty",chainHead.getHeader().getDifficultyTarget());
    info.put("testnet",params != MainNetParams.get());
//      info.put("keypoololdest",null);
//      info.put("keypoolsize",null);
      info.put("paytxfee",paytxfee.toPlainString());
//      info.put("relayfee",null);
    info.put("errors","");
    return info;
  }

  public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
    Object response = "dummy";
    List<Object> requestParams = req.getPositionalParams();
    String method = req.getMethod();
    try {
      if (method.equals("getnewaddress")) {
        response = getnewaddress();
      } else if (method.equals("getaccountaddress")) {
        response = getnewaddress();
      } else if (method.equals("getbalance")) {
        response = getbalance();
      } else if (method.equals("getunconfirmedbalance")) {
        response = getunconfirmedbalance();
      } else if (method.equals("sendtoaddress")) {
        response = sendtoaddress((String)requestParams.get(0),requestParams.get(1).toString());
      } else if (method.equals("sendmany")) {
        response = sendmany((JSONObject)JSONValue.parse((String)requestParams.get(0)));
      } else if (method.equals("sendfrom")) {

      } else if (method.equals("validateaddress")) {
        response = validateaddress((String)requestParams.get(0));
      } else if (method.equals("getinfo")) {
        response = getinfo();
      } else {
        response = JSONRPC2Error.METHOD_NOT_FOUND;
      }
    } catch (InsufficientMoneyException e) {
      JSONRPC2Error error = new JSONRPC2Error(-6,"Insufficient funds",e.getMessage());
      return new JSONRPC2Response(error,req.getID());
    } catch (AddressFormatException e) {
      JSONRPC2Error error = new JSONRPC2Error(-5,"Invalid Bitcoin address",e.getMessage());
      return new JSONRPC2Response(error,req.getID());
    } catch (Exception e) { 
      e.printStackTrace();
      JSONRPC2Error error = new JSONRPC2Error(-32602,"Invalid parameters",e.getMessage());
      return new JSONRPC2Response(error,req.getID());
    }
    return new JSONRPC2Response(response,req.getID());
  }
}
