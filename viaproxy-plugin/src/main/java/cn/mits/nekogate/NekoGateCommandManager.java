package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

final class NekoGateCommandManager implements NekoGateAPI.CommandRegistry {
    private record Registration(String permission, String description, NekoGateAPI.CommandExecutor executor) { }
    private record Context(NekoGateAPI.Player player, List<String> arguments, String input,
                           Consumer<String> reply) implements NekoGateAPI.CommandContext {
        @Override public void reply(String message) {
            if (message != null && !message.isBlank()) this.reply.accept(message);
        }
    }

    private final ConcurrentMap<String, Registration> commands = new ConcurrentHashMap<>();
    private final NekoGateAPI.PermissionService permissions;

    NekoGateCommandManager(NekoGateAPI.PermissionService permissions) {
        this.permissions = permissions;
    }

    @Override
    public NekoGateAPI.Subscription register(String name, String permission, String description,
                                              NekoGateAPI.CommandExecutor executor) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Command name must be 1-32 letters, digits, '_' or '-'");
        }
        if (permission == null || !permission.matches("[A-Za-z0-9_.*-]{1,128}")) {
            throw new IllegalArgumentException("A valid permission node is required");
        }
        if (executor == null) throw new IllegalArgumentException("executor is required");

        String key = name.toLowerCase(Locale.ROOT);
        Registration registration = new Registration(permission, description == null ? "" : description, executor);
        if (this.commands.putIfAbsent(key, registration) != null) {
            throw new IllegalStateException("Command is already registered: " + key);
        }
        return () -> this.commands.remove(key, registration);
    }

    /** Returns extension commands visible to this player in the client command tree. */
    List<String> visibleCommandNames(NekoGateAPI.Player player) {
        if (player == null) return List.of();
        return this.commands.entrySet().stream()
                .filter(entry -> this.permissions.has(player, entry.getValue().permission()))
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Returns true when the input belongs to a registered extension command. */
    boolean dispatch(NekoGateAPI.Player player, String input, Consumer<String> reply) {
        if (player == null || player.name() == null || input == null) return false;
        List<String> tokens = tokenize(input);
        if (tokens.isEmpty()) return false;

        String commandName = tokens.get(0);
        if (commandName.startsWith("/")) commandName = commandName.substring(1);
        int namespace = commandName.indexOf(':');
        if (namespace >= 0) commandName = commandName.substring(namespace + 1);
        Registration registration = this.commands.get(commandName.toLowerCase(Locale.ROOT));
        if (registration == null) return false;

        Consumer<String> safeReply = reply == null ? ignored -> { } : reply;
        if (!this.permissions.has(player, registration.permission())) {
            safeReply.accept("§cYou do not have permission: " + registration.permission());
            return true;
        }

        try {
            registration.executor().execute(new Context(player, List.copyOf(tokens.subList(1, tokens.size())),
                    input, safeReply));
        } catch (Throwable error) {
            System.err.println("[NekoGate] Extension command failed (" + commandName + "): " + error);
            safeReply.accept("§cAn error occurred while executing this command.");
        }
        return true;
    }

    private static List<String> tokenize(String input) {
        String value = input.trim();
        if (value.startsWith("/")) value = value.substring(1);
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                token.append(c);
                escaped = false;
            } else if (c == '\\' && quote != 0) {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!token.isEmpty()) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(c);
            }
        }
        if (escaped) token.append('\\');
        if (!token.isEmpty()) tokens.add(token.toString());
        return tokens;
    }
}
