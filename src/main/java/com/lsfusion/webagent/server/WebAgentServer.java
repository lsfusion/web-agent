package com.lsfusion.webagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsfusion.webagent.command.CommandDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebAgentServer {

    private static final Logger LOG = Logger.getLogger(WebAgentServer.class.getName());
    private static final String VERSION = "0.1.0";

    private final String host;
    private final int port;
    private final CommandDispatcher dispatcher;
    private final List<String> allowedOrigins;
    private final String token;

    private HttpServer server;

    public WebAgentServer(String host, int port, CommandDispatcher dispatcher,
                          List<String> allowedOrigins, String token) {
        this.host = host;
        this.port = port;
        this.dispatcher = dispatcher;
        this.allowedOrigins = allowedOrigins == null ? Collections.singletonList("*") : allowedOrigins;
        this.token = token;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/ping", new PingHandler());
        server.createContext("/execute", new ExecuteHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        LOG.info("web-agent " + VERSION + " listening on http://" + host + ":" + port);
        LOG.info("commands: " + dispatcher.commands());
        if (token == null) {
            LOG.warning("no auth token configured — running in OPEN mode (dev only)");
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void writeCors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String allow = pickAllowedOrigin(origin);
        if (allow != null) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", allow);
            ex.getResponseHeaders().add("Vary", "Origin");
        }
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-WebAgent-Token");
        ex.getResponseHeaders().add("Access-Control-Max-Age", "600");
    }

    private String pickAllowedOrigin(String origin) {
        if (allowedOrigins.contains("*")) return "*";
        if (origin != null && allowedOrigins.contains(origin)) return origin;
        return null;
    }

    private boolean isOriginAllowed(HttpExchange ex) {
        if (allowedOrigins.contains("*")) return true;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return origin != null && allowedOrigins.contains(origin);
    }

    private boolean isTokenValid(HttpExchange ex) {
        if (token == null) return true;
        String supplied = ex.getRequestHeaders().getFirst("X-WebAgent-Token");
        return token.equals(supplied);
    }

    private static void writeJson(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private final class PingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                writeCors(ex);
                if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                ObjectNode body = Json.obj();
                body.put("name", "web-agent");
                body.put("version", VERSION);
                body.put("authRequired", token != null);
                body.set("commands", Json.MAPPER.valueToTree(dispatcher.commands()));
                writeJson(ex, 200, body);
            } finally {
                ex.close();
            }
        }
    }

    private final class ExecuteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                writeCors(ex);
                String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
                if ("OPTIONS".equals(method)) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                if (!"POST".equals(method)) {
                    writeJson(ex, 405, Json.error("Method not allowed"));
                    return;
                }
                if (!isOriginAllowed(ex)) {
                    writeJson(ex, 403, Json.error("Origin not allowed"));
                    return;
                }
                if (!isTokenValid(ex)) {
                    writeJson(ex, 401, Json.error("Invalid or missing token"));
                    return;
                }

                JsonNode req;
                try (InputStream is = ex.getRequestBody()) {
                    req = Json.MAPPER.readTree(is);
                }
                String command = Json.optString(req.path("command"));
                JsonNode arguments = req.path("arguments");
                String id = Json.optString(req.path("id"));

                if (command == null || command.isEmpty()) {
                    writeJson(ex, 400, Json.error("Missing 'command'"));
                    return;
                }

                ObjectNode response = Json.obj();
                if (id != null) response.put("id", id);
                try {
                    JsonNode result = dispatcher.dispatch(command, arguments);
                    response.setAll((ObjectNode) result);
                    writeJson(ex, 200, response);
                } catch (CommandDispatcher.UnknownCommandException unknown) {
                    response.put("error", unknown.getMessage());
                    writeJson(ex, 404, response);
                } catch (Exception failure) {
                    LOG.log(Level.WARNING, "command '" + command + "' failed", failure);
                    response.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    writeJson(ex, 200, response);
                }
            } catch (Exception fatal) {
                LOG.log(Level.SEVERE, "/execute crashed", fatal);
                byte[] msg = ("{\"error\":\"internal: " + fatal.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, msg.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            } finally {
                ex.close();
            }
        }
    }
}
