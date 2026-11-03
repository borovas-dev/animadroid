#!/usr/bin/env bash
# Sign config/providers.json with an Ed25519 key (OpenSSL 1.1.1+/3.x).
#   tools/sign-config.sh keygen [private.pem]   -> creates the key, prints the public key to paste into RemoteConfigKeys.kt
#   tools/sign-config.sh sign   [private.pem]   -> writes config/providers.json.sig and syncs the bundled copy
#   tools/sign-config.sh pubkey [private.pem]   -> prints the base64 raw public key again
# Keep the private key OUT of the repo (default: ~/.config/animadroid/config-signing.pem).
set -euo pipefail
cd "$(dirname "$0")/.."
cmd="${1:-sign}"
key="${2:-$HOME/.config/animadroid/config-signing.pem}"
json=config/providers.json

pubkey() { openssl pkey -in "$key" -pubout -outform DER | tail -c 32 | base64 -w0; echo; }

case "$cmd" in
  keygen)
    [ -e "$key" ] && { echo "refusing to overwrite $key" >&2; exit 1; }
    mkdir -p "$(dirname "$key")"; umask 077
    openssl genpkey -algorithm ed25519 -out "$key"
    echo "Public key (paste into RemoteConfigKeys.PUBLIC_KEY_BASE64):"; pubkey ;;
  pubkey) pubkey ;;
  sign)
    # Raw detached signature (64 bytes) over the exact file bytes, stored base64.
    openssl pkeyutl -sign -rawin -inkey "$key" -in "$json" | base64 -w0 > "$json.sig"
    cp "$json" core/remoteconfig/src/main/resources/providers.json
    echo "wrote $json.sig (bundled copy synced). Commit both files together." ;;
  *) echo "usage: $0 keygen|sign|pubkey [private.pem]" >&2; exit 2 ;;
esac
