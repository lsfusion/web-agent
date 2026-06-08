package com.lsfusion.webagent;

import com.lsfusion.webagent.command.CommandDispatcher;
import com.lsfusion.webagent.command.GetAvailablePrintersHandler;
import com.lsfusion.webagent.command.PrintHandler;
import com.lsfusion.webagent.server.WebAgentServer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Main {

    public static void main(String[] args) throws Exception {
        String host = getOpt(args, "--host", System.getenv("WEB_AGENT_HOST"), "127.0.0.1");
        int port = Integer.parseInt(getOpt(args, "--port", System.getenv("WEB_AGENT_PORT"), "8765"));
        String token = getOpt(args, "--token", System.getenv("WEB_AGENT_TOKEN"), null);
        String originsCsv = getOpt(args, "--allowed-origins",
                System.getenv("WEB_AGENT_ALLOWED_ORIGINS"), "*");
        List<String> allowedOrigins = "*".equals(originsCsv)
                ? Collections.singletonList("*")
                : Arrays.asList(originsCsv.split(","));

        CommandDispatcher dispatcher = new CommandDispatcher()
                .register(new PrintHandler())
                .register(new GetAvailablePrintersHandler());

        WebAgentServer server = new WebAgentServer(host, port, dispatcher, allowedOrigins, token);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "web-agent-shutdown"));
        server.start();
    }

    private static String getOpt(String[] args, String flag, String envValue, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return envValue != null ? envValue : fallback;
    }
}
