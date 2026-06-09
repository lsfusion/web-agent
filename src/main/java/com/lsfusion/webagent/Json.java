package com.lsfusion.webagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ObjectNode result(Object value) {
        return obj().set("result", MAPPER.valueToTree(value));
    }

    public static ObjectNode resultNull() {
        return obj().putNull("result");
    }

    // Flutter encodes byte payloads (TCP responses, file dumps) and error messages
    // alike as base64 strings inside the "result" field; mirror that.
    public static ObjectNode resultB64(byte[] bytes) {
        return obj().put("result", Base64.getEncoder().encodeToString(bytes));
    }

    public static ObjectNode resultB64(String s) {
        return resultB64(s.getBytes(StandardCharsets.UTF_8));
    }

    public static ObjectNode error(String message) {
        return obj().put("error", message);
    }

    public static String optString(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
