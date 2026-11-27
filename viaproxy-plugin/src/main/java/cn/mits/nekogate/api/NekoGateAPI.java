package cn.mits.nekogate.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Stable public API exposed to trusted NekoGate extensions.
 * API compatibility is tracked independently from the plugin version.
 */
public interface NekoGateAPI {
    int API_VERSION = 1;
    int API_MINOR_VERSION = 1;

    Path dataDirectory();
    EventBus events();
    PlayerService players();
    AuditService audit();
    CommandRegistry commands();
    PermissionService permissions();

    interface EventBus {
        <T> Subscription subscribe(Class<T> eventType, Consumer<? super T> listener);
    }

    interface Subscription extends AutoCloseable {
        @Override void close();
    }

    interface PlayerService {
        List<Player> onlinePlayers();
        Optional<Player> find(String nameOrUuid);
        boolean sendMessage(String playerName, String message);
        boolean kick(String playerName, String reason);
    }

    record Player(UUID uuid, String name) { }

    interface AuditService {
        /** Writes an extension event to the append-only player activity JSONL log. */
        void log(String eventType, Map<String, ?> fields);
    }

    /** Registers commands handled by NekoGate before they are forwarded to the backend. */
    interface CommandRegistry {
        /**
         * Registers a command without the leading slash. Names are case-insensitive.
         * A non-empty permission node is required; callbacks run on the packet thread and must stay fast. Commands are denied by default
         * unless the player's UUID is granted that node in permissions.properties.
         */
        Subscription register(String name, String permission, String description, CommandExecutor executor);
    }

    @FunctionalInterface
    interface CommandExecutor {
        void execute(CommandContext context) throws Exception;
    }

    /** Context for one invocation of an extension command. */
    interface CommandContext {
        Player player();
        List<String> arguments();
        String input();
        void reply(String message);
    }

    /** Reads configured permission grants for online player UUIDs. */
    interface PermissionService {
        boolean has(Player player, String permission);
    }
}
