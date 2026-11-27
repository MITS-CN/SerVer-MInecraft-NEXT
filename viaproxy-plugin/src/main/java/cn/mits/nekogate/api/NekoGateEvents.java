package cn.mits.nekogate.api;

import java.time.Instant;
import java.util.Map;

/** Immutable event payloads emitted by the NekoGate core. */
public final class NekoGateEvents {
    private NekoGateEvents() { }

    public interface Event {
        Instant timestamp();
        NekoGateAPI.Player player();
    }

    public record PlayerJoin(Instant timestamp, NekoGateAPI.Player player) implements Event { }
    public record PlayerQuit(Instant timestamp, NekoGateAPI.Player player) implements Event { }
    public record Chat(Instant timestamp, NekoGateAPI.Player player, String message) implements Event { }
    public record ServerChat(Instant timestamp, NekoGateAPI.Player player, String message,
                             String senderUuid, String senderName) implements Event { }
    public record Command(Instant timestamp, NekoGateAPI.Player player, String command) implements Event { }
    public record SystemMessage(Instant timestamp, NekoGateAPI.Player player,
                                String component, boolean overlay) implements Event { }
    public record Whisper(Instant timestamp, NekoGateAPI.Player player, String direction,
                          String recipient, String message, String component) implements Event { }
    public record Custom(Instant timestamp, NekoGateAPI.Player player,
                         String type, Map<String, String> data) implements Event { }
}
