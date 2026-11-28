#!/usr/bin/env sh
set -eu
VERSION=9.1.0
SHA256=a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

# Prefer a Gradle installation already available on PATH.
if command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
  echo "[ViaProxy] Using system Gradle: $GRADLE"
else
  CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/nekogate/gradle"
  INSTALL="$CACHE/gradle-$VERSION"
  GRADLE="$INSTALL/gradle-$VERSION/bin/gradle"
  if [ ! -x "$GRADLE" ]; then
    mkdir -p "$CACHE"
    ZIP="$CACHE/gradle-$VERSION-bin.zip"
    echo "[ViaProxy] Downloading Gradle $VERSION..."
    curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
    ACTUAL="$(sha256sum "$ZIP" | cut -d ' ' -f 1)"
    [ "$ACTUAL" = "$SHA256" ] || { echo "Gradle SHA-256 mismatch: expected $SHA256, got $ACTUAL" >&2; exit 1; }
    rm -rf "$INSTALL"
    mkdir -p "$INSTALL"
    unzip -q "$ZIP" -d "$INSTALL"
  fi
fi
exec "$GRADLE" -p "$ROOT/viaproxy-plugin" installPlugin
