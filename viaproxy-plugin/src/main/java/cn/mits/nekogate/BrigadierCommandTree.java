package cn.mits.nekogate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.List;

/**
 * Adds proxy-owned literal commands to a backend Brigadier command tree without
 * decoding or rewriting any backend node payloads.
 */
final class BrigadierCommandTree {
    private static final int MAX_NODE_COUNT = 100_000;

    private BrigadierCommandTree() { }

    static byte[] appendCommands(byte[] packetData, List<String> commandNames) {
        if (packetData == null || packetData.length == 0 || commandNames == null || commandNames.isEmpty()) {
            return packetData;
        }

        List<String> names = commandNames.stream()
                .filter(name -> name != null && name.matches("[a-z0-9_-]{1,32}"))
                .distinct()
                .toList();
        if (names.isEmpty()) return packetData;

        ByteBuf input = Unpooled.wrappedBuffer(packetData);
        ByteBuf output = Unpooled.buffer(packetData.length + names.size() * 40 + 16);
        try {
            int nodeCount = PacketTypes.readVarInt(input);
            if (nodeCount < 1 || nodeCount > MAX_NODE_COUNT
                    || names.size() > MAX_NODE_COUNT - nodeCount - 1) {
                return packetData;
            }

            int nodesStart = input.readerIndex();
            // Brigadier serializes its root node first and writes root index 0 as
            // the final single-byte VarInt. Refuse non-standard trees rather than
            // guessing the boundary of an opaque node payload.
            int rootIndexStart = packetData.length - 1;
            if (rootIndexStart < nodesStart || packetData[rootIndexStart] != 0) return packetData;
            int originalRootIndex = 0;

            PacketTypes.writeVarInt(output, nodeCount + names.size() + 1);
            output.writeBytes(packetData, nodesStart, rootIndexStart - nodesStart);

            int firstAddedNode = nodeCount;
            for (String name : names) {
                // Brigadier node type 1 = literal; no children or redirect.
                output.writeByte(0x01);
                PacketTypes.writeVarInt(output, 0);
                PacketTypes.writeString(output, name);
            }

            // A synthetic root keeps the backend root intact as a child, so every
            // backend command remains available alongside proxy-owned commands.
            output.writeByte(0x00);
            PacketTypes.writeVarInt(output, names.size() + 1);
            PacketTypes.writeVarInt(output, originalRootIndex);
            for (int i = 0; i < names.size(); i++) {
                PacketTypes.writeVarInt(output, firstAddedNode + i);
            }
            PacketTypes.writeVarInt(output, nodeCount + names.size());
            byte[] rewritten = new byte[output.readableBytes()];
            output.readBytes(rewritten);
            return rewritten;
        } catch (RuntimeException ignored) {
            return packetData;
        } finally {
            input.release();
            output.release();
        }
    }

}
