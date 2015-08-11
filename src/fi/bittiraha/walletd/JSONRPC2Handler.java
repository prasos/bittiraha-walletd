package fi.bittiraha.walletd;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.thetransactioncompany.jsonrpc2.*;
import com.thetransactioncompany.jsonrpc2.server.*;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.Charsets;

import java.text.*;
import java.util.*;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;

class JSONRPC2Handler implements HttpHandler {
    private RequestHandler handler;
    private HttpServer server;
    public JSONRPC2Handler(int port, RequestHandler h) throws Exception {
      handler = h;
      server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("localhost"),port), 0);
      server.createContext("/", this);
      server.setExecutor(Executors.newCachedThreadPool()); // creates a default executor
      server.start();
    }
    public void handle(HttpExchange t) throws IOException {
      String response = "Internal Server Error";
      try {
        String req = IOUtils.toString(t.getRequestBody(),Charsets.UTF_8.name());
//          System.out.println("Request: " + req);

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register(handler);
            
        JSONRPC2Request jsonreq = JSONRPC2Request.parse(req,false,true);
        JSONRPC2Response resp = dispatcher.process(jsonreq, null);
            
        response = resp.toString();

        t.sendResponseHeaders(200, response.length());
      } catch(Exception e) {
        t.sendResponseHeaders(501, response.length());
        e.printStackTrace();
      }
      OutputStream os = t.getResponseBody();
      os.write(response.getBytes());
      os.close();
    }
}

