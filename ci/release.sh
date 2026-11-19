#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
# Builds and signs a Glance release on the DeskMini: ci/release.sh <version>, the keys' passphrase on stdin (never as an
# argument or a variable Docker can show). Signed with Etesian's release platform key, the key release ROMs sign
# their built-in Glance with, so a release installs over it and keeps the signature permissions (the launcher reads
# Glance, Glance reads Feeds). The keys (${CI_DIR}/keys/etesian-keys.tar.gpg) are decrypted only inside the container's
# memory (/keys, a tmpfs) and go with it. Out: release/EtesianGlance-<version>.apk and its SHA-256.
set -euo pipefail
V="${1:?version}"; [[ $V =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not a version: $V" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")/.." && pwd)"; CI="${CI_DIR:-$HOME/ci}"; KEYS="$CI/keys/etesian-keys.tar.gpg"
[ -f "$KEYS" ] || { echo "no release keys at $KEYS" >&2; exit 1; }
(cd "$(dirname "$KEYS")" && sha256sum -c --status "$(basename "$KEYS").sha256") || { echo "the keys file is not the one recorded" >&2; exit 1; }
mkdir -p "$HERE/release" "$CI/glance/gradle"
docker build -q -t etesian-glance-builder "$HERE/ci" >/dev/null
docker run --rm -i --tmpfs /keys:size=16m,mode=700,uid=1000,gid=1000 -u 1000:1000 -e HOME=/tmp -e GRADLE_USER_HOME=/gradle \
    -v "$HERE:/glance" -v "$CI/glance/gradle:/gradle" -v "$KEYS:/keys.tar.gpg:ro" -w /glance \
    etesian-glance-builder bash -euo pipefail -c '
PASS=$(cat)
./gradlew --no-daemon -q -PglanceVersion='"$V"' :app:testDebugUnitTest :app:assembleRelease
printf %s "$PASS" | gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 0 -d /keys.tar.gpg | tar -x -C /keys
unset PASS
U=$(ls app/build/outputs/apk/release/*.apk | head -1)
A=release/EtesianGlance-'"$V"'.apk
apksigner sign --v4-signing-enabled false --key /keys/platform.pk8 --cert /keys/platform.x509.pem --out "$A" "$U"
apksigner verify --print-certs "$A" | grep -m1 "certificate SHA-256"
rm -rf /keys/*
cd release && sha256sum "$(basename "$A")" > "$(basename "$A").sha256"'
ls -l "$HERE/release"
