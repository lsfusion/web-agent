package com.lsfusion.webagent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Main {

    public static void main(String[] args) throws Exception {
        Logging.init();

        String host = getOpt(args, "--host", System.getenv("WEB_AGENT_HOST"), "127.0.0.1");
        int port = Integer.parseInt(getOpt(args, "--port", System.getenv("WEB_AGENT_PORT"), "8765"));
        String token = getOpt(args, "--token", System.getenv("WEB_AGENT_TOKEN"), null);
        String originsCsv = getOpt(args, "--allowed-origins",
                System.getenv("WEB_AGENT_ALLOWED_ORIGINS"), "*");
        List<String> allowedOrigins = "*".equals(originsCsv)
                ? Collections.singletonList("*")
                : Arrays.asList(originsCsv.split(","));

        WebAgentServer server = new WebAgentServer(host, port, allowedOrigins, token);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "web-agent-shutdown"));
        server.start();

        if (!hasFlag(args, "--no-tray") && System.getenv("WEB_AGENT_NO_TRAY") == null)
            Tray.install(host, port);
    }

    private static String getOpt(String[] args, String flag, String envValue, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return envValue != null ? envValue : fallback;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) return true;
        }
        return false;
    }
}
