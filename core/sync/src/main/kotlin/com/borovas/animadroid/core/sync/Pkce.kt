package com.borovas.animadroid.core.sync

import java.security.SecureRandom

/** MAL only supports `code_challenge_method=plain`, so the challenge equals the verifier (RFC 7636: 43..128 unreserved chars). */
object Pkce {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val random = SecureRandom()

    fun verifier(length: Int = 96): String {
        require(length in 43..128)
        return buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }

    fun challenge(verifier: String): String = verifier

    fun state(): String = verifier(43)
}
