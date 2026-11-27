package cn.mits.nekogate;

import cn.mits.nekogate.api.Component;
import cn.mits.nekogate.api.DisplayName;
import cn.mits.nekogate.api.DisplayNameService;
import cn.mits.nekogate.api.NekoGateAPI;
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

/** Thread-safe display-name overrides backed by plugins/NekoGate/name.josn5. */
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
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\n  \"players\": {}\n}\n");
        }
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject entries = root.has("players") ? root.getAsJsonObject("players") : root;
        Map<UUID, DisplayName> next = new java.util.HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            UUID id;
            try { id = UUID.fromString(entry.getKey()); } catch (IllegalArgumentException ignored) { continue; }
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject value = entry.getValue().getAsJsonObject();
            String prefix = string(value, "prefix"), name = string(value, "name"), suffix = string(value, "suffix");
            if (name == null) continue;
            next.put(id, DisplayName.of(prefix, name, suffix));
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
    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
