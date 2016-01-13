package fi.bittiraha.walletd;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import com.thetransactioncompany.jsonrpc2.server.RequestHandler;
import fi.bittiraha.util.ConfigFile;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.CoinSelection;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.KeyChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkState;

public class WalletRPC extends Thread implements RequestHandler {
  private static final Logger log = LoggerFactory.getLogger(WalletRPC.class);
  private final NetworkParameters params;
  private String filePrefix;
  private int port;
  private WalletApp kit;
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
    config.defaultInteger("targetCoinCount",8);
    config.defaultBigDecimal("targetCoinAmount", new BigDecimal("0.5"));
    config.defaultInteger("port",port);

    config.defaultBoolean("randomizeChangeOutputs", false);

    this.port = config.getInteger("port");

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
      kit = new WalletApp(params, new File("."), filePrefix);

      kit.startAsync();
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
      "sendonce",
      "validateaddress",
      "settxfee",
      "listunspent",
      "estimate_fee"
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
    for (Map.Entry<String, Object> entry : paylist.entrySet()) {
      Coin value = Coin.parseCoin(entry.getValue().toString());
      String key = entry.getKey();
      if (key.toLowerCase().startsWith("0x")) {
        // Parsing as hex encoded output script
        try {
          byte[] script = DatatypeConverter.parseHexBinary(key.substring(2));
          result.add(new TransactionOutput(params, null, value, script));
        } catch (IllegalArgumentException e) {
          throw new AddressFormatException("Parsing target as script but is not hexadecimal");
        }
      } else {
        // Parsing as an ordinary bitcoin address
        Address target = new Address(params, key);
        result.add(new TransactionOutput(params, null, value, target));
      }
    }
    return result;
  }

  private long getConfirmedCoinCount() {
    BigDecimal count = new BigDecimal(0);
    BigDecimal target = config.getBigDecimal("targetCoinAmount");
    for (TransactionOutput coin : kit.wallet().calculateAllSpendCandidates(false,false)) {
      if (coin.getParentTransactionDepthInBlocks() > 0) {
        count = count.add(target.min(coin2BigDecimal(coin.getValue())).divide(target,8,BigDecimal.ROUND_HALF_UP));
      }
    }
    return count.setScale(0,BigDecimal.ROUND_FLOOR).longValue();
  }
  private Transaction newTransaction(List<TransactionOutput> paylist) {
    Transaction tx = new Transaction(params);
    Coin totalOut = Coin.ZERO;
    for (TransactionOutput out : paylist) {
      tx.addOutput(out);
      totalOut = totalOut.add(out.getValue());
    }
    CoinSelection inputs = sendSelector.select(totalOut,kit.wallet().calculateAllSpendCandidates(false,false));
    Coin totalIn = Coin.ZERO;
    for (TransactionOutput in : inputs.gathered) {
      tx.addInput(in);
      totalIn = totalIn.add(in.getValue());
    }
    Coin change = totalIn.subtract(totalOut);
    Coin target = Coin.parseCoin(config.getBigDecimal("targetCoinAmount").toString());

    long pieces = change.divide(target);
    long extraChange = Math.min(pieces, (long) config.getInteger("targetCoinCount") - getConfirmedCoinCount());
    if (config.getBoolean("randomizeChangeOutputs"))
    {
      log.info("Adding random change outputs, change " + change.toFriendlyString() + ", target " + target.toFriendlyString() );
      // add some extra change addresses
      while (target.compareTo(change) <= 0) {
        double changeLeft = ((double)(change.subtract(target).longValue())) * 0.00000001;
        double fee = ((double)(paytxfee.longValue())) * 0.00000001;
        double min = Math.max(config.getBigDecimal("targetCoinAmount").doubleValue() * 0.25, 0.01);
        double max = Math.min(config.getBigDecimal("targetCoinAmount").doubleValue() * 2.0, changeLeft);
        if (max <= min) { break; }
        double randomOutput = min + (max - min) * Math.random();
        if (randomOutput < fee * 10.0) { log.info("Too small random output " + Double.toString(randomOutput)); break; }
        // Convert to satoshis
        Coin extraChangeAmount = Coin.valueOf((long)(randomOutput*100000000));
        tx.addOutput(extraChangeAmount,kit.wallet().freshAddress(KeyChain.KeyPurpose.CHANGE));
        log.info("Added " + extraChangeAmount.toFriendlyString() + " extra randomized output, change left " + Double.toString(changeLeft));
        target = target.add(extraChangeAmount);
      }
    }
    else
    {
      if (extraChange > 0) {
        Coin extraChangeAmount = change.divide(extraChange + 1);
        for (int i=0;i<extraChange;i++) {
          tx.addOutput(extraChangeAmount,kit.wallet().freshAddress(KeyChain.KeyPurpose.CHANGE));
        }
        log.info("Added " + extraChange + " extra change outputs of " + extraChangeAmount.toFriendlyString() + " each.");
      }
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
// disable queueing for now, it's unreliable
//    queuedPaylist = provisionalQueue;
    queuedTx = provisionalTx;
  }

  private Transaction reallySend() {
      checkState(sendlock.isHeldByCurrentThread());
      log.info("sending transaction " + queuedTx.getHash().toString());
      kit.wallet().commitTx(queuedTx);
      kit.peerGroup().broadcastTransaction(queuedTx);
      return queuedTx;
// disable queueing for now, it's unreliable
//      queuedTx.getConfidence().addEventListener(new Sendinel());
//      nextSend.set(queuedTx);
//      currentSend = queuedTx;
//      queuedTx = null;
//      queuedPaylist = new ArrayList<TransactionOutput>();
//      nextSend = SettableFuture.create();
//      return nextSend;
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

  private String sendonce(Map<String,Object> paylist, String identifier)
    throws InsufficientMoneyException, AddressFormatException,
            InterruptedException,ExecutionException {
    sendlock.lock();
    if (kit.sendonceMap.containsKey(identifier)) {
      sendlock.unlock();
      log.info("sendonce call for used identifier " + identifier +
               ". Returning the old txid " + kit.sendonceMap.get(identifier));
      return kit.sendonceMap.get(identifier);
    } else {
      String txid = sendmany(paylist);
      kit.sendonceMap.put(identifier,txid);
      sendlock.unlock();
      log.info("sendonce call with new identifier. Sent tx " + txid);
      return txid;
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
      Transaction parent = out.getParentTransaction();
      int depth = -1;
      if (parent != null) depth = parent.getConfidence().getDepthInBlocks();
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
    if (!kit.isRunning()) {
      JSONRPC2Error error = new JSONRPC2Error(-28,"Initializing...");
      return new JSONRPC2Response(error,req.getID());
    }
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
        case "sendonce":
          Object rp1 = rp.get(1);
          if (String.class.isInstance(rp1)) {
            // This is here to allow sendonce to work with bitcoin-cli in the same manner as sendmany
            response = sendonce((JSONObject)JSONValue.parse((String)rp1), (String) rp.get(0));
          } else { // Please call it this way though.
            response = sendonce((JSONObject)rp1, (String) rp.get(0));
          }
          break;
        case "sendmany":
          response = sendmany((JSONObject)rp.get(1));
          break;
        case "validateaddress":
          response = validateaddress((String)rp.get(0));
          break;
        case "estimate_fee":
          // recommended not to use this function, but if used, try to return something sensible
          if ((long)rp.get(0) < 3L) { response = "0.00052186"; }
          else if ((long)rp.get(0) < 6L) { response = "0.00018234"; }
          else { response = "0.00017992"; }
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
      JSONRPC2Error error = new JSONRPC2Error(-6,"Insufficient funds");
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
