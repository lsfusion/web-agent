package com.lsfusion.webagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebAgentServer {

    private static final Logger LOG = Logger.getLogger(WebAgentServer.class.getName());
    // Stamped into the jar manifest from pom.xml by the build; no manifest when
    // running from exploded classes (IDE), hence the "dev" fallback.
    private static final String VERSION = Objects.requireNonNullElse(
            WebAgentServer.class.getPackage().getImplementationVersion(), "dev");

    private final String host;
    private final int port;
    private final List<String> allowedOrigins;
    private final String token;

    private HttpServer server;

    public WebAgentServer(String host, int port, List<String> allowedOrigins, String token) {
        this.host = host;
        this.port = port;
        this.allowedOrigins = allowedOrigins == null ? List.of("*") : allowedOrigins;
        this.token = token;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        register("/ping", this::handlePing);
        register("/execute", this::handleExecute);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        LOG.info("web-agent " + VERSION + " listening on http://" + host + ":" + port);
        if (token == null) LOG.warning("no auth token configured — running in OPEN mode (dev only)");
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ---- registration ----

    @FunctionalInterface
    private interface Endpoint {
        void handle(HttpExchange ex) throws Exception;
    }

    // Common wrapper: CORS headers, OPTIONS preflight, fatal-catch, always-close.
    private void register(String path, Endpoint endpoint) {
        HttpHandler wrapped = ex -> {
            try (ex) {
                writeCors(ex);
                if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                endpoint.handle(ex);
            } catch (Exception fatal) {
                LOG.log(Level.SEVERE, path + " crashed", fatal);
                try { writeJson(ex, 500, Json.error("internal: " + fatal.getMessage())); }
                catch (IOException ignored) { /* socket likely dead */ }
            }
        };
        server.createContext(path, wrapped);
    }

    // ---- endpoints ----

    private void handlePing(HttpExchange ex) throws IOException {
        ObjectNode body = Json.obj();
        body.put("name", "web-agent");
        body.put("version", VERSION);
        body.put("authRequired", token != null);
        writeJson(ex, 200, body);
    }

    private void handleExecute(HttpExchange ex) throws Exception {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
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
        if (command == null || command.isEmpty()) {
            writeJson(ex, 400, Json.error("Missing 'command'"));
            return;
        }
        try {
            writeJson(ex, 200, Commands.dispatch(command, req.path("arguments")));
        } catch (RuntimeException e) {
            writeJson(ex, 404, Json.error(e.getMessage()));
        } catch (Exception failure) {
            LOG.log(Level.WARNING, "command '" + command + "' failed", failure);
            writeJson(ex, 200, Json.error(failure.getClass().getSimpleName() + ": " + failure.getMessage()));
        }
    }

    // ---- CORS / auth ----

    private void writeCors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String allow = allowedOrigins.contains("*") ? "*"
                : (origin != null && allowedOrigins.contains(origin)) ? origin : null;
        if (allow != null) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", allow);
            ex.getResponseHeaders().add("Vary", "Origin");
        }
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-WebAgent-Token");
        ex.getResponseHeaders().add("Access-Control-Max-Age", "600");
    }

    private boolean isOriginAllowed(HttpExchange ex) {
        if (allowedOrigins.contains("*")) return true;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return origin != null && allowedOrigins.contains(origin);
    }

    private boolean isTokenValid(HttpExchange ex) {
        return token == null || token.equals(ex.getRequestHeaders().getFirst("X-WebAgent-Token"));
    }

    private static void writeJson(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
