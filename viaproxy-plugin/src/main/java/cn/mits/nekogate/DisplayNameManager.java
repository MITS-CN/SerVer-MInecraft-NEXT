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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe display-name overrides backed by ./data/name.josn5. */
final class DisplayNameManager implements DisplayNameService {
    private final Path file;
    private final NekoGateAPI.PlayerService players;
    private final Map<UUID, DisplayName> configured = new ConcurrentHashMap<>();
    private final Map<UUID, DisplayName> runtime = new ConcurrentHashMap<>();

    DisplayNameManager(Path file, NekoGateAPI.PlayerService players) {
        this.file = file;
        this.players = players;
        try { reload(); } catch (IOException e) {
            System.err.println("[NekoGate] Could not load display names: " + e.getMessage());
        }
    }

    @Override public Optional<DisplayName> find(UUID id) {
        if (id == null) return Optional.empty();
        DisplayName value = runtime.get(id);
        return Optional.ofNullable(value != null ? value : configured.get(id));
    }
    @Override public Optional<DisplayName> find(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return players.find(username).flatMap(p -> find(p.uuid()));
    }
    @Override public void set(UUID id, DisplayName name) {
        if (id == null || name == null) throw new IllegalArgumentException("playerId and displayName are required");
        runtime.put(id, name);
    }
    @Override public void clear(UUID id) { if (id != null) runtime.remove(id); }

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
        Map<UUID, DisplayName> next = new java.util.HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            UUID id;
            try { id = UUID.fromString(entry.getKey()); } catch (RuntimeException ignored) { continue; }
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            JsonElement nameElement = value.get("name");
            if (nameElement == null || nameElement.isJsonNull()) continue;
            Component name = readComponent(nameElement);
            if (name.text().isBlank() && name.children().isEmpty()) continue;
            next.put(id, new DisplayName(
                    readComponent(value.get("prefix")),
                    name,
                    readComponent(value.get("suffix"))
            ));
        }
        configured.clear();
        configured.putAll(next);
    }

    @Override public Component chatName(UUID id, String username) { return resolve(id, username); }
    @Override public Component tabName(UUID id, String username) { return resolve(id, username); }

    private Component resolve(UUID id, String username) {
        DisplayName value = find(id).orElse(null);
        return value == null ? Component.text(username == null ? "" : username)
                : Component.join(value.prefix(), value.name(), value.suffix());
    }

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
