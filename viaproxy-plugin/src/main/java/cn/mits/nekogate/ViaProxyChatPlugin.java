package cn.mits.nekogate;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.VersionedPacketTransformer;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import io.netty.channel.Channel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ViaProxyChatPlugin extends ViaProxyPlugin {
    private static final int DEFAULT_PORT = 25626;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket serverSocket;
    private volatile VersionedPacketTransformer<ClientboundPackets26_3, ?> chatTransformer;

    @Override
    public void onEnable() {
        int port = readPort();
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            this.serverSocket = server;
            this.running.set(true);
            Thread thread = new Thread(this::acceptLoop, "nekogate-chat-control");
            thread.setDaemon(true);
            thread.start();
            System.out.println("[NekoGateChat] Local chat control listening on 127.0.0.1:" + port);
        } catch (IOException e) {
            throw new IllegalStateException("Could not bind NekoGate chat control port " + port, e);
        }
    }

    @Override
    public void onDisable() {
        this.running.set(false);
        ServerSocket server = this.serverSocket;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
    }

    private static int readPort() {
        String value = System.getenv("NEKOGATE_CONTROL_PORT");
        if (value == null || value.isBlank()) return DEFAULT_PORT;
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException("out of range");
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid NEKOGATE_CONTROL_PORT: " + value, e);
        }
    }

    private void acceptLoop() {
        while (this.running.get()) {
            try {
                Socket socket = this.serverSocket.accept();
                socket.setSoTimeout(3000);
                handle(socket);
            } catch (IOException e) {
                if (this.running.get()) System.err.println("[NekoGateChat] Control socket error: " + e);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null || line.length() > 65536) {
                writeResponse(writer, 0, 0, 1, "empty or oversized request");
                return;
            }
            try {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                String message = request.get("message").getAsString();
                if (message.length() > 8192) {
                    writeResponse(writer, 0, 0, 1, "message too long");
                    return;
                }
                int[] result = broadcast(message);
                writeResponse(writer, result[0], result[1], result[2], null);
            } catch (Exception e) {
                writeResponse(writer, 0, 0, 1, e.toString());
            }
        } catch (IOException e) {
            System.err.println("[NekoGateChat] Failed to handle control request: " + e);
        }
    }

    private static void writeResponse(BufferedWriter writer, int sent, int total, int failed, String error) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("sent", sent);
        response.addProperty("total", total);
        response.addProperty("failed", failed);
        if (error != null) response.addProperty("error", error);
        writer.write(response.toString());
        writer.newLine();
        writer.flush();
    }

    private int[] broadcast(String message) {
        VersionedPacketTransformer<ClientboundPackets26_3, ?> transformer = getChatTransformer();
        CompoundTag component = new CompoundTag();
        component.putString("text", message);
        int total = 0, sent = 0, failed = 0;

        for (Channel channel : ViaProxy.getConnectedClients()) {
            if (!channel.isActive()) continue;
            try {
                ProxyConnection connection = ProxyConnection.fromChannel(channel);
                if (connection == null || connection.isClosed()
                        || connection.getC2pConnectionState() != ConnectionState.PLAY
                        || connection.getP2sConnectionState() != ConnectionState.PLAY) continue;
                UserConnection user = connection.getUserConnection();
                if (user == null) continue;

                total++;
                boolean delivered = transformer.scheduleSend(user, ClientboundPackets26_3.SYSTEM_CHAT, packet -> {
                    // Minecraft 26.3 encodes text components as NBT tags, not JSON components.
                    packet.write(Types.TAG, component);
                    packet.write(Types.BOOLEAN, false);
                });
                if (delivered) sent++; else failed++;
            } catch (Throwable e) {
                failed++;
                System.err.println("[NekoGateChat] Failed to inject chat for " + channel.remoteAddress()
                        + " (client=" + connectionVersion(channel, true)
                        + ", server=" + connectionVersion(channel, false) + ")");
                e.printStackTrace(System.err);
            }
        }
        System.out.println("[NekoGateChat] say delivered=" + sent + "/" + total + " failed=" + failed);
        return new int[]{sent, total, failed};
    }

    private static String connectionVersion(Channel channel, boolean client) {
        try {
            ProxyConnection connection = ProxyConnection.fromChannel(channel);
            if (connection == null) return "unknown";
            ProtocolVersion version = client ? connection.getClientVersion() : connection.getServerVersion();
            return version == null ? "unknown" : version.getName() + " (" + version.getVersion() + ")";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private VersionedPacketTransformer<ClientboundPackets26_3, ?> getChatTransformer() {
        VersionedPacketTransformer<ClientboundPackets26_3, ?> current = this.chatTransformer;
        if (current == null) synchronized (this) {
            current = this.chatTransformer;
            if (current == null) {
                current = Via.getManager().getProtocolManager().createPacketTransformer(
                        ProtocolVersion.v26_3, ClientboundPackets26_3.class, null);
                this.chatTransformer = current;
            }
        }
        return current;
    }
}
