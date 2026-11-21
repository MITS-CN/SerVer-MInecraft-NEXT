# Building NekoGate

## Requirements

- Rust stable toolchain
- JDK 17 or newer
- Gradle (the ViaProxy plugin is built separately from the Rust executable)
- A ViaProxy JAR named `ViaProxy.jar` in the project working directory

## First build

From the repository root in PowerShell:

```powershell
gradle -p viaproxy-plugin clean build
cargo build --release
```

Then start the proxy as usual:

```powershell
cargo run --release -- --log-level DEBUG
```

NekoGate installs `viaproxy-plugin/build/libs/nekogate-viaproxy-plugin.jar` into the ViaProxy working directory's `plugins` folder before launching ViaProxy. The plugin exposes a loopback-only control socket on port 25626; it is not reachable from the LAN.

## Chat injection

The `say` command is handled inside ViaProxy. The plugin creates a System Chat packet using the 1.19.1 protocol model and asks ViaVersion's `VersionedPacketTransformer` to translate and send it for each connected client. This avoids maintaining client-version-specific packet IDs or serializers in Rust.
