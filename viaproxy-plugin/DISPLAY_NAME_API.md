# Display Name API v1.2

The Java Extension API keeps its existing v1.1 services and adds
`NekoGateAPI.displayNames()`. Existing extension entry points are unchanged.

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
Calling `clear(UUID)` removes the runtime override.

## Configuration

NekoGate creates `plugins/NekoGate/name.josn5` when it does not exist.
The file currently uses strict JSON syntax:

```json
{
  "players": {
    "00000000-0000-0000-0000-000000000000": {
      "prefix": "[Admin] ",
      "name": "MITS_CN",
      "suffix": " ★"
    }
  }
}
```

Use player UUIDs as keys. Call `api.displayNames().reload()` after editing
the file. Invalid UUID keys and entries without a name are ignored.

## Components

- `Component.text(text)`: plain text.
- `Component.colored(text, "#RRGGBB")`: HEX color.
- `Component.gradient(text, start, end)`: per-Unicode-code-point gradient.
- `Component.join(...)`: combines styled components.
- `withBold` / `withItalic`: applies text formatting.

`chatName(UUID, username)` and `tabName(UUID, username)` return the
resolved presentation component, falling back to the authenticated username.
The API model does not expose ViaVersion's shaded component classes.
