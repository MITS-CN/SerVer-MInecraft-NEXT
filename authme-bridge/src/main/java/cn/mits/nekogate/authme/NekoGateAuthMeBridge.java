package cn.mits.nekogate.authme;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class NekoGateAuthMeBridge extends JavaPlugin implements Listener {
    private static final String CHANNEL = "nekogate:auth";
    private static final int PAYLOAD_VERSION = 1;
    private static final int JOIN_SYNC_ATTEMPTS = 40;

    @Override
    public void onEnable() {
        if (AuthMeApi.getInstance() == null) {
            getLogger().severe("AuthMe API is unavailable. Extension commands will remain locked (fail closed).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AuthMe bridge enabled; NekoGate extension commands require AuthMe authentication.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // AuthMe may restore a session asynchronously. Keep the proxy locked until
        // the API reports authenticated or unrestricted for this exact connection.
        syncAfterJoin(event.getPlayer(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        sendState(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogout(LogoutEvent event) {
        sendState(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRestoreSession(RestoreSessionEvent event) {
        // RestoreSessionEvent is fired before restoration completes.
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> syncCurrentState(player));
    }

    private void syncAfterJoin(Player player, int attempt) {
        if (!player.isOnline()) return;
        if (syncCurrentState(player)) return;
        if (attempt + 1 < JOIN_SYNC_ATTEMPTS) {
            Bukkit.getScheduler().runTaskLater(this, () -> syncAfterJoin(player, attempt + 1), 1L);
        }
    }

    private boolean syncCurrentState(Player player) {
        if (!player.isOnline()) return false;
        AuthMeApi api = AuthMeApi.getInstance();
        if (api == null) return false;

        boolean authenticated;
        try {
            authenticated = api.isAuthenticated(player) || api.isUnrestricted(player);
        } catch (RuntimeException error) {
            getLogger().warning("Could not query AuthMe state for " + player.getName() + ": " + error);
            return false;
        }
        sendState(player, authenticated);
        return authenticated;
    }

    private void sendState(Player player, boolean authenticated) {
        if (player == null || !player.isOnline()) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(2);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(PAYLOAD_VERSION);
                output.writeByte(authenticated ? 1 : 0);
            }
            player.sendPluginMessage(this, CHANNEL, bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode AuthMe state", impossible);
        }
    }
}
