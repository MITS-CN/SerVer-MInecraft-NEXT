package cn.mits.nekogate;

import com.viaversion.viaversion.libs.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;

/**
 * Optional append-only player activity audit log.
 */
final class PlayerActivityLogger {
    private final Path logFile;
    private final boolean enabled;
    private final boolean logChat;
    private final boolean logCommands;
    private final boolean logConnections;
    private final Object writeLock = new Object();

    PlayerActivityLogger(Path directory) {
        Path configFile = directory.resolve("config.properties");
        this.logFile = directory.resolve("player-activity.jsonl");

        Properties config = new Properties();
        try {
            Files.createDirectories(directory);
            if (Files.notExists(configFile)) {
                Files.writeString(configFile, """
                    # NekoGate player activity audit log
                    # Disabled by default. Set logging.enabled=true to start recording.
                    logging.enabled=false
                    logging.chat=true
                    logging.commands=true
                    logging.connections=true
                    """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                config.load(reader);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[NekoGate] Could not load activity logging config; logging stays disabled: " + e);
        }

        this.enabled = Boolean.parseBoolean(config.getProperty("logging.enabled", "false"));
        this.logChat = Boolean.parseBoolean(config.getProperty("logging.chat", "true"));
        this.logCommands = Boolean.parseBoolean(config.getProperty("logging.commands", "true"));
        this.logConnections = Boolean.parseBoolean(config.getProperty("logging.connections", "true"));

        if (this.enabled) {
            System.out.println("[NekoGate] Player activity logging enabled: " + this.logFile);
        } else {
            System.out.println("[NekoGate] Player activity logging disabled (plugins/NekoGate/config.properties)");
        }
    }

    boolean isEnabled() {
        return this.enabled;
    }

    void logJoin(String player, String uuid, Instant joinedAt) {
        if (!this.enabled || !this.logConnections) return;
        JsonObject event = event("join", player, uuid);
        event.addProperty("joined_at", joinedAt.toString());
        append(event);
    }

    void logLeave(String player, String uuid, Instant joinedAt, Instant leftAt) {
        if (!this.enabled || !this.logConnections) return;
        JsonObject event = event("leave", player, uuid);
        event.addProperty("joined_at", joinedAt.toString());
        event.addProperty("left_at", leftAt.toString());
        event.addProperty("online_seconds", Math.max(0, leftAt.getEpochSecond() - joinedAt.getEpochSecond()));
        append(event);
    }

    void logChat(String player, String uuid, String message) {
        if (!this.enabled || !this.logChat) return;
        JsonObject event = event("chat", player, uuid);
        event.addProperty("message", message);
        append(event);
    }

    void logCommand(String player, String uuid, String command) {
        if (!this.enabled || !this.logCommands) return;
        JsonObject event = event("command", player, uuid);
        event.addProperty("command", command);
        append(event);
    }

    private static JsonObject event(String type, String player, String uuid) {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("event", type);
        event.addProperty("player", player);
        if (uuid != null) event.addProperty("uuid", uuid);
        return event;
    }

    private void append(JsonObject event) {
        synchronized (this.writeLock) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    this.logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                writer.write(event.toString());
                writer.newLine();
            } catch (IOException e) {
                System.err.println("[NekoGate] Failed to append player activity log: " + e);
            }
        }
    }
}
