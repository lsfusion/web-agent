package com.lsfusion.webagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ObjectNode result(Object value) {
        ObjectNode node = obj();
        node.set("result", MAPPER.valueToTree(value));
        return node;
    }

    public static ObjectNode error(String message) {
        return obj().put("error", message);
    }

    public static String optString(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
