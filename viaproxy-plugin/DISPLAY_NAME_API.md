# Display Name API v1.4

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
the chat sender name. The GameProfile name is always kept as the real backend
username, so command suggestions and commands such as `/tell` continue to use
the actual player identity.

The in-world name tag is rendered separately through the player's entity
metadata (`custom_name` and `custom_name_visible`). This supports the full
prefix/name/suffix component tree, including colors, gradients, bold, italic,
and nested `extra` components, without changing the GameProfile name or
taking over the backend's Scoreboard Team membership. The plugin tracks player
UUIDs to entity IDs from Add Entity packets and reapplies the configured
custom name after backend entity-metadata updates. This path is implemented
for Minecraft 26.3 server packets; actual in-game verification is still
required. UUIDs, skin properties, chat signatures, and message bodies are
not rewritten.
