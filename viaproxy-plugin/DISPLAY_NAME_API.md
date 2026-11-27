# Display Name API v1.3

The Java Extension API exposes `NekoGateAPI.displayNames()` for configuring
presentation names. The UUID-based methods from API v1.2 remain available for
backward compatibility; new configurations and extensions should use player
usernames as keys.

## Runtime API

Use the authenticated username directly. This does not require looking up or
supplying a UUID:

```java
api.displayNames().set("MITS_CN", new DisplayName(
    Component.text("[Admin] "),
    Component.gradient("MITS_CN", "#ffee00", "#d31027").withBold(true),
    Component.text(" ★")
));

// Remove only the runtime override:
api.displayNames().clear("MITS_CN");
```

The override changes presentation only. The authenticated username, UUID,
skin properties, and player identity are not changed by the API. Runtime
overrides take precedence over configured entries.

## Configuration

NekoGate creates `./data/name.josn5` when the file does not exist. Use the
player's **backend Minecraft username** as the key under `players`:

```json
{
  "players": {
    "MITS_CN": {
      "prefix": {
        "text": "[Admin] ",
        "color": "#55FFFF"
      },
      "name": {
        "text": "MITS_CN",
        "gradient": ["#ffee00", "#d31027"],
        "bold": true
      },
      "suffix": " ★"
    }
  }
}
```

Username keys are case-insensitive. This is the recommended format because
the ViaProxy packet handler can read the original backend username from the
player-info packet for **every player sent by the backend**, including players
who did not connect through ViaProxy. The viewer receives the rewritten
packet through the tunnel.

### Legacy UUID keys

UUID-keyed entries from API v1.2 remain readable for compatibility. However,
resolving one requires NekoGate's local player service to map that UUID to a
known ViaProxy player. It cannot reliably resolve a UUID-only entry for an
arbitrary backend player who is not connected through the proxy. Migrate
those entries to username keys to cover both tunnel and non-tunnel players.

## Components

Each of `prefix`, `name`, and `suffix` accepts either a plain string or a
component object. Component objects support:

- `text`: displayed text.
- `color`: a HEX color such as `#55FFFF`.
- `gradient`: a two-color array such as `["#ffee00", "#d31027"]`.
- `bold` and `italic`: boolean formatting.
- `extra`: nested component array.

A gradient takes precedence over `color`. Invalid colors fall back to plain
text. Call `api.displayNames().reload()` after editing the file.

## Current protocol behavior

For Minecraft 26.3, the plugin resolves entries by the original backend
username. It applies the rich component to the player-list display name and
the chat sender name. The in-world GameProfile name is a plain string with a
16 UTF-16-code-unit limit, so its formatting is flattened and long values are
truncated. UUIDs, skin properties, chat signatures, and message bodies are
not rewritten. Full rich formatting for in-world name tags requires
Scoreboard Team packet handling and is a separate compatibility concern.
