package com.lsfusion.webagent.command;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One ClientAction = one handler. The contract mirrors the Flutter client's
 * executeCommand(cmd, arguments): inputs come as a positional JSON array
 * (same shape that GwtActionDispatcher.executeAsyncResultFlutter sends), and
 * the returned JsonNode is the body the browser will receive verbatim.
 */
public interface CommandHandler {
    String name();

    JsonNode handle(JsonNode arguments) throws Exception;
}
