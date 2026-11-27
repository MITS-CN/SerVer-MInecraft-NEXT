package cn.mits.nekogate;

import cn.mits.nekogate.api.Component;
import cn.mits.nekogate.api.DisplayName;
import cn.mits.nekogate.api.DisplayNameService;
import cn.mits.nekogate.api.NekoGateAPI;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe display-name overrides backed by ./data/name.josn5.
 * Player names are the canonical configuration keys; UUID entries remain
 * readable for extensions/configurations created with API v1.2.
 */
final class DisplayNameManager implements DisplayNameService {
    private final Path file;
    private final NekoGateAPI.PlayerService players;
    private final Map<String, DisplayName> configured = new ConcurrentHashMap<>();
    private final Map<String, DisplayName> runtime = new ConcurrentHashMap<>();
    private final Map<UUID, DisplayName> legacyConfigured = new ConcurrentHashMap<>();
    private final Map<UUID, DisplayName> legacyRuntime = new ConcurrentHashMap<>();

    DisplayNameManager(Path file, NekoGateAPI.PlayerService players) {
        this.file = file;
        this.players = players;
        try { reload(); } catch (IOException e) {
            System.err.println("[NekoGate] Could not load display names: " + e.getMessage());
        }
    }

    @Override public Optional<DisplayName> find(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String key = key(username);
        DisplayName value = runtime.get(key);
        if (value == null) value = configured.get(key);
        if (value != null) return Optional.of(value);

        // Compatibility: the v1.2 NameTag extension persisted entries by UUID.
        return players.find(username).map(NekoGateAPI.Player::uuid)
                .map(this::findLegacy).orElse(Optional.empty());
    }

    @Override public Optional<DisplayName> find(UUID playerId) {
        if (playerId == null) return Optional.empty();
        Optional<DisplayName> legacy = findLegacy(playerId);
        if (legacy.isPresent()) return legacy;
        return players.find(playerId.toString())
                .map(NekoGateAPI.Player::name)
                .map(this::findByName)
                .orElse(Optional.empty());
    }

    private Optional<DisplayName> findLegacy(UUID playerId) {
        DisplayName value = legacyRuntime.get(playerId);
        if (value == null) value = legacyConfigured.get(playerId);
        return Optional.ofNullable(value);
    }

    private Optional<DisplayName> findByName(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String key = key(username);
        DisplayName value = runtime.get(key);
        if (value == null) value = configured.get(key);
        return Optional.ofNullable(value);
    }

    @Override public void set(String username, DisplayName displayName) {
        if (username == null || username.isBlank() || displayName == null) {
            throw new IllegalArgumentException("username and displayName are required");
        }
        runtime.put(key(username), displayName);
    }

    @Override public void set(UUID playerId, DisplayName displayName) {
        if (playerId == null || displayName == null) {
            throw new IllegalArgumentException("playerId and displayName are required");
        }
        legacyRuntime.put(playerId, displayName);
    }

    @Override public void clear(String username) {
        if (username != null) runtime.remove(key(username));
    }

    @Override public void clear(UUID playerId) {
        if (playerId != null) legacyRuntime.remove(playerId);
    }

    @Override public synchronized void reload() throws IOException {
        if (!Files.exists(file)) {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, "{\n  \"players\": {}\n}\n");
        }
        JsonObject root;
        JsonObject entries;
        try {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            entries = root.has("players") ? root.getAsJsonObject("players") : root;
        } catch (RuntimeException e) {
            throw new IOException("Invalid display-name configuration: " + e.getMessage(), e);
        }

        Map<String, DisplayName> nextNames = new HashMap<>();
        Map<UUID, DisplayName> nextIds = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String configuredKey = entry.getKey().trim();
            if (configuredKey.isEmpty() || !entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            JsonElement nameElement = value.get("name");
            if (nameElement == null || nameElement.isJsonNull()) continue;
            Component name = readComponent(nameElement);
            if (name.text().isBlank() && name.children().isEmpty()) continue;
            DisplayName displayName = new DisplayName(
                    readComponent(value.get("prefix")),
                    name,
                    readComponent(value.get("suffix"))
            );
            try {
                nextIds.put(UUID.fromString(configuredKey), displayName);
            } catch (IllegalArgumentException notUuid) {
                nextNames.put(key(configuredKey), displayName);
            }
        }
        configured.clear();
        configured.putAll(nextNames);
        legacyConfigured.clear();
        legacyConfigured.putAll(nextIds);
    }

    @Override public Component chatName(String username) { return resolve(username); }
    @Override public Component tabName(String username) { return resolve(username); }

    @Override public Component chatName(UUID playerId, String username) {
        return resolve(playerId, username);
    }

    @Override public Component tabName(UUID playerId, String username) {
        return resolve(playerId, username);
    }

    private Component resolve(String username) {
        DisplayName value = find(username).orElse(null);
        return value == null ? Component.text(username == null ? "" : username)
                : Component.join(value.prefix(), value.name(), value.suffix());
    }

    private Component resolve(UUID playerId, String username) {
        DisplayName value = find(playerId).orElseGet(() -> findByName(username).orElse(null));
        return value == null ? Component.text(username == null ? "" : username)
                : Component.join(value.prefix(), value.name(), value.suffix());
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    /** Reads both simple components and vanilla-style JSON text components with extra[]. */
    private static Component readComponent(JsonElement element) {
        if (element == null || element.isJsonNull()) return Component.empty();
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().isString()
                    ? Component.text(element.getAsString()) : Component.empty();
        }
        if (!element.isJsonObject()) return Component.empty();

        JsonObject object = element.getAsJsonObject();
        String text = string(object, "text");
        if (text == null) text = "";
        Component result = Component.text(text);

        JsonElement gradientElement = object.get("gradient");
        if (gradientElement != null && gradientElement.isJsonArray()) {
            JsonArray gradient = gradientElement.getAsJsonArray();
            if (gradient.size() == 2) {
                try {
                    result = Component.gradient(text, gradient.get(0).getAsString(), gradient.get(1).getAsString());
                } catch (RuntimeException ignored) {
                    // Invalid colors fall back to the component's plain text.
                }
            }
        } else {
            String color = string(object, "color");
            if (color != null) {
                try { result = Component.colored(text, color); }
                catch (RuntimeException ignored) {
                    // Invalid colors fall back to the component's plain text.
                }
            }
        }
        if (booleanValue(object, "bold")) result = result.withBold(true);
        if (booleanValue(object, "italic")) result = result.withItalic(true);

        JsonElement extra = object.get("extra");
        if (extra != null && extra.isJsonArray()) {
            java.util.List<Component> children = new java.util.ArrayList<>();
            for (JsonElement child : extra.getAsJsonArray()) children.add(readComponent(child));
            result = result.withChildren(children);
        }
        return result;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
