package cn.mits.nekogate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.netminecraft.packet.PacketTypes;

import java.util.List;
import java.util.Map;

/**
 * Adds proxy-owned literal commands and their literal children to the existing
 * Brigadier root node. Backend nodes and their indices are copied unchanged.
 */
final class BrigadierCommandTree {
    private static final int MAX_NODE_COUNT = 100_000;

    private BrigadierCommandTree() { }

    static byte[] appendCommands(byte[] packetData, Map<String, List<String>> commands) {
        if (packetData == null || packetData.length == 0 || commands == null || commands.isEmpty()) {
            return packetData;
        }

        Map<String, List<String>> safeCommands = new java.util.TreeMap<>();
        commands.forEach((name, children) -> {
            if (name == null || !name.matches("[a-z0-9_-]{1,32}")) return;
            List<String> safeChildren = (children == null ? List.<String>of() : children).stream()
                    .filter(child -> child != null && child.matches("[a-z0-9_-]{1,32}"))
                    .distinct()
                    .sorted()
                    .toList();
            safeCommands.put(name, safeChildren);
        });
        if (safeCommands.isEmpty()) return packetData;

        int addedNodes = safeCommands.size()
                + safeCommands.values().stream().mapToInt(List::size).sum();
        ByteBuf input = Unpooled.wrappedBuffer(packetData);
        ByteBuf output = Unpooled.buffer(packetData.length + addedNodes * 48 + 16);
        try {
            int nodeCount = PacketTypes.readVarInt(input);
            if (nodeCount < 1 || nodeCount > MAX_NODE_COUNT
                    || addedNodes > MAX_NODE_COUNT - nodeCount) {
                return packetData;
            }

            int nodesStart = input.readerIndex();
            int rootIndexStart = packetData.length - 1;
            // Brigadier's root node is node zero and the packet ends with root index zero.
            if (rootIndexStart <= nodesStart || packetData[rootIndexStart] != 0) return packetData;

            int rootFlags = input.readUnsignedByte();
            if ((rootFlags & 0x03) != 0 || (rootFlags & 0x08) != 0) return packetData;

            int originalChildCount = PacketTypes.readVarInt(input);
            if (originalChildCount < 0 || originalChildCount > nodeCount
                    || originalChildCount > MAX_NODE_COUNT - safeCommands.size()) {
                return packetData;
            }

            int childrenStart = input.readerIndex();
            for (int i = 0; i < originalChildCount; i++) {
                int childIndex = PacketTypes.readVarInt(input);
                if (childIndex < 0 || childIndex >= nodeCount) return packetData;
            }
            int rootNodeEnd = input.readerIndex();
            if (rootNodeEnd > rootIndexStart) return packetData;

            PacketTypes.writeVarInt(output, nodeCount + addedNodes);
            output.writeByte(rootFlags);
            PacketTypes.writeVarInt(output, originalChildCount + safeCommands.size());
            output.writeBytes(packetData, childrenStart, rootNodeEnd - childrenStart);

            int nextNode = nodeCount;
            for (int i = 0; i < safeCommands.size(); i++) {
                PacketTypes.writeVarInt(output, nextNode + i);
            }

            // Preserve every backend node and its original index byte-for-byte.
            output.writeBytes(packetData, rootNodeEnd, rootIndexStart - rootNodeEnd);

            nextNode = nodeCount + safeCommands.size();
            for (Map.Entry<String, List<String>> command : safeCommands.entrySet()) {
                List<String> children = command.getValue();
                output.writeByte(0x05); // executable literal node
                PacketTypes.writeVarInt(output, children.size());
                for (int i = 0; i < children.size(); i++) {
                    PacketTypes.writeVarInt(output, nextNode++);
                }
                PacketTypes.writeString(output, command.getKey());
            }

            for (List<String> children : safeCommands.values()) {
                for (String child : children) {
                    output.writeByte(0x05); // executable literal node
                    PacketTypes.writeVarInt(output, 0); // no children
                    PacketTypes.writeString(output, child);
                }
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
