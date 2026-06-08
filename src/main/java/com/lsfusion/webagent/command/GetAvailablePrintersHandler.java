package com.lsfusion.webagent.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.lsfusion.webagent.server.Json;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;

/**
 * Returns a JSON-encoded array of printer names as the "result" field — the
 * GWT side passes it straight to getJSONStringResult, so the value must be a
 * string. GPrintFileAction-side code then re-parses that string as JSON.
 */
public final class GetAvailablePrintersHandler implements CommandHandler {

    @Override
    public String name() {
        return "getAvailablePrinters";
    }

    @Override
    public JsonNode handle(JsonNode args) throws Exception {
        ArrayNode printers = Json.MAPPER.createArrayNode();
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            printers.add(s.getName());
        }
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        return Json.obj()
                .put("result", Json.MAPPER.writeValueAsString(printers))
                .put("default", def == null ? null : def.getName());
    }
}
