# Building NekoGate

## Requirements

- Rust stable toolchain
- JDK 17 or newer
- Gradle 8.x or newer available as `gradle` on PATH
- A ViaProxy JAR in the project working directory (either `ViaProxy.jar` or `ViaProxy-3.4.14.jar`)

## Build and run

From the repository root in PowerShell:

```powershell
cargo build --release
cargo run --release -- --log-level DEBUG
```

On first launch, NekoGate automatically runs `gradle -p viaproxy-plugin build` when the plugin JAR is missing, then copies the built plugin into the ViaProxy `plugins` directory. You no longer need to build the plugin manually. Gradle and JDK 17+ must be installed and available on PATH.

The plugin exposes a loopback-only control socket on port 25626; it is not reachable from the LAN.

## Chat injection

The `say` command is handled inside ViaProxy. The plugin creates a System Chat packet using the 1.19.1 protocol model and asks ViaVersion's `VersionedPacketTransformer` to translate and send it for each connected client. This avoids maintaining client-version-specific packet IDs or serializers in Rust.
