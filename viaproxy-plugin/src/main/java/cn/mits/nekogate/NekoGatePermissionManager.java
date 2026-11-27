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
import java.util.stream.Collectors;

final class NekoGatePermissionManager implements NekoGateAPI.PermissionService {
    private final Properties grants = new Properties();

    NekoGatePermissionManager(Path file) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.writeString(file,
                        "# NekoGate extension permissions\n" +
                        "# One line per player UUID: <uuid>=permission.node,another.node\n" +
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
        if (player == null || player.uuid() == null || permission == null || permission.isBlank()) return false;
        String configured = this.grants.getProperty(player.uuid().toString());
        if (configured == null || configured.isBlank()) return false;

        Set<String> nodes = Arrays.stream(configured.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
        String requested = permission.toLowerCase(Locale.ROOT);
        if (nodes.contains("*") || nodes.contains(requested)) return true;
        for (String node : nodes) {
            if (node.endsWith(".*") && requested.startsWith(node.substring(0, node.length() - 1))) return true;
        }
        return false;
    }
}
