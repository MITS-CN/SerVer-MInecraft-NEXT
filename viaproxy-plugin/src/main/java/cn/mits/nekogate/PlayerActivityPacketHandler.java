package cn.mits.nekogate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.PacketDirection;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Observes player activity and movement without changing or cancelling packets.
 */
final class PlayerActivityPacketHandler extends PacketHandler {
    private static final Pattern DIMENSION_KEY =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final PlayerActivityLogger logger;
    private final AtomicBoolean joined = new AtomicBoolean();
    private final AtomicBoolean joinLogged = new AtomicBoolean();
    private volatile Instant joinedAt;
    private volatile String dimension;
    private volatile Double x;
    private volatile Double y;
    private volatile Double z;
    private volatile Float yaw;
    private volatile Float pitch;

    PlayerActivityPacketHandler(PlayerActivityLogger logger, ProxyConnection connection) {
        super(connection);
        this.logger = logger;
    }

    @Override
    public boolean handleC2P(Packet packet, List<ChannelFutureListener> listeners) {
        if (this.proxyConnection.getC2pConnectionState() != ConnectionState.PLAY) return true;

        if (packet instanceof UnknownPacket unknown) {
            MCPackets type = MCPackets.getPacket(
                    ConnectionState.PLAY,
                    PacketDirection.SERVERBOUND,
                    this.proxyConnection.getClientVersion().getVersion(),
                    unknown.packetId
            );
            if (type == null) return true;

            if (this.logger.isLocationLoggingEnabled()) {
                updateMovement(type, unknown.data);
            }
            if (type == MCPackets.C2S_CHAT || type == MCPackets.C2S_CHAT_COMMAND
                    || type == MCPackets.C2S_CHAT_COMMAND_SIGNED) {
                logRawChat(type, unknown.data);
            }
            return true;
        }

        // Keep compatibility with packet implementations that expose decoded fields.
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
        if (packet instanceof UnknownPacket unknown && this.logger.isLocationLoggingEnabled()) {
            MCPackets type = MCPackets.getPacket(
                    ConnectionState.PLAY,
                    PacketDirection.CLIENTBOUND,
                    this.proxyConnection.getServerVersion().getVersion(),
                    unknown.packetId
            );
            if (type == MCPackets.S2C_LOGIN || type == MCPackets.S2C_RESPAWN) {
                updateDimension(extractDimension(unknown.data));
            }
        }

        if (!this.joined.get() && packet.getClass().getSimpleName().contains("LoginGameProfile")) {
            String player = playerName();
            if (player != null && this.joined.compareAndSet(false, true)) {
                this.joinedAt = Instant.now();
                logJoinIfReady();
            }
        }
        return true;
    }

    void onDisconnect() {
        Instant start = this.joinedAt;
        if (start == null || !this.joined.compareAndSet(true, false)) return;

        String player = playerName();
        if (player == null) return;
        if (this.joinLogged.compareAndSet(false, true)) {
            this.logger.logJoin(player, playerUuid(), start, this.dimension, this.x, this.y, this.z, this.yaw, this.pitch);
        }
        this.logger.logLeave(player, playerUuid(), start, Instant.now(),
                this.dimension, this.x, this.y, this.z, this.yaw, this.pitch);
    }

    private void logRawChat(MCPackets type, byte[] data) {
        if (data == null || data.length == 0) return;
        try {
            ByteBuf buffer = Unpooled.wrappedBuffer(data);
            String value;
            try {
                value = PacketTypes.readString(buffer, 256);
            } finally {
                buffer.release();
            }
            if (value == null || value.isBlank()) return;

            String player = playerName();
            if (player == null) return;
            if (type == MCPackets.C2S_CHAT) {
                this.logger.logChat(player, playerUuid(), value);
            } else {
                this.logger.logCommand(player, playerUuid(), value.startsWith("/") ? value : "/" + value);
            }
        } catch (RuntimeException ignored) {
            // Malformed or version-specific payloads are ignored; the packet still passes through.
        }
    }

    private void updateMovement(MCPackets type, byte[] data) {
        if (data == null) return;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            switch (type) {
                case C2S_MOVE_PLAYER_POS -> {
                    if (buffer.remaining() < 24) return;
                    this.x = buffer.getDouble();
                    this.y = buffer.getDouble();
                    this.z = buffer.getDouble();
                }
                case C2S_MOVE_PLAYER_POS_ROT -> {
                    if (buffer.remaining() < 32) return;
                    this.x = buffer.getDouble();
                    this.y = buffer.getDouble();
                    this.z = buffer.getDouble();
                    this.yaw = buffer.getFloat();
                    this.pitch = buffer.getFloat();
                }
                case C2S_MOVE_PLAYER_ROT -> {
                    if (buffer.remaining() < 8) return;
                    this.yaw = buffer.getFloat();
                    this.pitch = buffer.getFloat();
                }
                default -> {
                    return;
                }
            }
            logJoinIfReady();
        } catch (RuntimeException ignored) {
            // Ignore malformed or unsupported movement payloads.
        }
    }

    private void updateDimension(String nextDimension) {
        if (nextDimension == null || nextDimension.isBlank()) return;
        String previous = this.dimension;
        if (nextDimension.equals(previous)) return;
        this.dimension = nextDimension;

        if (previous != null && this.joined.get()) {
            String player = playerName();
            if (player != null) {
                this.logger.logDimensionChange(player, playerUuid(), previous, nextDimension,
                        this.x, this.y, this.z, this.yaw, this.pitch);
            }
        }
    }

    private void logJoinIfReady() {
        Instant start = this.joinedAt;
        if (start == null || !this.joined.get() || !this.joinLogged.compareAndSet(false, true)) return;
        String player = playerName();
        if (player != null) {
            this.logger.logJoin(player, playerUuid(), start,
                    this.dimension, this.x, this.y, this.z, this.yaw, this.pitch);
        }
    }

    private static String extractDimension(byte[] data) {
        if (data == null || data.length == 0) return null;
        String payload = new String(data, StandardCharsets.ISO_8859_1);
        Matcher matcher = DIMENSION_KEY.matcher(payload);
        while (matcher.find()) {
            String key = matcher.group();
            if (key.startsWith("minecraft:") || key.indexOf(':') > 0) return key;
        }
        return null;
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
            for (var field : type.getDeclaredFields()) {
                if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
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
