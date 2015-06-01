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
    
  public static class WalletRPC extends Thread implements RequestHandler {
    private NetworkParameters params;
    private String filePrefix;
    private int port;
    private WalletAccountManager kit;
    private Map account;
    private JSONRPC2Handler server;
    
    public WalletRPC(int port, String filePrefix, NetworkParameters params) {
      this.filePrefix = filePrefix;
      this.params = params;
      this.port = port;
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

  public static void main(String[] args) throws Exception {
    (new WalletRPC(18332,"testnet",TestNet3Params.get())).start();
    (new WalletRPC(8332,"mainnet",MainNetParams.get())).start();
  }
}
