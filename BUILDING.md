# Building NekoGate

## Requirements

- Rust stable toolchain
- JDK 17 or newer (Java 25 is supported)
- PowerShell on Windows, or Python 3 on Linux/macOS (for the ViaProxy updater)
- A ViaProxy JAR in the project working directory (either `ViaProxy.jar` or `ViaProxy-3.4.14.jar`)

## Build and run

From the repository root in PowerShell:

```powershell
cargo build --release
cargo run --release -- --log-level DEBUG
```

Before starting ViaProxy, NekoGate checks the latest successful official ViaProxy CI build. If a newer build is available, it downloads the artifact, verifies the GitHub-provided SHA-256 digest, validates the JAR, backs up the old JAR, and replaces it. The check is cached for 24 hours. If the network is unavailable, the existing JAR is kept. Set `NEKOGATE_DISABLE_VIAPROXY_AUTO_UPDATE=1` to disable this behavior.\n\nOn first launch, NekoGate automatically downloads Gradle 9.1.0 when it is not already cached, verifies the official SHA-256 checksum, builds the plugin, and copies it into the ViaProxy `plugins` directory. You do not need to install Gradle or add it to PATH. The Gradle distribution is cached under the user's local cache directory. A compatible JDK (17 or newer) is still required.

The plugin exposes a loopback-only control socket on port 25626; it is not reachable from the LAN.

## Chat injection

The `say` command is handled inside ViaProxy. The plugin creates a System Chat packet using the 1.19.1 protocol model and asks ViaVersion's `VersionedPacketTransformer` to translate and send it for each connected client. This avoids maintaining client-version-specific packet IDs or serializers in Rust.
