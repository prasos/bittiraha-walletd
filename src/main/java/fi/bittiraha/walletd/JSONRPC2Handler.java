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

import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.Charsets;

import java.text.*;
import java.util.*;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.lang.Runtime;

class JSONRPC2Handler implements HttpHandler {
    private RequestHandler handler;
    private HttpServer server;
    public JSONRPC2Handler(String hostName, int port, RequestHandler h) throws Exception {
      int cores = Runtime.getRuntime().availableProcessors();
      handler = h;
      server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(hostName),port), 0);
      server.createContext("/", this);
      server.setExecutor(Executors.newFixedThreadPool(cores));
      server.start();
    }

    private static String ensureRequestHasID(String req) {
        JSONObject parsed = (JSONObject)JSONValue.parse(req);
        if (parsed.containsKey("id")) return req;
        else {
            parsed.put("id","");
            return parsed.toJSONString();
        }
    }

    public void handle(HttpExchange t) throws IOException {
      String response = "Internal Server Error";
      try {
        String req = IOUtils.toString(t.getRequestBody(),Charsets.UTF_8.name());
//          System.out.println("Request: " + req);

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register(handler);
            
        JSONRPC2Request jsonreq = JSONRPC2Request.parse(ensureRequestHasID(req),false,true);
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

