# Display Name API v1.2

The Java Extension API keeps its existing v1.1 services and adds
NekoGateAPI.displayNames(). Existing extension entry points are unchanged.

## Runtime API

```java
UUID playerId = api.players().find("MITS_CN").orElseThrow().uuid();
api.displayNames().set(playerId, new DisplayName(
    Component.text("[Admin] "),
    Component.gradient("MITS_CN", "#ffee00", "#d31027"),
    Component.text(" ★")
));
```

The override is presentation-only; the authenticated username and UUID are
not changed. Runtime overrides take precedence over configured entries.
Calling clear(UUID) removes the runtime override.

## Configuration

NekoGate creates ./data/name.josn5 when it does not exist. The file uses
strict JSON syntax and player UUIDs as keys:

```json
{
  "players": {
    "00000000-0000-0000-0000-000000000000": {
      "prefix": {"text": "[Admin] ", "color": "#55FFFF"},
      "name": {"text": "MITS_CN", "gradient": ["#ffee00", "#d31027"], "bold": true},
      "suffix": " ★"
    }
  }
}
```

Each of prefix, name, and suffix accepts either a plain string or a
component object. Component objects support text, color (#RRGGBB),
gradient (a two-color array), bold, and italic. A gradient takes
precedence over color. Invalid colors fall back to plain text.

Call api.displayNames().reload() after editing the file. Invalid UUID keys
and entries without a non-empty name are ignored.

## Components

- Component.text(text): plain text.
- Component.colored(text, "#RRGGBB"): HEX color.
- Component.gradient(text, start, end): per-Unicode-code-point gradient.
- Component.join(...): combines styled components.
- withBold / withItalic: applies text formatting.

For Minecraft 26.3, NekoGate applies the resolved display name to both the
player-info display-name field (Tab list) and the PLAYER_CHAT sender-name
component (chat). Packets that do not match the expected layout are forwarded
unchanged. Authenticated usernames, UUIDs, message bodies, and signatures are
not modified. The API model does not expose ViaVersion's shaded component classes.
