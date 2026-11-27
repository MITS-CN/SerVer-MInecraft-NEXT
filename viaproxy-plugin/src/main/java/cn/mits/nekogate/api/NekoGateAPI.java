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

    Path dataDirectory();
    EventBus events();
    PlayerService players();
    AuditService audit();

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
}
