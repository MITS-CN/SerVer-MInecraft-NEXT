package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;

import java.nio.file.Path;
import java.util.Map;

final class NekoGateApiImpl implements NekoGateAPI {
    private final Path dataDirectory;
    private final NekoGateEventBus events;
    private final PlayerService players;
    private final AuditService audit;

    NekoGateApiImpl(Path dataDirectory, NekoGateEventBus events,
                    PlayerService players, AuditService audit) {
        this.dataDirectory = dataDirectory;
        this.events = events;
        this.players = players;
        this.audit = audit;
    }

    @Override public Path dataDirectory() { return this.dataDirectory; }
    @Override public EventBus events() { return this.events; }
    @Override public PlayerService players() { return this.players; }
    @Override public AuditService audit() { return this.audit; }

    void publish(Object event) { this.events.publish(event); }

    interface PlayerService extends NekoGateAPI.PlayerService { }
    interface AuditService extends NekoGateAPI.AuditService { }
}
