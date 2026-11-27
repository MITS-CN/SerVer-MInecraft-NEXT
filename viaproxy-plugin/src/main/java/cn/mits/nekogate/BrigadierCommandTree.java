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

        ByteBuf input = Unpooled.wrappedBuffer(packetData);
        ByteBuf output = Unpooled.buffer(packetData.length + commandNames.size() * 40 + 16);
        try {
            int nodeCount = PacketTypes.readVarInt(input);
            if (nodeCount < 1 || nodeCount > MAX_NODE_COUNT
                    || commandNames.size() > MAX_NODE_COUNT - nodeCount - 1) {
                return packetData;
            }

            int nodesStart = input.readerIndex();
            int rootIndexStart = findTrailingVarIntStart(packetData);
            if (rootIndexStart < nodesStart) return packetData;

            input.readerIndex(rootIndexStart);
            int originalRootIndex = PacketTypes.readVarInt(input);
            if (input.isReadable() || originalRootIndex < 0 || originalRootIndex >= nodeCount) {
                return packetData;
            }

            PacketTypes.writeVarInt(output, nodeCount + commandNames.size() + 1);
            output.writeBytes(packetData, nodesStart, rootIndexStart - nodesStart);

            int firstAddedNode = nodeCount;
            for (String name : commandNames) {
                if (name == null || !name.matches("[a-z0-9_-]{1,32}")) continue;
                // Brigadier node type 1 = literal; no children or redirect.
                output.writeByte(0x01);
                PacketTypes.writeVarInt(output, 0);
                PacketTypes.writeString(output, name);
            }

            // A synthetic root keeps the backend root intact as a child, so every
            // backend command remains available alongside proxy-owned commands.
            int validAdded = output.readableBytes(); // only used to keep the output path explicit
            output.writeByte(0x00);
            PacketTypes.writeVarInt(output, commandNames.size() + 1);
            PacketTypes.writeVarInt(output, originalRootIndex);
            for (int i = 0; i < commandNames.size(); i++) {
                PacketTypes.writeVarInt(output, firstAddedNode + i);
            }
            PacketTypes.writeVarInt(output, nodeCount + commandNames.size());
            return output.readBytes(output.readableBytes()).array();
        } catch (RuntimeException ignored) {
            return packetData;
        } finally {
            input.release();
            output.release();
        }
    }

    private static int findTrailingVarIntStart(byte[] data) {
        int start = data.length - 1;
        while (start > 0 && (data[start - 1] & 0x80) != 0) start--;
        return start;
    }
}
