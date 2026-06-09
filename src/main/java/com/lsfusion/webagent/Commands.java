package com.lsfusion.webagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fazecast.jSerialComm.SerialPort;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Commands {

    public static JsonNode dispatch(String command, JsonNode args) throws Exception {
        return switch (command) {
            case "print" -> print(args);
            case "getAvailablePrinters" -> getAvailablePrinters();
            case "readFile" -> readFile(args);
            case "writeFile" -> writeFile(args);
            case "deleteFile" -> deleteFile(args);
            case "fileExists" -> fileExists(args);
            case "makeDir" -> makeDir(args);
            case "moveFile" -> moveFile(args);
            case "copyFile" -> copyFile(args);
            case "listFiles" -> listFiles(args);
            case "runCommand" -> runCommand(args);
            case "sendTCP" -> sendTCP(args);
            case "sendUDP" -> sendUDP(args);
            case "writeToSocket" -> writeToSocket(args);
            case "writeToComPort" -> writeToComPort(args);
            case "ping" -> ping(args);
            default -> throw new RuntimeException("Unknown command: " + command);
        };
    }

    // args: [fileData(base64)|null, filePath|null, text|null, printerName|null]
    private static JsonNode print(JsonNode args) throws Exception {
        String fileData = Json.optString(args.path(0));
        String filePath = Json.optString(args.path(1));
        String text = Json.optString(args.path(2));
        String printerName = Json.optString(args.path(3));

        PrintService service = printerName == null
                ? PrintServiceLookup.lookupDefaultPrintService()
                : Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(s -> printerName.equals(s.getName()))
                .findFirst().orElse(null);
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

    private static JsonNode getAvailablePrinters() {
        String names = Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(PrintService::getName)
                .collect(Collectors.joining("\n"));
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        return Json.obj()
                .put("result", names)
                .put("default", def == null ? null : def.getName());
    }

    // args: [path]
    private static JsonNode readFile(JsonNode args) {
        String path = Json.optString(args.path(0));
        try {
            Path p = Path.of(path);
            if (!Files.exists(p)) return Json.error("File does not exist");
            byte[] bytes = Files.readAllBytes(p);
            return Json.result(Base64.getEncoder().encodeToString(bytes));
        } catch (Exception e) {
            return Json.error("Error reading file: " + e);
        }
    }

    // args: [url, path] — download URL into local file
    private static JsonNode writeFile(JsonNode args) {
        String url = Json.optString(args.path(0));
        String path = Json.optString(args.path(1));
        try {
            URLConnection conn = new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; lsFusion-web-agent)");
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, Path.of(path), StandardCopyOption.REPLACE_EXISTING);
            }
            return Json.resultNull();
        } catch (Exception e) {
            return Json.resultB64("Exception: " + e);
        }
    }

    // args: [path]
    private static JsonNode deleteFile(JsonNode args) {
        String path = Json.optString(args.path(0));
        try {
            Path p = Path.of(path);
            if (!Files.exists(p)) {
                return Json.result("File or directory does not exist: " + path);
            }
            if (Files.isDirectory(p)) {
                try (Stream<Path> stream = Files.walk(p)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(f -> {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                    });
                }
            } else {
                Files.delete(p);
            }
            return Json.resultNull();
        } catch (Exception e) {
            return Json.result("Error deleting file or directory: " + e);
        }
    }

    // args: [path]
    private static JsonNode fileExists(JsonNode args) {
        String path = Json.optString(args.path(0));
        // Path.of can throw InvalidPathException on garbage; treat as "no".
        try {
            return Json.result(path != null && Files.exists(Path.of(path)));
        } catch (RuntimeException e) {
            return Json.result(false);
        }
    }

    // args: [path]
    private static JsonNode makeDir(JsonNode args) {
        String path = Json.optString(args.path(0));
        try {
            Files.createDirectories(Path.of(path));
            return Json.result(true);
        } catch (Exception e) {
            return Json.error("Error making dir: " + e);
        }
    }

    // args: [source, destination]
    private static JsonNode moveFile(JsonNode args) {
        String src = Json.optString(args.path(0));
        String dst = Json.optString(args.path(1));
        try {
            Files.move(Path.of(src), Path.of(dst), StandardCopyOption.REPLACE_EXISTING);
            return Json.resultNull();
        } catch (Exception e) {
            return Json.result("Error moving file: " + e);
        }
    }

    // args: [source, destination]
    private static JsonNode copyFile(JsonNode args) {
        String src = Json.optString(args.path(0));
        String dst = Json.optString(args.path(1));
        try {
            Files.copy(Path.of(src), Path.of(dst), StandardCopyOption.REPLACE_EXISTING);
            return Json.resultNull();
        } catch (Exception e) {
            return Json.result("Error copying file: " + e);
        }
    }

    // args: [source, recursive]
    private static JsonNode listFiles(JsonNode args) {
        String source = Json.optString(args.path(0));
        boolean recursive = args.path(1).asBoolean(false);
        ArrayNode entries = Json.MAPPER.createArrayNode();
        Path root = Path.of(source);
        if (!Files.exists(root)) return Json.obj().set("result", entries);
        try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
            stream.filter(p -> !p.equals(root)).forEach(p -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    ObjectNode entry = Json.obj();
                    entry.put("path", p.toString().replace("\\", "\\\\"));
                    entry.put("isDirectory", attrs.isDirectory());
                    entry.put("modifiedDateTime",
                            Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    entry.put("fileSize", attrs.isDirectory() ? 0 : attrs.size());
                    entries.add(entry);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            return Json.error("Error listing files: " + e);
        }
        return Json.obj().set("result", entries);
    }

    // args: [command] — single command line; on Windows wrapped via cmd /c,
    // on Unix via sh -c, mirroring Dart's Process.run behaviour.
    private static JsonNode runCommand(JsonNode args) throws Exception {
        String command = Json.optString(args.path(0));
        if (command == null) return Json.error("runCommand: command is required");

        ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);

        Process p = pb.start();
        // Drain stdout/stderr concurrently to avoid pipe-buffer deadlock on large output.
        CompletableFuture<String> outFut = drainAsync(p.getInputStream());
        CompletableFuture<String> errFut = drainAsync(p.getErrorStream());
        int exit = p.waitFor();

        return Json.obj()
                .put("cmdOut", outFut.join().trim())
                .put("cmdErr", errFut.join().trim())
                .put("exitValue", exit);
    }

    private static CompletableFuture<String> drainAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    // args: [host, port, fileBytes(base64), timeoutMs] — write payload, read response.
    private static JsonNode sendTCP(JsonNode args) {
        String host = Json.optString(args.path(0));
        int port = args.path(1).asInt();
        String fileBytes = Json.optString(args.path(2));
        int timeoutMs = args.path(3).asInt(3600000);
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            sock.getOutputStream().write(Base64.getDecoder().decode(fileBytes));
            sock.getOutputStream().flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = sock.getInputStream().read(chunk)) != -1) buf.write(chunk, 0, n);
            } catch (SocketTimeoutException ignored) {
                // soft EOF — return whatever we managed to read
            }
            return Json.resultB64(buf.toByteArray());
        } catch (Exception e) {
            return Json.resultB64(e.toString());
        }
    }

    // args: [host, port, fileBytes(base64)]
    private static JsonNode sendUDP(JsonNode args) {
        String host = Json.optString(args.path(0));
        int port = args.path(1).asInt();
        String fileBytes = Json.optString(args.path(2));
        try (DatagramSocket sock = new DatagramSocket()) {
            byte[] data = Base64.getDecoder().decode(fileBytes);
            sock.send(new DatagramPacket(data, data.length, InetAddress.getByName(host), port));
            return Json.resultNull();
        } catch (Exception e) {
            return Json.resultB64(e.toString());
        }
    }

    // args: [host, port, text, charset]
    private static JsonNode writeToSocket(JsonNode args) {
        String host = Json.optString(args.path(0));
        int port = args.path(1).asInt();
        String text = Json.optString(args.path(2));
        String charsetName = Json.optString(args.path(3));
        try {
            Charset charset = switch (charsetName == null ? "" : charsetName.toLowerCase()) {
                case "utf8", "utf-8" -> StandardCharsets.UTF_8;
                case "ascii" -> StandardCharsets.US_ASCII;
                case "latin1", "iso-8859-1" -> StandardCharsets.ISO_8859_1;
                default -> null;
            };
            if (charset == null) return Json.result("Unsupported charset: " + charsetName);
            try (Socket sock = new Socket(host, port)) {
                sock.getOutputStream().write(text.getBytes(charset));
                sock.getOutputStream().flush();
            }
            return Json.resultNull();
        } catch (Exception e) {
            return Json.result("Socket error: " + e);
        }
    }

    // args: [portName, baudRate, fileBytes(base64)]
    private static JsonNode writeToComPort(JsonNode args) {
        String portName = Json.optString(args.path(0));
        int baudRate = args.path(1).asInt();
        String base64 = Json.optString(args.path(2));
        try {
            SerialPort port = SerialPort.getCommPort(portName);
            port.setBaudRate(baudRate);
            if (!port.openPort()) return Json.result("Failed to open port " + portName);
            try {
                byte[] data = Base64.getDecoder().decode(base64);
                int written = port.writeBytes(data, data.length);
                if (written == data.length) return Json.resultNull();
                return Json.result("Failed to write all bytes to port");
            } finally {
                port.closePort();
            }
        } catch (Exception e) {
            return Json.result("Error writing to COM port: " + e);
        }
    }

    // args: [host] — Flutter does a TCP 80 connect, not ICMP. Mirror that.
    private static JsonNode ping(JsonNode args) {
        String host = Json.optString(args.path(0));
        try {
            String h = host;
            if (host != null && host.contains("://")) {
                URI uri = URI.create(host);
                if (uri.getHost() != null) h = uri.getHost();
            }
            try (Socket sock = new Socket()) {
                assert h != null;
                sock.connect(new InetSocketAddress(h, 80), 5000);
            }
            return Json.resultNull();
        } catch (Exception e) {
            return Json.result("Host is not reachable: " + e);
        }
    }
}
