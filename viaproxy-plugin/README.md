# NekoGate ViaProxy chat plugin

The plugin receives local NekoGate console messages and sends a Minecraft 1.19.1-base System Chat packet through ViaVersion's VersionedPacketTransformer. ViaVersion performs client-version translation; Rust no longer guesses packet IDs or chat encodings.

## Build

Requires JDK 17+ and Gradle:

    gradle -p viaproxy-plugin clean build

The resulting JAR is viaproxy-plugin/build/libs/nekogate-viaproxy-plugin.jar.

NekoGate copies it into the ViaProxy working directory's plugins folder before ViaProxy starts. The plugin control listener binds only to loopback on port 25626.

## Player activity audit log

On first startup, the plugin creates `plugins/NekoGate/config.properties` with activity logging disabled:

```properties
logging.enabled=false
logging.chat=true
logging.server_chat=true
logging.system_messages=true
logging.whispers=true
logging.commands=true
logging.connections=true
logging.locations=true
```

Set `logging.enabled=true` and restart ViaProxy to enable it. Player-originated chat, backend player-chat broadcasts, backend system messages, whispers, player-issued commands, connection events, and in-game location logging can be toggled independently. System messages retain their serialized text-component payload and the action-bar/overlay flag. Whisper commands such as `/msg`, `/tell`, `/w`, and `/whisper` are recorded with sender, recipient, and message; incoming vanilla whisper system messages are also marked as received whispers when their translation key is present. Backend chat is observed from clientbound player-chat packets while at least one tunnel client is connected; repeated copies delivered to multiple tunnel clients are deduplicated. The backend sender UUID and cached player name, original message, and message timestamp are stored when available. Events are appended as JSON Lines to `plugins/NekoGate/player-activity.jsonl`; existing records are preserved across restarts.

Join and leave events include the last observed dimension, X/Y/Z coordinates, and yaw/pitch when available. Dimension changes are logged as separate `dimension_change` events. Coordinates are observed from client movement packets; the initial join location may only become available after the client sends its first movement packet. Unsupported or unavailable fields are omitted. Location logging stores session and dimension-change snapshots, not every movement packet.

Chat messages are stored verbatim, and command entries represent commands observed at the proxy rather than confirmed backend execution. Backend player-chat entries use the sender UUID because the clientbound player-chat packet does not carry a plain sender-name field. Protect the log file and establish an appropriate retention policy.
