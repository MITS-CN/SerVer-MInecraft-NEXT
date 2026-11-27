package cn.mits.nekogate;

import cn.mits.nekogate.api.Component;
import cn.mits.nekogate.api.DisplayName;
import cn.mits.nekogate.api.DisplayNameService;
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
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe display-name overrides backed by ./data/name.josn5; keys are player names. */
final class DisplayNameManager implements DisplayNameService {
    private final Path file;
    private final Map<String, DisplayName> configured = new ConcurrentHashMap<>();
    private final Map<String, DisplayName> runtime = new ConcurrentHashMap<>();

    DisplayNameManager(Path file, NekoGateAPI.PlayerService players) {
        this.file = file;
        try { reload(); } catch (IOException e) {
            System.err.println("[NekoGate] Could not load display names: " + e.getMessage());
        }
    }

    @Override public Optional<DisplayName> find(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        String key = key(username);
        DisplayName value = runtime.get(key);
        return Optional.ofNullable(value != null ? value : configured.get(key));
    }

    @Override public void set(String username, DisplayName displayName) {
        if (username == null || username.isBlank() || displayName == null) {
            throw new IllegalArgumentException("username and displayName are required");
        }
        runtime.put(key(username), displayName);
    }

    @Override public void clear(String username) {
        if (username != null) runtime.remove(key(username));
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

        Map<String, DisplayName> next = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String username = entry.getKey().trim();
            if (username.isEmpty() || !entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            JsonElement nameElement = value.get("name");
            if (nameElement == null || nameElement.isJsonNull()) continue;
            Component name = readComponent(nameElement);
            if (name.text().isBlank() && name.children().isEmpty()) continue;
            next.put(key(username), new DisplayName(
                    readComponent(value.get("prefix")),
                    name,
                    readComponent(value.get("suffix"))
            ));
        }
        configured.clear();
        configured.putAll(next);
    }

    @Override public Component chatName(String username) { return resolve(username); }
    @Override public Component tabName(String username) { return resolve(username); }

    private Component resolve(String username) {
        DisplayName value = find(username).orElse(null);
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
