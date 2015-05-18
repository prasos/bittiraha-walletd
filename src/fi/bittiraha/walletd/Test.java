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

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.utils.BriefLogFormatter;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

import com.google.common.base.Joiner;
import java.io.File;
import static com.google.common.base.Preconditions.checkNotNull;

public class Test {
  private static WalletAccountManager kit;
  private static Map account;
  private static NetworkParameters params = TestNet3Params.get();
  private static String filePrefix = "testnet";
    
  public static class WalletRPC implements RequestHandler {
    public String[] handledRequests() {
      return new String[]{"getnewaddress","getaccountaddress","getbalance","sendtoaddress"};
    }
      
    private String sendtoaddress(Address target, Coin value) throws InsufficientMoneyException {
      Wallet.SendResult result = kit.wallet().sendCoins(Wallet.SendRequest.to(target,value));
      return result.tx.getHash().toString();
    }

    private BigDecimal getbalance() {
      BigDecimal satoshis = new BigDecimal(kit.wallet().getBalance().value);
      return new BigDecimal("0.00000001").multiply(satoshis);
    }

    public JSONRPC2Response process(JSONRPC2Request req, MessageContext ctx) {
      Object response = "dummy";
      List<Object> requestParams = req.getPositionalParams();
      String method = req.getMethod();
      try {
        if (method.equals("getnewaddress")) {
          response = kit.wallet().freshReceiveKey().toAddress(params).toString();
        } else if (method.equals("getaccountaddress")) {
          response = kit.wallet().freshReceiveKey().toAddress(params).toString();
        } else if (method.equals("getbalance")) {
          return new JSONRPC2Response(getbalance(),req.getID());
        } else if (method.equals("sendtoaddress")) {
            response = sendtoaddress(new Address(params, (String)requestParams.get(0)),
                                    Coin.parseCoin(requestParams.get(1).toString()));
        } else if (method.equals("sendmany")) {

        } else if (method.equals("sendfrom")) {

        } else {
          response = JSONRPC2Error.METHOD_NOT_FOUND;
        }
      } catch (InsufficientMoneyException e) {
        response = new JSONRPC2Error(-6,"Insufficient funds",e.getMessage());
      } catch (Exception e) { 
        e.printStackTrace();
        response = new JSONRPC2Error(-32602,"Invalid parameters",e.getMessage());
      }
      return new JSONRPC2Response(response,req.getID());
    }
  }

  public static void main(String[] args) throws Exception {
    JSONRPC2Handler jsonrpc = new JSONRPC2Handler(8080, new WalletRPC());

    kit = new WalletAccountManager(params, new File("."), filePrefix);
    
    kit.startAsync();
    kit.awaitRunning();
    
    account = kit.getAccountMap();
    
    System.out.println(filePrefix + " wallet running.");
  }
}
