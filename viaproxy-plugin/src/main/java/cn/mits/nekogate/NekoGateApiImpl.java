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
    private final NekoGateAuthService authentication;

    NekoGateApiImpl(Path dataDirectory, NekoGateEventBus events, PlayerService players,
                    AuditService audit, NekoGateCommandManager commands,
                    NekoGatePermissionManager permissions, DisplayNameService displayNames,
                    NekoGateAuthService authentication) {
        this.dataDirectory = dataDirectory;
        this.events = events;
        this.players = players;
        this.audit = audit;
        this.commands = commands;
        this.permissions = permissions;
        this.displayNames = displayNames;
        this.authentication = authentication;
    }

    @Override public Path dataDirectory() { return this.dataDirectory; }
    @Override public EventBus events() { return this.events; }
    @Override public PlayerService players() { return this.players; }
    @Override public AuditService audit() { return this.audit; }
    @Override public CommandRegistry commands() { return this.commands; }
    @Override public PermissionService permissions() { return this.permissions; }
    @Override public DisplayNameService displayNames() { return this.displayNames; }
    @Override public NekoGateAPI.AuthenticationService authentication() { return this.authentication; }

    void publish(Object event) { this.events.publish(event); }

    Map<String, List<String>> visibleCommands(NekoGateAPI.Player player) {
        Map<String, List<String>> visible = new java.util.TreeMap<>(this.commands.visibleCommands(player));
        visible.put("ng", List.of("changepassword", "login", "logout", "register", "status"));
        return Map.copyOf(visible);
    }
    boolean dispatchCommand(NekoGateAPI.Player player, String input, String sessionId) {
        java.util.function.Consumer<String> reply = message -> {
            if (player != null && player.name() != null && this.authentication.isConnectionActive(sessionId)) {
                this.players.sendMessage(player.name(), message);
            }
        };
        if (this.authentication.handleCommand(player, input, sessionId, reply)) return true;
        return this.commands.dispatch(player, input, reply, this.authentication.isAuthenticated(sessionId));
    }
    void openAuthSession(String sessionId) { this.authentication.openConnection(sessionId); }
    void disconnectAuthSession(String sessionId) { this.authentication.disconnect(sessionId); }
    interface PlayerService extends NekoGateAPI.PlayerService { }
    interface AuditService extends NekoGateAPI.AuditService { }
}
