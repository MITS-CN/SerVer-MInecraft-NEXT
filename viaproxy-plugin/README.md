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
# Enable rate-limited diagnostics for rejected system-chat NBT payloads.
# NBT safety checks are always active, even when this is false.
debug.nbt=false
# Enable login packet diagnostics after /login.
debug.login=false
```

Set `logging.enabled=true` and restart ViaProxy to enable it. Set `debug.nbt=true` to print rate-limited `[NekoGate][NBT-DIAG]` details when a system-chat NBT payload is rejected; it defaults to `false` to keep normal logs quiet. This option controls diagnostics only: the NBT size, array-length, nesting, and tag-count safety checks always remain active. Set `debug.login=true` to print rate-limited `[NekoGate][LOGIN-DIAG]` events and packet counters after a player's `/login` command; it defaults to `false`. This controls diagnostic output only and does not affect command forwarding or activity auditing. Restart ViaProxy after changing either debug setting. Player-originated chat, backend player-chat broadcasts, backend system messages, whispers, player-issued commands, connection events, and in-game location logging can be toggled independently. System messages retain their serialized text-component payload and the action-bar/overlay flag. Whisper commands such as `/msg`, `/tell`, `/w`, and `/whisper` are recorded with sender, recipient, and message; incoming vanilla whisper system messages are also marked as received whispers when their translation key is present. Backend chat is observed from clientbound player-chat packets while at least one tunnel client is connected; repeated copies delivered to multiple tunnel clients are deduplicated. The backend sender UUID and cached player name, original message, and message timestamp are stored when available. Events are appended as JSON Lines to `plugins/NekoGate/player-activity.jsonl`; existing records are preserved across restarts.

Join and leave events include the last observed dimension, X/Y/Z coordinates, and yaw/pitch when available. Dimension changes are logged as separate `dimension_change` events. Coordinates are observed from client movement packets; the initial join location may only become available after the client sends its first movement packet. Unsupported or unavailable fields are omitted. Location logging stores session and dimension-change snapshots, not every movement packet.

Chat messages are stored verbatim, and command entries represent commands observed at the proxy rather than confirmed backend execution. Backend player-chat entries use the sender UUID because the clientbound player-chat packet does not carry a plain sender-name field. Protect the log file and establish an appropriate retention policy.


## Java Extension API (API v1.1)

NekoGate can load trusted third-party Java extensions from:

    plugins/NekoGate/extensions/

Place extension JARs in that directory and restart ViaProxy. Extensions are discovered with Java's `ServiceLoader`; each JAR must contain:

    META-INF/services/cn.mits.nekogate.api.NekoGateExtension

The file contains the fully qualified class name of each extension provider (one per line). Providers implement `NekoGateExtension` and receive the public `NekoGateAPI` instance in `onEnable`. `onDisable` is called during shutdown. Event subscriptions should be closed in `onDisable`.

### Build the API

Requires JDK 17+ and Gradle:

    gradle -p viaproxy-plugin apiJar

The standalone compile-time API artifact is:

    viaproxy-plugin/build/libs/nekogate-api-1.0.0.jar

Compile your extension against this JAR (for example, Gradle `compileOnly`) and do not shade or bundle it into your extension. The API package is versioned independently using `NekoGateAPI.API_VERSION`.

### Available services and events

- `api.players().onlinePlayers()` and `find(nameOrUuid)` return player snapshots.
- `api.players().sendMessage(name, message)` and `kick(name, reason)` expose basic player actions.
- `api.events().subscribe(EventType.class, listener)` subscribes to events and returns an `AutoCloseable` subscription.
- `api.audit().log(type, fields)` appends a custom `extension:<type>` JSONL record to the player activity log. The global `logging.enabled` setting must be enabled.
- Core events: `PlayerJoin`, `PlayerQuit`, `Chat`, `ServerChat`, `Command`, `SystemMessage`, and `Whisper`.

Example listener:

```java
public final class WelcomeExtension implements NekoGateExtension {
    private NekoGateAPI.Subscription subscription;

    @Override
    public void onEnable(NekoGateAPI api) {
        subscription = api.events().subscribe(NekoGateEvents.PlayerJoin.class, event -> {
            api.players().sendMessage(event.player().name(), "Welcome!");
            api.audit().log("welcome_sent", Map.of("player", event.player().name()));
        });
    }

    @Override
    public void onDisable() {
        if (subscription != null) subscription.close();
    }
}
```


### Registering a command

Commands are registered during `onEnable` and should be unregistered during `onDisable`:

```java
public final class HelloExtension implements NekoGateExtension {
    private NekoGateAPI.Subscription command;

    @Override
    public void onEnable(NekoGateAPI api) {
        command = api.commands().register(
            "hello",
            "example.hello",
            "Sends a greeting",
            context -> context.reply("Hello, " + context.player().name() + "!")
        );
    }

    @Override
    public void onDisable() {
        if (command != null) command.close();
    }
}
```

Players run this command as `/hello`. Arguments are available through `context.arguments()`; quoted arguments are supported. The command is intercepted only when an extension has registered that name. Unregistered commands continue to the backend as usual.

### Client-side command completion

Extension commands registered with `api.commands().register(...)` are now added to each eligible player's client-side Brigadier command tree when the backend sends its command tree. Visibility follows the command's configured permission, so players without permission do not receive the extension command in tab completion. The existing Brigadier root node is extended with proxy-owned command entries; all backend nodes and their indices are preserved.

This command-tree injection applies to Minecraft protocol versions that support Brigadier command trees (1.13 and newer). The current registration API publishes the extension command's root literal; extensions that need structured subcommand and argument suggestions will need to provide syntax metadata in a future API addition. Command execution and permission checks remain authoritative on the proxy and do not rely on client-side visibility.

### Permission configuration

On startup NekoGate creates `plugins/NekoGate/permissions.properties`. Permissions are denied by default: the generated file contains comments only and grants no player any extension permission.

Manage full access from either Rust console entry (standard input or the local TCP console at `127.0.0.1:25625`):

```text
op Steve
deop Steve
```

`op` grants the `*` permission node and `deop` removes that grant. Both commands work for online or offline player names; UUIDs are accepted when the player is offline or when targeting a UUID directly. For an online player identified by UUID, the stored key is their current player name. Changes are written immediately and take effect without restarting ViaProxy. These commands are not registered as Minecraft in-game commands and cannot be run by players.

The file also supports manual permission grants by player name (case-insensitive) or UUID:

```properties
# Exact permission
Steve=example.hello

# Namespace wildcard
Alex=example.*
```

Multiple nodes are comma-separated. A `namespace.*` grant includes all permission nodes under that namespace. Manual file edits are loaded at ViaProxy startup; restart ViaProxy after editing.
Extensions run inside the ViaProxy process with the same operating-system permissions as NekoGate. Only install JARs from sources you trust. API major version remains 1; this release adds the command and permission services compatibly.
