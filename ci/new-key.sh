#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Once, on the DeskMini: makes Glance's own signing key for the public APK (stock Android; the ROM's built-in Glance
# keeps the platform key). Give it the ROM's release keys' passphrase (the ETESIAN_KEYS_PASS secret), typed or on stdin.
# The key is made in a container's memory and leaves it only encrypted (gpg, symmetric), as
# ${CI_DIR}/keys/glance-key.tar.gpg. Keep a copy of that file somewhere safe: every update must be signed with it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; CI="${CI_DIR:-$HOME/ci}"; OUT="$CI/keys/glance-key.tar.gpg"
[ -e "$OUT" ] && { echo "$OUT already exists; it signs every release, so it is never replaced" >&2; exit 1; }
if [ -t 0 ]; then
    read -rsp "Passphrase: " PASS; echo; read -rsp "Again: " P2; echo
    [ "$PASS" = "$P2" ] || { echo "they differ" >&2; exit 1; }
else PASS=$(cat); fi
docker build -q -t etesian-glance-builder "$HERE" >/dev/null
printf %s "$PASS" | docker run --rm -i --tmpfs /k:size=4m,mode=700,uid=1000,gid=1000 -u 1000:1000 -e HOME=/tmp \
    etesian-glance-builder bash -euo pipefail -c '
PASS=$(cat); cd /k
# The keystore password only guards it inside the encrypted file; gpg and the passphrase are the real lock.
keytool -genkeypair -keystore glance.p12 -storetype PKCS12 -storepass glance -alias glance -keyalg RSA -keysize 4096 \
    -validity 36500 -dname "CN=Etesian Glance" >/dev/null 2>&1
tar -c glance.p12 | gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 -c -o - 3<<<"$PASS"
rm -f glance.p12' > "$OUT.part"
mv "$OUT.part" "$OUT"
(cd "$(dirname "$OUT")" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "made $OUT"
