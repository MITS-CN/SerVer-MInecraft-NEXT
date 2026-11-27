package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

final class NekoGatePermissionManager implements NekoGateAPI.PermissionService {
    private final Properties grants = new Properties();

    NekoGatePermissionManager(Path file) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.writeString(file,
                        "# NekoGate extension permissions\n" +
                        "# Use player name (recommended for tunnel/offline-mode networks) or UUID as the key.\n" +
                        "# Examples: Steve=nekogate.nametag and <uuid>=example.hello\n" +
                        "# '*' grants every permission; 'nekogate.example.*' grants that namespace.\n" +
                        "# Permissions are denied by default. Restart ViaProxy after editing.\n",
                        StandardCharsets.UTF_8);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                this.grants.load(reader);
            }
        } catch (IOException error) {
            System.err.println("[NekoGate] Could not load extension permissions: " + error);
        }
    }

    @Override
    public boolean has(NekoGateAPI.Player player, String permission) {
        if (player == null || permission == null || permission.isBlank()) return false;

        // UUID grants remain supported for installations with stable authenticated UUIDs.
        // Name grants also work on tunnel/offline-mode connections where UUID may be absent
        // or may not be stable across authentication boundaries.
        Set<String> nodes = new java.util.HashSet<>();
        if (player.uuid() != null) {
            addNodes(nodes, this.grants.getProperty(player.uuid().toString()));
        }
        if (player.name() != null && !player.name().isBlank()) {
            for (String key : this.grants.stringPropertyNames()) {
                if (key.equalsIgnoreCase(player.name())) {
                    addNodes(nodes, this.grants.getProperty(key));
                }
            }
        }
        if (nodes.isEmpty()) return false;
        String requested = permission.toLowerCase(Locale.ROOT);
        if (nodes.contains("*") || nodes.contains(requested)) return true;
        for (String node : nodes) {
            if (node.endsWith(".*") && requested.startsWith(node.substring(0, node.length() - 1))) return true;
        }
        return false;
    }

    private static void addNodes(Set<String> nodes, String configured) {
        if (configured == null || configured.isBlank()) return;
        Arrays.stream(configured.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .forEach(nodes::add);
    }
}
