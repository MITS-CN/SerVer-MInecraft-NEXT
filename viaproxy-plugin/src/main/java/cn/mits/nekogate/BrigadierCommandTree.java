package cn.mits.nekogate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.List;

/**
 * Adds proxy-owned literal commands to the existing Brigadier root node.
 * Backend nodes and their indices are copied without modification.
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
                    || names.size() > MAX_NODE_COUNT - nodeCount) {
                return packetData;
            }

            int nodesStart = input.readerIndex();
            int rootIndexStart = packetData.length - 1;
            // The Brigadier root node is node zero and the packet ends with root index zero.
            if (rootIndexStart <= nodesStart || packetData[rootIndexStart] != 0) return packetData;

            int rootFlags = input.readUnsignedByte();
            if ((rootFlags & 0x03) != 0 || (rootFlags & 0x08) != 0) return packetData;

            int originalChildCount = PacketTypes.readVarInt(input);
            if (originalChildCount < 0 || originalChildCount > nodeCount
                    || originalChildCount > MAX_NODE_COUNT - names.size()) {
                return packetData;
            }

            int childrenStart = input.readerIndex();
            for (int i = 0; i < originalChildCount; i++) {
                int childIndex = PacketTypes.readVarInt(input);
                if (childIndex < 0 || childIndex >= nodeCount) return packetData;
            }
            int rootNodeEnd = input.readerIndex();
            if (rootNodeEnd > rootIndexStart) return packetData;

            // Rewrite only node zero's child count/list. All existing backend
            // nodes remain byte-for-byte intact and keep their original indices.
            PacketTypes.writeVarInt(output, nodeCount + names.size());
            output.writeByte(rootFlags);
            PacketTypes.writeVarInt(output, originalChildCount + names.size());
            output.writeBytes(packetData, childrenStart, rootNodeEnd - childrenStart);

            int firstAddedNode = nodeCount;
            for (int i = 0; i < names.size(); i++) {
                PacketTypes.writeVarInt(output, firstAddedNode + i);
            }

            // Copy every backend node after the root unchanged.
            output.writeBytes(packetData, rootNodeEnd, rootIndexStart - rootNodeEnd);

            // Append literal nodes before the unchanged root-index field.
            for (String name : names) {
                output.writeByte(0x01); // literal, no executable flag
                PacketTypes.writeVarInt(output, 0); // no children
                PacketTypes.writeString(output, name);
            }
            output.writeByte(0); // root node index

            byte[] rewritten = new byte[output.readableBytes()];
            output.readBytes(rewritten);
            return rewritten;
        } catch (RuntimeException ignored) {
            // Malformed or unfamiliar command trees are forwarded unchanged.
            return packetData;
        } finally {
            input.release();
            output.release();
        }
    }
}
