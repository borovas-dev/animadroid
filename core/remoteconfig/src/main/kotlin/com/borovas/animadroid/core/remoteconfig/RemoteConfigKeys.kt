package com.borovas.animadroid.core.remoteconfig

import java.util.Base64

object RemoteConfigKeys {
    /**
     * PLACEHOLDER - OWNER TODO. Base64 of the raw 32-byte Ed25519 public key used to verify
     * `config/providers.json.sig`. Generate your own pair and paste the output here; see config/README.md.
     * While this is the placeholder every remote document fails verification (release builds then use the
     * cache/bundled copy; debug builds accept unsigned documents).
     */
    const val PUBLIC_KEY_BASE64 = "PLACEHOLDER_REPLACE_WITH_BASE64_ED25519_PUBLIC_KEY"

    /**
     * TEMPORARY (owner decision, 2026-11-03): accept `providers.json` without a signature in RELEASE builds too.
     * The config is fetched over HTTPS from the owner's GitHub repo, so trust rests on that account. A `.sig` that is
     * present but wrong is still rejected. To enforce signing again: set this to false, run
     * `tools/sign-config.sh keygen`, paste the public key above, and sign each config change.
     */
    const val ALLOW_UNSIGNED_CONFIG = true

    const val CONFIG_URL = "https://raw.githubusercontent.com/borovas-dev/animadroid/main/config/providers.json"
    const val SIGNATURE_URL = "$CONFIG_URL.sig"
    const val GITHUB_REPO = "borovas-dev/animadroid"

    /** Decoded key or null while still the placeholder / malformed. */
    fun publicKey(b64: String = PUBLIC_KEY_BASE64): ByteArray? =
        runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()?.takeIf { it.size == 32 }
}
