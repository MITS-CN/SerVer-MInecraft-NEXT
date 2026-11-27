# NekoGate AuthMe Bridge

This companion Bukkit/Paper plugin relays the **actual AuthMeReloaded authentication state** to the NekoGate ViaProxy plugin.

## Why it is required

NekoGate consumes registered extension commands (such as `/nametag`) inside ViaProxy before those commands reach the backend. AuthMe therefore never gets a chance to reject those commands. The bridge closes that gap by reporting AuthMe's state over a server-to-proxy plugin message.

The proxy starts every connection as unauthenticated. Only a status message sent by the backend bridge can unlock extension-command execution. A missing bridge, missing AuthMe API, malformed message, or lost status leaves the player locked.

## Build

Requires JDK 17+ and Gradle:

    gradle -p authme-bridge clean build

Output:

    authme-bridge/build/libs/nekogate-authme-bridge.jar

## Install

1. Keep AuthMeReloaded installed on the backend server.
2. Copy `nekogate-authme-bridge.jar` into the same backend server's `plugins/` directory.
3. Restart the backend server.
4. Keep the NekoGate ViaProxy plugin installed on the proxy.

The bridge has a hard dependency on AuthMe and will not enable without its API.

## Behavior

- New proxy sessions are locked by default.
- Successful AuthMe login/registration unlocks extension commands.
- AuthMe logout locks extension commands again.
- AuthMe unrestricted users and successfully restored sessions are recognized through `AuthMeApi`.
- Disconnect destroys the proxy-side session state.
- The bridge uses the AuthMe API/events; it does not inspect chat text, command strings, login prompts, or player-name permissions to infer authentication.

## Verification

Expected ViaProxy log entries:

    [NekoGate][AUTH] AuthMe state updated: player=Steve authenticated=false
    [NekoGate][AUTH] AuthMe state updated: player=Steve authenticated=true

Before authentication, an extension command is consumed and rejected with an authentication-required message. AuthMe commands such as `/login` continue to the backend normally.
