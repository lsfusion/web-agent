package com.lsfusion.webagent.command;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class CommandDispatcher {

    private final Map<String, CommandHandler> handlers = new LinkedHashMap<>();

    public CommandDispatcher register(CommandHandler handler) {
        handlers.put(handler.name(), handler);
        return this;
    }

    public Set<String> commands() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    public JsonNode dispatch(String command, JsonNode arguments) throws Exception {
        CommandHandler handler = handlers.get(command);
        if (handler == null) {
            throw new UnknownCommandException(command);
        }
        return handler.handle(arguments);
    }

    public static final class UnknownCommandException extends RuntimeException {
        public UnknownCommandException(String command) {
            super("Unknown command: " + command);
        }
    }
}
