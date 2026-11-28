package cn.mits.nekogate;

import cn.mits.nekogate.api.DisplayNameService;
import cn.mits.nekogate.api.NekoGateAPI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class NekoGateApiImpl implements NekoGateAPI {
    private final Path dataDirectory;
    private final NekoGateEventBus events;
    private final PlayerService players;
    private final AuditService audit;
    private final NekoGateCommandManager commands;
    private final NekoGatePermissionManager permissions;
    private final DisplayNameService displayNames;

    NekoGateApiImpl(Path dataDirectory, NekoGateEventBus events, PlayerService players,
                    AuditService audit, NekoGateCommandManager commands,
                    NekoGatePermissionManager permissions, DisplayNameService displayNames) {
        this.dataDirectory = dataDirectory;
        this.events = events;
        this.players = players;
        this.audit = audit;
        this.commands = commands;
        this.permissions = permissions;
        this.displayNames = displayNames;
    }

    @Override public Path dataDirectory() { return this.dataDirectory; }
    @Override public EventBus events() { return this.events; }
    @Override public PlayerService players() { return this.players; }
    @Override public AuditService audit() { return this.audit; }
    @Override public CommandRegistry commands() { return this.commands; }
    @Override public PermissionService permissions() { return this.permissions; }
    @Override public DisplayNameService displayNames() { return this.displayNames; }

    void publish(Object event) { this.events.publish(event); }

    boolean hasEventListeners(Class<?> eventType) {
        return this.events.hasListeners(eventType);
    }

    Map<String, List<String>> visibleCommands(NekoGateAPI.Player player) {
        return this.commands.visibleCommands(player);
    }
    boolean dispatchCommand(NekoGateAPI.Player player, String input, boolean authenticated) {
        return this.commands.dispatch(player, input, message -> {
            if (player != null && player.name() != null) this.players.sendMessage(player.name(), message);
        }, authenticated);
    }
    interface PlayerService extends NekoGateAPI.PlayerService { }
    interface AuditService extends NekoGateAPI.AuditService { }
}
