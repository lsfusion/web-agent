package com.lsfusion.webagent.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsfusion.webagent.server.Json;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Handles the "print" command from GwtActionDispatcher (GPrintFileAction /
 * GWriteToPrinterAction). Args: [fileData(base64) | null, filePath | null,
 * text | null, printerName | null].
 *
 * Returns {"result": "OK"} on success, {"error": "..."} on failure — matching
 * what getJSONStringResult / getJSONError expect on the GWT side.
 */
public final class PrintHandler implements CommandHandler {

    @Override
    public String name() {
        return "print";
    }

    @Override
    public JsonNode handle(JsonNode args) throws Exception {
        String fileData   = Json.optString(args.path(0));
        String filePath   = Json.optString(args.path(1));
        String text       = Json.optString(args.path(2));
        String printerName = Json.optString(args.path(3));

        PrintService service = pickPrinter(printerName);
        if (service == null) {
            return Json.error(printerName == null
                    ? "No default printer found"
                    : "Printer not found: " + printerName);
        }

        Doc doc;
        if (text != null) {
            doc = new SimpleDoc(new StringReader(text), DocFlavor.READER.TEXT_PLAIN, null);
        } else if (fileData != null) {
            byte[] bytes = Base64.getDecoder().decode(fileData);
            doc = new SimpleDoc(new ByteArrayInputStream(bytes), DocFlavor.INPUT_STREAM.AUTOSENSE, null);
        } else if (filePath != null) {
            InputStream in = Files.newInputStream(Path.of(filePath));
            doc = new SimpleDoc(in, DocFlavor.INPUT_STREAM.AUTOSENSE, null);
        } else {
            return Json.error("print: one of fileData, filePath, text must be provided");
        }

        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        DocPrintJob job = service.createPrintJob();
        job.print(doc, attrs);
        return Json.result("OK");
    }

    private static PrintService pickPrinter(String name) {
        if (name == null) {
            return PrintServiceLookup.lookupDefaultPrintService();
        }
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (name.equals(s.getName())) return s;
        }
        return null;
    }
}
