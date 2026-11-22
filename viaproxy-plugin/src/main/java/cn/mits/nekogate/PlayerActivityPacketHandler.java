package cn.mits.nekogate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCVersion;
import net.raphimc.netminecraft.constants.PacketDirection;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes player activity and movement without changing or cancelling packets.
 */
final class PlayerActivityPacketHandler extends PacketHandler {
    private final PlayerActivityLogger logger;
    private final Map<String, String> backendPlayerNames = new ConcurrentHashMap<>();
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
        if (packet instanceof UnknownPacket unknown) {
            int serverProtocol = this.proxyConnection.getServerVersion().getVersion();
            MCPackets type = MCPackets.getPacket(
                    ConnectionState.PLAY,
                    PacketDirection.CLIENTBOUND,
                    serverProtocol,
                    unknown.packetId
            );
            if (serverProtocol >= MCVersion.v26_3) {
                if (type == MCPackets.S2C_PLAYER_INFO_UPDATE) {
                    updateBackendPlayerNames(unknown.data);
                } else if (type == MCPackets.S2C_PLAYER_INFO_REMOVE) {
                    removeBackendPlayerNames(unknown.data);
                }
            }
            if (this.logger.isServerChatLoggingEnabled() && type == MCPackets.S2C_PLAYER_CHAT) {
                logRawServerChat(unknown.data, serverProtocol);
            }
            if (this.logger.isSystemMessageLoggingEnabled() && type == MCPackets.S2C_SYSTEM_CHAT) {
                logRawSystemMessage(unknown.data);
            }
            if (this.logger.isLocationLoggingEnabled()
                    && (type == MCPackets.S2C_LOGIN || type == MCPackets.S2C_RESPAWN)) {
                updateDimension(extractDimension(type, unknown.data, serverProtocol));
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

    private void logRawServerChat(byte[] data, int protocolVersion) {
        if (data == null || data.length == 0) return;
        try {
            ByteBuf buffer = Unpooled.wrappedBuffer(data);
            try {
                // Minecraft 1.21.9 added a per-client global chat index before the sender UUID.
                if (protocolVersion >= MCVersion.v1_21_9) {
                    PacketTypes.readVarInt(buffer);
                }
                UUID sender = PacketTypes.readUuid(buffer);
                // The sender's own connection is already covered by the client-to-proxy chat log.
                // Another tunnel client's connection can still observe and record this broadcast.
                String receivingPlayerUuid = playerUuid();
                if (sender.toString().equalsIgnoreCase(receivingPlayerUuid == null ? "" : receivingPlayerUuid)) return;
                PacketTypes.readVarInt(buffer); // Sender message index
                if (buffer.readBoolean()) {
                    buffer.skipBytes(256); // Optional message signature
                }
                String message = PacketTypes.readString(buffer, 256);
                long messageTimestamp = buffer.readLong();
                this.logger.logServerChat(sender.toString(), this.backendPlayerNames.get(sender.toString()), message, messageTimestamp);
            } finally {
                buffer.release();
            }
        } catch (RuntimeException ignored) {
            // Unsupported or malformed backend chat payloads must never affect forwarding.
        }
    }


    /**
     * Minecraft 26.3 player_info_update: action flags (byte), entry count,
     * then UUID and the payloads selected by each action bit in ordinal order.
     */
    private void updateBackendPlayerNames(byte[] data) {
        if (data == null || data.length == 0) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            int actions = buffer.readUnsignedByte();
            int count = PacketTypes.readVarInt(buffer);
            if (count < 0 || count > 4096) return;

            for (int i = 0; i < count; i++) {
                UUID playerId = PacketTypes.readUuid(buffer);
                if ((actions & 0x01) != 0) {
                    String name = PacketTypes.readString(buffer, 16);
                    if (name != null && !name.isBlank()) {
                        this.backendPlayerNames.put(playerId.toString(), name);
                    }
                    int properties = PacketTypes.readVarInt(buffer);
                    if (properties < 0 || properties > 1024) return;
                    for (int property = 0; property < properties; property++) {
                        PacketTypes.readString(buffer, 64); // Property name
                        PacketTypes.readString(buffer, 32767); // Property value
                        if (buffer.readBoolean()) {
                            PacketTypes.readString(buffer, 1024); // Optional signature
                        }
                    }
                }
                if ((actions & 0x02) != 0 && buffer.readBoolean()) {
                    PacketTypes.readUuid(buffer); // Chat session ID
                    buffer.readLong(); // Public key expiry
                    skipLengthPrefixedBytes(buffer); // Encoded public key
                    skipLengthPrefixedBytes(buffer); // Public key signature
                }
                if ((actions & 0x04) != 0) PacketTypes.readVarInt(buffer); // Game mode
                if ((actions & 0x08) != 0) buffer.readBoolean(); // Listed
                if ((actions & 0x10) != 0) PacketTypes.readVarInt(buffer); // Latency
                if ((actions & 0x20) != 0) Types.OPTIONAL_TAG.read(buffer); // Display name component
                if ((actions & 0x40) != 0) PacketTypes.readVarInt(buffer); // List order
                if ((actions & 0x80) != 0) buffer.readBoolean(); // Show hat
            }
        } catch (RuntimeException ignored) {
            // Never interfere with forwarding if a backend sends an unknown packet layout.
        } finally {
            buffer.release();
        }
    }

    private void removeBackendPlayerNames(byte[] data) {
        if (data == null || data.length == 0) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            int count = PacketTypes.readVarInt(buffer);
            if (count < 0 || count > 4096) return;
            for (int i = 0; i < count; i++) {
                this.backendPlayerNames.remove(PacketTypes.readUuid(buffer).toString());
            }
        } catch (RuntimeException ignored) {
            // Ignore malformed player-list updates; packet forwarding remains untouched.
        } finally {
            buffer.release();
        }
    }

    private static void skipLengthPrefixedBytes(ByteBuf buffer) {
        int length = PacketTypes.readVarInt(buffer);
        if (length < 0 || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid length-prefixed byte array");
        }
        buffer.skipBytes(length);
    }


    private void logRawSystemMessage(byte[] data) {
        if (data == null || data.length == 0) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            Object component = Types.TAG.read(buffer);
            boolean overlay = buffer.readBoolean();
            String serialized = component == null ? "" : component.toString();
            if (!serialized.isBlank()) {
                this.logger.logSystemMessage(serialized, overlay);
                // Vanilla /msg, /tell and /w responses use these translation keys.
                if (serialized.contains("commands.message.display.incoming")) {
                    this.logger.logReceivedWhisper(serialized);
                }
            }
        } catch (RuntimeException ignored) {
            // Ignore unsupported component encodings without affecting packet forwarding.
        } finally {
            buffer.release();
        }
    }

    private void logWhisperCommand(String sender, String senderUuid, String command) {
        String value = command.startsWith("/") ? command.substring(1) : command;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)^(?:minecraft:)?(?:msg|tell|w|whisper)\\s+(\\S+)\\s+(.+)$"
        ).matcher(value);
        if (matcher.matches()) {
            this.logger.logWhisper(sender, senderUuid, matcher.group(1), matcher.group(2), "sent");
        }
    }

    private void logRawChat(MCPackets type, byte[] data) {
        if (data == null || data.length == 0) return;
        try {
            ByteBuf buffer = Unpooled.wrappedBuffer(data);
            String value;
            try {
                value = PacketTypes.readString(buffer, 32767);
            } finally {
                buffer.release();
            }
            if (value == null || value.isBlank()) return;

            String player = playerName();
            if (player == null) return;
            if (type == MCPackets.C2S_CHAT) {
                this.logger.logChat(player, playerUuid(), value);
            } else {
                String command = value.startsWith("/") ? value : "/" + value;
                this.logger.logCommand(player, playerUuid(), command);
                logWhisperCommand(player, playerUuid(), command);
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

    private static String extractDimension(MCPackets type, byte[] data, int protocolVersion) {
        if (data == null || data.length == 0 || protocolVersion < MCVersion.v1_20_2) return null;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            if (type == MCPackets.S2C_LOGIN) {
                buffer.readInt(); // Entity ID
                buffer.readBoolean(); // Hardcore
                int dimensionCount = PacketTypes.readVarInt(buffer);
                if (dimensionCount < 0 || dimensionCount > 4096) return null;
                for (int i = 0; i < dimensionCount; i++) {
                    PacketTypes.readString(buffer, 32767);
                }
                PacketTypes.readVarInt(buffer); // Max players
                PacketTypes.readVarInt(buffer); // View distance
                PacketTypes.readVarInt(buffer); // Simulation distance
                buffer.readBoolean(); // Reduced debug info
                buffer.readBoolean(); // Show respawn screen
                buffer.readBoolean(); // Do limited crafting
                PacketTypes.readVarInt(buffer); // Dimension type
                return PacketTypes.readString(buffer, 32767); // Current dimension identifier
            }
            if (type == MCPackets.S2C_RESPAWN) {
                PacketTypes.readVarInt(buffer); // Dimension type
                return PacketTypes.readString(buffer, 32767); // Current dimension identifier
            }
        } catch (RuntimeException ignored) {
            // Older or non-standard packet layouts are left without a dimension value.
        } finally {
            buffer.release();
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
