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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional append-only player activity audit log.
 */
final class PlayerActivityLogger {
    private final Path logFile;
    private final boolean enabled;
    private final boolean logChat;
    private final boolean logServerChat;
    private final boolean logSystemMessages;
    private final boolean logWhispers;
    private final boolean logCommands;
    private final boolean logConnections;
    private final boolean logLocations;
    private final boolean debugNbt;
    private final Object writeLock = new Object();
    private final Object serverChatDedupLock = new Object();
    private final Map<String, Boolean> recentServerMessages = new LinkedHashMap<>(256, 0.75f, true);

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
                    # Record chat broadcast by the backend while at least one tunnel player is online.
                    logging.server_chat=true
                    logging.system_messages=true
                    logging.whispers=true
                    logging.commands=true
                    logging.connections=true
                    logging.locations=true
                    # Enable rate-limited diagnostics for rejected system-chat NBT payloads.
                    # NBT safety checks remain enabled regardless of this setting.
                    debug.nbt=false
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
        this.logServerChat = Boolean.parseBoolean(config.getProperty("logging.server_chat", "true"));
        this.logSystemMessages = Boolean.parseBoolean(config.getProperty("logging.system_messages", "true"));
        this.logWhispers = Boolean.parseBoolean(config.getProperty("logging.whispers", "true"));
        this.logCommands = Boolean.parseBoolean(config.getProperty("logging.commands", "true"));
        this.logConnections = Boolean.parseBoolean(config.getProperty("logging.connections", "true"));
        this.logLocations = Boolean.parseBoolean(config.getProperty("logging.locations", "true"));
        this.debugNbt = Boolean.parseBoolean(config.getProperty("debug.nbt", "false"));

        if (this.enabled) {
            System.out.println("[NekoGate] Player activity logging enabled: " + this.logFile);
        } else {
            System.out.println("[NekoGate] Player activity logging disabled (plugins/NekoGate/config.properties)");
        }
    }

    boolean isEnabled() {
        return this.enabled;
    }

    boolean isServerChatLoggingEnabled() {
        return this.enabled && this.logServerChat;
    }

    boolean isLocationLoggingEnabled() {
        return this.enabled && this.logLocations;
    }

    boolean isSystemMessageLoggingEnabled() {
        return this.enabled && this.logSystemMessages;
    }

    boolean isNbtDiagnosticsEnabled() {
        return this.debugNbt;
    }

    void logJoin(String player, String uuid, Instant joinedAt, String dimension,
                 Double x, Double y, Double z, Float yaw, Float pitch) {
        if (!this.enabled || !this.logConnections) return;
        JsonObject event = event("join", player, uuid);
        event.addProperty("joined_at", joinedAt.toString());
        addLocation(event, dimension, x, y, z, yaw, pitch);
        append(event);
    }

    void logLeave(String player, String uuid, Instant joinedAt, Instant leftAt, String dimension,
                  Double x, Double y, Double z, Float yaw, Float pitch) {
        if (!this.enabled || !this.logConnections) return;
        JsonObject event = event("leave", player, uuid);
        event.addProperty("joined_at", joinedAt.toString());
        event.addProperty("left_at", leftAt.toString());
        event.addProperty("online_seconds", Math.max(0, leftAt.getEpochSecond() - joinedAt.getEpochSecond()));
        addLocation(event, dimension, x, y, z, yaw, pitch);
        append(event);
    }

    void logDimensionChange(String player, String uuid, String previousDimension, String dimension,
                            Double x, Double y, Double z, Float yaw, Float pitch) {
        if (!isLocationLoggingEnabled()) return;
        JsonObject event = event("dimension_change", player, uuid);
        event.addProperty("previous_dimension", previousDimension);
        event.addProperty("dimension", dimension);
        addCoordinates(event, x, y, z, yaw, pitch);
        append(event);
    }

    void logChat(String player, String uuid, String message) {
        if (!this.enabled || !this.logChat) return;
        JsonObject event = event("chat", player, uuid);
        event.addProperty("message", message);
        append(event);
    }

    void logServerChat(String senderUuid, String senderName, String message, long messageTimestamp) {
        if (!isServerChatLoggingEnabled() || message == null || message.isBlank()) return;

        // Every tunnel client receives the same backend broadcast. Keep one copy per
        // sender/message timestamp instead of writing a duplicate for each connection.
        String key = senderUuid + "\u0000" + messageTimestamp + "\u0000" + message;
        synchronized (this.serverChatDedupLock) {
            if (this.recentServerMessages.containsKey(key)) return;
            this.recentServerMessages.put(key, Boolean.TRUE);
            if (this.recentServerMessages.size() > 4096) {
                var iterator = this.recentServerMessages.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        }

        JsonObject event = new JsonObject();
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("event", "server_chat");
        event.addProperty("sender_uuid", senderUuid);
        if (senderName != null && !senderName.isBlank()) {
            event.addProperty("sender_name", senderName);
        }
        event.addProperty("message", message);
        event.addProperty("message_timestamp", messageTimestamp);
        append(event);
    }

    void logSystemMessage(String player, String uuid, String component, boolean overlay) {
        if (!isSystemMessageLoggingEnabled() || component == null || component.isBlank()) return;
        JsonObject event = event("system_message", player, uuid);
        event.addProperty("component", component);
        event.addProperty("overlay", overlay);
        append(event);
    }

    void logWhisper(String sender, String senderUuid, String recipient, String message, String direction) {
        if (!this.enabled || !this.logWhispers || message == null || message.isBlank()) return;
        JsonObject event = event("whisper", sender, senderUuid);
        event.addProperty("direction", direction);
        if (recipient != null && !recipient.isBlank()) event.addProperty("recipient", recipient);
        event.addProperty("message", message);
        append(event);
    }

    void logReceivedWhisper(String player, String uuid, String component) {
        if (!this.enabled || !this.logWhispers || component == null || component.isBlank()) return;
        JsonObject event = event("whisper", player, uuid);
        event.addProperty("direction", "received");
        event.addProperty("component", component);
        append(event);
    }

    void logCommand(String player, String uuid, String command) {
        if (!this.enabled || !this.logCommands) return;
        JsonObject event = event("command", player, uuid);
        event.addProperty("command", command);
        append(event);
    }

    void logExtensionEvent(String eventType, Map<String, ?> fields) {
        if (!this.enabled) return;
        if (eventType == null || !eventType.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("eventType must be 1-64 letters, digits, '.', '_' or '-'");
        }
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", Instant.now().toString());
        event.addProperty("event", "extension:" + eventType);
        if (fields != null) {
            fields.forEach((key, value) -> {
                if (key == null || !key.matches("[A-Za-z0-9_.-]{1,64}")
                        || key.equals("timestamp") || key.equals("event")) return;
                if (value == null) event.add(key, com.viaversion.viaversion.libs.gson.JsonNull.INSTANCE);
                else if (value instanceof Boolean bool) event.addProperty(key, bool);
                else if (value instanceof Number number) event.addProperty(key, number);
                else if (value instanceof Character character) event.addProperty(key, character);
                else event.addProperty(key, String.valueOf(value));
            });
        }
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

    private static void addLocation(JsonObject event, String dimension, Double x, Double y, Double z,
                                    Float yaw, Float pitch) {
        if (dimension != null) event.addProperty("dimension", dimension);
        addCoordinates(event, x, y, z, yaw, pitch);
    }

    private static void addCoordinates(JsonObject event, Double x, Double y, Double z, Float yaw, Float pitch) {
        if (x != null) event.addProperty("x", x);
        if (y != null) event.addProperty("y", y);
        if (z != null) event.addProperty("z", z);
        if (yaw != null) event.addProperty("yaw", yaw);
        if (pitch != null) event.addProperty("pitch", pitch);
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
