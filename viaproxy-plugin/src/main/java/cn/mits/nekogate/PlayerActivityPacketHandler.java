package cn.mits.nekogate;

import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import io.netty.channel.ChannelFutureListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes decoded client chat packets without changing or cancelling them.
 */
final class PlayerActivityPacketHandler extends PacketHandler {
    private final PlayerActivityLogger logger;
    private final AtomicBoolean joined = new AtomicBoolean();
    private volatile Instant joinedAt;

    PlayerActivityPacketHandler(PlayerActivityLogger logger, ProxyConnection connection) {
        super(connection);
        this.logger = logger;
    }

    @Override
    public boolean handleC2P(Packet packet, List<ChannelFutureListener> listeners) {
        if (this.proxyConnection.getC2pConnectionState() != ConnectionState.PLAY) return true;

        String packetName = packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (!packetName.contains("chatcommand") && !packetName.contains("chatmessage")) return true;

        String player = playerName();
        if (player == null) return true;
        String uuid = playerUuid();

        if (packetName.contains("chatcommand")) {
            String command = readString(packet, "command");
            if (command != null && !command.isBlank()) {
                this.logger.logCommand(player, uuid, command.startsWith("/") ? command : "/" + command);
            }
        } else {
            String message = readString(packet, "message");
            if (message == null || message.isBlank()) return true;
            if (message.startsWith("/")) this.logger.logCommand(player, uuid, message);
            else this.logger.logChat(player, uuid, message);
        }
        return true;
    }

    @Override
    public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) {
        if (!this.joined.get() && packet.getClass().getSimpleName().contains("LoginGameProfile")) {
            String player = playerName();
            if (player != null && this.joined.compareAndSet(false, true)) {
                Instant now = Instant.now();
                this.joinedAt = now;
                this.logger.logJoin(player, playerUuid(), now);
            }
        }
        return true;
    }

    void onDisconnect() {
        Instant start = this.joinedAt;
        if (start == null || !this.joined.compareAndSet(true, false)) return;
        String player = playerName();
        if (player != null) this.logger.logLeave(player, playerUuid(), start, Instant.now());
    }

    private String playerName() {
        var profile = this.proxyConnection.getGameProfile();
        return profile == null ? null : profile.getName();
    }

    private String playerUuid() {
        var profile = this.proxyConnection.getGameProfile();
        return profile == null || profile.getId() == null ? null : profile.getId().toString();
    }

    private static String readString(Packet packet, String preferredField) {
        String fallback = null;
        for (Class<?> type = packet.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    if (!field.trySetAccessible()) continue;
                    Object value = field.get(packet);
                    if (!(value instanceof String text)) continue;
                    if (field.getName().equalsIgnoreCase(preferredField)) return text;
                    if (fallback == null) fallback = text;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Packet implementations may hide fields; try the remaining fields.
                }
            }
        }
        return fallback;
    }
}
