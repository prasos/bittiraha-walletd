package fi.bittiraha.walletd;

import fi.bittiraha.walletd.JSONRPC2Handler;
import fi.bittiraha.walletd.WalletAccountManager;
import fi.bittiraha.walletd.BalanceCoinSelector;
import fi.bittiraha.walletd.ConfirmedCoinSelector;
import fi.bittiraha.util.ConfigFile;

import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;

import java.text.*;
import java.util.*;
import java.io.*;
import java.math.BigDecimal;

import com.thetransactioncompany.jsonrpc2.*;
import com.thetransactioncompany.jsonrpc2.server.*;
import net.minidev.json.*;

import org.bitcoinj.core.*;
import org.bitcoinj.store.*;
import org.bitcoinj.wallet.*;
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

import com.google.common.base.Joiner;
import static com.google.common.base.Preconditions.*;

import java.util.logging.Level;
import java.util.logging.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.xml.bind.DatatypeConverter;

public class WalletRPC extends Thread implements RequestHandler {
  private static final Logger log = LoggerFactory.getLogger(WalletRPC.class);
  private final NetworkParameters params;
  private String filePrefix;
  private int port;
  private WalletAccountManager kit;
  private Map account;
  private JSONRPC2Handler server;
  private Coin paytxfee;
  private CoinSelector sendSelector = new BalanceCoinSelector();
  private Transaction currentSend = null;
  private SettableFuture<Transaction> nextSend = SettableFuture.create();
  private List<TransactionOutput> queuedPaylist = new ArrayList<TransactionOutput>();
  private Transaction queuedTx = null;
  private final ReentrantLock sendlock = Threading.lock("sendqueue");

  private ConfigFile config = new ConfigFile();

  public WalletRPC(int port, String filePrefix, NetworkParameters params) throws IOException {
    BriefLogFormatter.init();
    this.filePrefix = filePrefix;
    this.params = params;
    this.port = port;
    this.paytxfee = Coin.parseCoin("0.00020011");
    try {
      config.load(new FileReader(filePrefix+".conf"));
    }
    catch (FileNotFoundException e) {
      log.info(filePrefix + ": config file "+filePrefix+".conf not found. Using defaults.");
    }
    config.defaultBoolean("start",true);
    config.defaultBoolean("sendUnconfirmedChange",true);
    //defaults.setProperty("trustedPeer","1.2.3.4");
  }

  public void run() {
    if (!config.getBoolean("start")) {
      log.info(filePrefix + ": disabled (start!=1). Not starting.");
      return;
    }
    if (!config.getBoolean("sendUnconfirmedChange")) {
      log.info(filePrefix + ": Will not send unconfirmed coins under any circumstances.");
      sendSelector = new ConfirmedCoinSelector();
    }

    try {
      log.info(filePrefix + ": wallet starting.");
      kit = new WalletAccountManager(params, new File("."), filePrefix);
  
      kit.startAsync();
      kit.awaitRunning();
      server = new JSONRPC2Handler(port, this);
    
      log.info(filePrefix + ": wallet running.");
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
      "sendfrom",
      "validateaddress",
      "settxfee",
      "listunspent"
    };
  }
    
  private String getnewaddress() {
    String address = kit.wallet().freshReceiveKey().toAddress(params).toString();
    log.info(filePrefix + ": new receiveaddress " + address);
    return address;
  }

  // Dang this function looks UGLY and overly verbose. It really should be doable in a couple of lines.
  private List<TransactionOutput> parsePaylist(Map<String,Object> paylist) throws AddressFormatException {
    List<TransactionOutput> result = new ArrayList<TransactionOutput>(paylist.size());
    Iterator<Map.Entry<String,Object>> entries = paylist.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String,Object> entry = entries.next();
      Coin value = Coin.parseCoin(entry.getValue().toString());
      String key = entry.getKey();
      if (key.toLowerCase().startsWith("0x")) {
	  // Parsing as hex encoded output script
	  try {
	      byte[] script = DatatypeConverter.parseHexBinary(key.substring(2));
	      result.add(new TransactionOutput(params,null,value,script));
	  } catch (IllegalArgumentException e) {
	      throw new AddressFormatException("Parsing target as script but is not hexadecimal");
	  }
      } else {
	  // Parsing as an ordinary bitcoin address
	  Address target = new Address(params, key);
	  result.add(new TransactionOutput(params,null,value,target));
      }
    }
    return result;
  }

  private Transaction newTransaction(List<TransactionOutput> paylist) {
    Transaction tx = new Transaction(params);
    for (TransactionOutput out : paylist) {
      tx.addOutput(out);
    }
    return tx;
  }
  
  private Coin sumCoins(List<TransactionOutput> paylist) {
    Coin sum = Coin.ZERO;
    for (TransactionOutput out : paylist) {
      sum = sum.add(out.getValue());
    }
    return sum;
  }
  
  private void prepareTx(List<TransactionOutput> paylist) throws InsufficientMoneyException {
    checkState(sendlock.isHeldByCurrentThread());
    log.info("preparing transaction");
    List<TransactionOutput> provisionalQueue = new ArrayList<TransactionOutput>(queuedPaylist);
    if (paylist != null) provisionalQueue.addAll(paylist);
    Transaction provisionalTx = newTransaction(provisionalQueue);
    Wallet.SendRequest req = Wallet.SendRequest.forTx(provisionalTx);
    req.feePerKb = paytxfee;
    req.coinSelector = sendSelector;
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
      queuedPaylist = new ArrayList<TransactionOutput>();
      nextSend = SettableFuture.create();
      return currentSend;
  }

  private class Sendinel implements TransactionConfidence.Listener {
          @Override
          public void onConfidenceChanged(TransactionConfidence confidence,
                                          TransactionConfidence.Listener.ChangeReason reason) {
              if (confidence.numBroadcastPeers() >= 1 ||
                  confidence.getConfidenceType().equals(TransactionConfidence.ConfidenceType.BUILDING)) {
                  confidence.removeEventListener(this);
                  if (currentSend == null) return;
                  if (confidence.getTransactionHash().equals(currentSend.getHash())) {
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
                        queuedPaylist = new ArrayList<TransactionOutput>();
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
  
  }

  private String sendmany(Map<String,Object> _paylist)
  throws InsufficientMoneyException, AddressFormatException,
         InterruptedException,ExecutionException {
    List<TransactionOutput> paylist = parsePaylist(_paylist);
    log.info("Received send request");
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
      sendlock.lock();
      try {
        nextSend.setException(e);
        nextSend = SettableFuture.create();
        queuedPaylist = new ArrayList<TransactionOutput>();
        queuedTx = null;
        throw new ExecutionException(e);
      } finally {
        sendlock.unlock();
      }
    }
  }

  private Coin getcoinbalance() {
    return kit.wallet().getBalance(sendSelector).subtract(sumCoins(queuedPaylist));
  }

  public static BigDecimal coin2BigDecimal(Coin input) {
    BigDecimal satoshis = new BigDecimal(input.value);
    return new BigDecimal("0.00000001").multiply(satoshis);
  }

  private BigDecimal getbalance() {
    return coin2BigDecimal(getcoinbalance());
  }

  private BigDecimal getunconfirmedbalance() {
    Coin balance = kit.wallet().getBalance(Wallet.BalanceType.ESTIMATED).subtract(sumCoins(queuedPaylist));
    return coin2BigDecimal(balance);
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

  public static String txoutScript2String(NetworkParameters params, TransactionOutput out) {
    Address addr;
    addr = out.getAddressFromP2PKHScript(params);
    if (addr != null) return addr.toString();
    addr = out.getAddressFromP2SH(params);
    if (addr != null) return addr.toString();
    return "UNKNOWN";
  }

  private Object listunspent(long minconf, long maxconf, JSONArray filter) {
    List<String> addresses = new ArrayList<String>(filter.size());
    for (Object item : filter) addresses.add((String) item);
    JSONArray reply = new JSONArray();
    List<TransactionOutput> unspent = kit.wallet().calculateAllSpendCandidates(true, true);
    for (TransactionOutput out : unspent) {
      int depth = out.getParentTransaction().getConfidence().getDepthInBlocks();
      String addr = txoutScript2String(params,out);
      if (minconf <= depth && depth <= maxconf && (addresses.size() == 0 || addresses.contains(addr))) {
        JSONObject coin = new JSONObject();
        TransactionOutPoint outpoint = out.getOutPointFor();
        coin.put("txid", outpoint.getHash().toString());
        coin.put("vout",outpoint.getIndex());
        coin.put("address",addr);
        coin.put("scriptPubKey", DatatypeConverter.printHexBinary(out.getScriptPubKey().getProgram()));
        coin.put("amount", coin2BigDecimal(out.getValue()));
        coin.put("confirmations",depth);
        reply.add(coin);
      }
    }
    return reply;
  }

  private boolean settxfee(String fee) {
    paytxfee = Coin.parseCoin(fee);
    return true;
  }

  public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
    Object response = "dummy";
    List<Object> rp = req.getPositionalParams();
    String method = req.getMethod();
    JSONObject paylist = new JSONObject();
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
          paylist.put((String)rp.get(0),rp.get(1));
          response = sendmany(paylist);
          break;
        case "sendfrom":
          paylist.put((String)rp.get(1),rp.get(2));
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
        case "settxfee":
          response = settxfee(rp.get(0).toString());
          break;
        case "listunspent":
          long minconf = 1;
          long maxconf = 9999999;
          JSONArray filter = new JSONArray();
          switch (rp.size()) {
            case 3:
              filter = (JSONArray)JSONValue.parse((String)rp.get(2));
            case 2:
              maxconf = (long)rp.get(1);
            case 1:
              minconf = (long)rp.get(0);
            case 0:
              break;
            default:
              throw new Exception("Invalid number of parameters");
          }
          response = listunspent(minconf,maxconf,filter);
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
