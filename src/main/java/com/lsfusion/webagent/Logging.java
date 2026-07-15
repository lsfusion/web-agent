package com.lsfusion.webagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

// Routes everything logged through java.util.logging — the agent's own classes
// plus libraries that fall back to JUL (e.g. PDFBox via commons-logging) — into
// a rotating file: the installed app is a windowless exe whose stderr goes
// nowhere. The default ConsoleHandler stays, so `java -jar` runs still print.
final class Logging {

    private Logging() {
    }

    static void init() {
        // one line per entry: 2026-07-15 14:30:05.123 INFO    com.lsfusion... - message
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT.%1$tL %4$-7s %3$s - %5$s%6$s%n");
        try {
            Path dir = logDir();
            Files.createDirectories(dir);
            // 3 generations x 1MB, append; JUL adds a unique suffix on its own
            // when another running instance already holds the .lck file
            FileHandler file = new FileHandler(
                    dir.resolve("web-agent.%g.log").toString().replace('\\', '/'),
                    1024 * 1024, 3, true);
            file.setFormatter(new SimpleFormatter());
            Logger.getLogger("").addHandler(file);
        } catch (IOException e) {
            // a broken file log is not a reason to refuse to run
            Logger.getLogger(Logging.class.getName()).log(Level.WARNING, "file log unavailable", e);
        }

        // without this, exceptions on arbitrary threads die silently in the windowless exe
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                Logger.getLogger(Logging.class.getName())
                        .log(Level.SEVERE, "uncaught exception in thread " + t.getName(), e));
    }

    // %LOCALAPPDATA%\lsFusion\web-agent, with a dot-dir fallback for non-Windows
    static Path logDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank())
            return Path.of(localAppData, "lsFusion", "web-agent");
        return Path.of(System.getProperty("user.home"), ".lsfusion", "web-agent");
    }
}
