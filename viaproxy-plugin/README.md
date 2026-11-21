# NekoGate ViaProxy chat plugin

The plugin receives local NekoGate console messages and sends a Minecraft 1.19.1-base System Chat packet through ViaVersion's VersionedPacketTransformer. ViaVersion performs client-version translation; Rust no longer guesses packet IDs or chat encodings.

## Build

Requires JDK 17+ and Gradle:

    gradle -p viaproxy-plugin clean build

The resulting JAR is viaproxy-plugin/build/libs/nekogate-viaproxy-plugin.jar.

NekoGate copies it into the ViaProxy working directory's plugins folder before ViaProxy starts. The plugin control listener binds only to loopback on port 25626.
