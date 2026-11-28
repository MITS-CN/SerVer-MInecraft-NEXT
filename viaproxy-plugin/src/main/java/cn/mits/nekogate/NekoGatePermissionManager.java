package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

final class NekoGatePermissionManager implements NekoGateAPI.PermissionService {
    private final Properties grants = new Properties();
    private final Path file;

    NekoGatePermissionManager(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.writeString(file,
                        "# NekoGate extension permissions\n" +
                        "# Use player name (recommended for tunnel/offline-mode networks) or UUID as the key.\n" +
                        "# Examples: Steve=nekogate.nametag and <uuid>=example.hello\n" +
                        "# '*' grants every permission; 'nekogate.example.*' grants that namespace.\n" +
                        "# No player receives permissions by default. Use the Rust console: op <player> / deop <player>.\n" +
                        "# Permissions are denied by default. Manual edits take effect after restarting ViaProxy.\n",
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
    public synchronized boolean has(NekoGateAPI.Player player, String permission) {
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

    /**
     * Grants or revokes full extension access for a player name or UUID.
     * This is called only by the local Rust console control action.
     */
    synchronized void setOperator(String playerNameOrUuid, String uuid, boolean operator) {
        if (playerNameOrUuid == null || playerNameOrUuid.isBlank()) {
            throw new IllegalArgumentException("player name or UUID is required");
        }

        Properties previous = new Properties();
        previous.putAll(this.grants);
        removeMatchingKey(playerNameOrUuid);
        if (uuid != null && !uuid.isBlank()) removeMatchingKey(uuid);
        if (operator) this.grants.setProperty(playerNameOrUuid, "*");

        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                this.grants.store(writer, "NekoGate extension permissions");
            }
            try {
                Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            this.grants.clear();
            this.grants.putAll(previous);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new IllegalStateException("Could not save extension permissions: " + error.getMessage(), error);
        }
    }

    private void removeMatchingKey(String value) {
        for (String key : this.grants.stringPropertyNames().toArray(String[]::new)) {
            if (key.equalsIgnoreCase(value)) this.grants.remove(key);
        }
    }

    private static void addNodes(Set<String> nodes, String configured) {
        if (configured == null || configured.isBlank()) return;
        Arrays.stream(configured.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .forEach(nodes::add);
    }
}
