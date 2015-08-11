package fi.bittiraha.walletd;

import fi.bittiraha.walletd.JSONRPC2Handler;
import fi.bittiraha.walletd.WalletAccountManager;
import fi.bittiraha.walletd.BalanceCoinSelector;

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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.bitcoinj.utils.Threading;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.*;

import org.apache.commons.lang3.tuple.*;
import com.google.common.base.Joiner;
import java.io.File;
import static com.google.common.base.Preconditions.*;

import java.util.logging.Level;
import java.util.logging.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WalletRPC extends Thread implements RequestHandler {
  private static final Logger log = LoggerFactory.getLogger(WalletRPC.class);
  private final NetworkParameters params;
  private String filePrefix;
  private int port;
  private WalletAccountManager kit;
  private Map account;
  private JSONRPC2Handler server;
  private Coin paytxfee;
  private Transaction currentSend = null;
  private SettableFuture<Transaction> nextSend = SettableFuture.create();
  private List<Pair<Address,Coin>> queuedPaylist = new ArrayList<Pair<Address,Coin>>();
  private Transaction queuedTx = null;
  private final ReentrantLock sendlock = Threading.lock("sendqueue");
  
  public WalletRPC(int port, String filePrefix, NetworkParameters params) {
    BriefLogFormatter.init();
    this.filePrefix = filePrefix;
    this.params = params;
    this.port = port;
    this.paytxfee = Coin.parseCoin("0.00020011");
  }

  public void run() {
    try {
      log.info(filePrefix + " wallet starting.");
      kit = new WalletAccountManager(params, new File("."), filePrefix);
  
      kit.startAsync();
      kit.awaitRunning();
      server = new JSONRPC2Handler(port, this);
    
      log.info(filePrefix + " wallet running.");
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
    String address = kit.wallet().freshReceiveKey().toAddress(params).toString();
    log.info(filePrefix + ": new receiveaddress " + address);
    return address;
  }

  // Dang this function looks UGLY and overly verbose. It really should be doable in a couple of lines.
  private List<Pair<Address,Coin>> parsePaylist(Map<String,Object> paylist) throws AddressFormatException {
    List<Pair<Address,Coin>> result = new ArrayList<Pair<Address,Coin>>(paylist.size());
    Iterator<Map.Entry<String,Object>> entries = paylist.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String,Object> entry = entries.next();
      Address target = new Address(params, entry.getKey());
      Coin value = Coin.parseCoin(entry.getValue().toString());
      result.add(new ImmutablePair<Address,Coin>(target,value));
    }
    return result;
  }

  private Transaction newTransaction(List<Pair<Address,Coin>> paylist) {
    Transaction tx = new Transaction(params);
    for (Pair<Address,Coin> pair : paylist) {
      tx.addOutput(pair.getRight(), pair.getLeft());
    }
    return tx;
  }
  
  private Coin sumCoins(List<Pair<Address,Coin>> paylist) {
    Coin sum = Coin.ZERO;
    for (Pair<Address,Coin> pair : paylist) {
      sum = sum.add(pair.getRight());
    }
    return sum;
  }
  
  private void prepareTx(List<Pair<Address,Coin>> paylist) throws InsufficientMoneyException {
    checkState(sendlock.isHeldByCurrentThread());
    log.info("preparing transaction");
    List<Pair<Address,Coin>> provisionalQueue = new ArrayList<Pair<Address,Coin>>(queuedPaylist);
    if (paylist != null) provisionalQueue.addAll(paylist);
    Transaction provisionalTx = newTransaction(provisionalQueue);
    Wallet.SendRequest req = Wallet.SendRequest.forTx(provisionalTx);
    req.feePerKb = paytxfee;
    req.coinSelector = new BalanceCoinSelector();
    // This ensures we have enough balance. Throws InsufficientMoneyException otherwise.
    // Does not actually mark anything as spent yet.
    kit.wallet().completeTx(req); 
    queuedPaylist = provisionalQueue;
    queuedTx = provisionalTx;
  }

  private Transaction reallySend() {
      checkState(sendlock.isHeldByCurrentThread());
      log.info("sending transaction " + queuedTx.getHash().toString());
      kit.wallet().commitTx(queuedTx);
      kit.peerGroup().broadcastTransaction(queuedTx);
      queuedTx.getConfidence().addEventListener(new Sendinel());
      nextSend.set(queuedTx);
      currentSend = queuedTx;
      queuedTx = null;
      queuedPaylist = new ArrayList<Pair<Address,Coin>>();
      nextSend = SettableFuture.create();
      return currentSend;
  }

  private class Sendinel implements TransactionConfidence.Listener {
          @Override
          public void onConfidenceChanged(TransactionConfidence confidence,
                                          TransactionConfidence.Listener.ChangeReason reason) {
              if (currentSend == null) return;
              if (confidence.getTransactionHash().equals(currentSend.getHash()) &&
                  confidence.numBroadcastPeers() >= 1)
              {
                  log.info("Done with " + confidence.getTransactionHash().toString());
                  sendlock.lock();
                  try {
                    currentSend = null;
                    if (queuedPaylist.size() > 0) {
                      prepareTx(null);
                      reallySend();
                    }
                  } catch (Exception e) {
                    log.info("Got exception:" + e.toString());
                    nextSend.setException(e);
                    nextSend = SettableFuture.create();
                    queuedPaylist = new ArrayList<Pair<Address,Coin>>();
                    queuedTx = null;
                  } finally {
                    sendlock.unlock();
                  }
              } else {
                  log.info("Ignored " + confidence.getTransactionHash().toString() +
                           " currentSend: " + currentSend.getHash().toString() +
                           " comparison: " + (confidence.getTransactionHash() == currentSend.getHash()) +
                           " " + confidence.numBroadcastPeers() +
                           " " + reason.toString()
                  );
              }
          }
  
  }

  private String sendmany(Map<String,Object> _paylist)
  throws InsufficientMoneyException, AddressFormatException,
         InterruptedException,ExecutionException {
    List<Pair<Address,Coin>> paylist = parsePaylist(_paylist);
    log.info("Received sendmany request");
    sendlock.lock();
    try {
      prepareTx(paylist);
      if (currentSend == null) return reallySend().getHash().toString();
      else log.info("Send " + currentSend.getHash().toString() + " in progress, waiting for resolution...");
    } finally {
      sendlock.unlock();
    }
    ListenableFuture<Transaction> future = nextSend;
    try {
      Transaction next = future.get(25L,TimeUnit.SECONDS);
      return next.getHash().toString();
    } catch (TimeoutException e) {
      // FIXME: This might do unexpected things sometimes. Improve timeout handling.
      sendlock.lock();
      try {
        nextSend.setException(e);
        nextSend = SettableFuture.create();
        queuedPaylist = new ArrayList<Pair<Address,Coin>>();
        queuedTx = null;
        throw new ExecutionException(e);
      } finally {
        sendlock.unlock();
      }
    }
  }

  private Coin getcoinbalance() {
    return kit.wallet().getBalance(new BalanceCoinSelector()).subtract(sumCoins(queuedPaylist));
  }

  private BigDecimal getbalance() {
    BigDecimal satoshis = new BigDecimal(getcoinbalance().value);
    return new BigDecimal("0.00000001").multiply(satoshis);
  }

  private BigDecimal getunconfirmedbalance() {
    Coin balance = kit.wallet().getBalance(Wallet.BalanceType.ESTIMATED).subtract(sumCoins(queuedPaylist));
    BigDecimal satoshis = new BigDecimal(balance.value);
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
    List<Object> rp = req.getPositionalParams();
    String method = req.getMethod();
    try {
      switch (method) {
        case "getnewaddress":
        case "getaccountaddress":
          response = getnewaddress();
          break;
        case "getbalance":
          response = getbalance();
          break;
        case "getunconfirmedbalance":
          response = getunconfirmedbalance();
          break;
        case "sendtoaddress":
          JSONObject paylist = new JSONObject();
          paylist.put((String)rp.get(0),rp.get(1));
          response = sendmany(paylist);
          break;
        case "sendmany":
          response = sendmany((JSONObject)JSONValue.parse((String)rp.get(0)));
          break;
        case "validateaddress":
          response = validateaddress((String)rp.get(0));
          break;
        case "getinfo":
          response = getinfo();
          break;
        default:
          response = JSONRPC2Error.METHOD_NOT_FOUND;
          break;
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
