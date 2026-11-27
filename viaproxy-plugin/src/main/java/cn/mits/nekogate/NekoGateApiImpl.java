package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;

import java.nio.file.Path;

final class NekoGateApiImpl implements NekoGateAPI {
    private final Path dataDirectory;
    private final NekoGateEventBus events;
    private final PlayerService players;
    private final AuditService audit;
    private final NekoGateCommandManager commands;
    private final NekoGatePermissionManager permissions;

    NekoGateApiImpl(Path dataDirectory, NekoGateEventBus events,
                    PlayerService players, AuditService audit,
                    NekoGateCommandManager commands, NekoGatePermissionManager permissions) {
        this.dataDirectory = dataDirectory;
        this.events = events;
        this.players = players;
        this.audit = audit;
        this.commands = commands;
        this.permissions = permissions;
    }

    @Override public Path dataDirectory() { return this.dataDirectory; }
    @Override public EventBus events() { return this.events; }
    @Override public PlayerService players() { return this.players; }
    @Override public AuditService audit() { return this.audit; }
    @Override public CommandRegistry commands() { return this.commands; }
    @Override public PermissionService permissions() { return this.permissions; }

    void publish(Object event) { this.events.publish(event); }

    boolean dispatchCommand(NekoGateAPI.Player player, String input) {
        return this.commands.dispatch(player, input, message -> {
            if (player != null && player.name() != null) this.players.sendMessage(player.name(), message);
        });
    }

    interface PlayerService extends NekoGateAPI.PlayerService { }
    interface AuditService extends NekoGateAPI.AuditService { }
}
