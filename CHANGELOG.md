# Changelog

## Unreleased

### Added
- Add console-only `op` and `deop` commands to grant or revoke persistent NekoGate extension operator access.
- Keep extension permissions denied by default; the generated permission file contains no pre-authorized players.

### Added
- Add opt-out NekoGate authentication performance diagnostics for queue wait, Argon2, SQLite, reply dispatch, heap/GC metrics, and slow synchronous extension stages. Credentials and account names are never logged.

### Fixed
- Remove embedded dependency signature metadata when building the shaded ViaProxy plugin JAR, preventing Java from rejecting the plugin with `Invalid signature file digest for Manifest main attributes`.
