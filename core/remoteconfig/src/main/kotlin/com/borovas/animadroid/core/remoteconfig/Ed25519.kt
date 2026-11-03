package com.borovas.animadroid.core.remoteconfig

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Pure-JVM Ed25519 (RFC 8032) signature *verification*. `java.security.Signature("Ed25519")` only exists from
 * Android 13 (API 33) and the app's minSdk is 31, so we carry our own. Verify-only, public inputs, so no
 * constant-time requirement. Affine arithmetic is slow-ish (~ms) but runs once per config fetch.
 */
object Ed25519 {
    private val P = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)
    private val L = BigInteger.valueOf(2).pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val I = BigInteger.valueOf(2).modPow((P - BigInteger.ONE).shiftRight(2), P)

    private class Pt(val x: BigInteger, val y: BigInteger)

    private val ZERO = Pt(BigInteger.ZERO, BigInteger.ONE)
    private val B: Pt = run {
        val y = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
        Pt(recoverX(y, 0)!!, y)
    }

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        val x2 = (y * y - BigInteger.ONE) * (D * y * y + BigInteger.ONE).modInverse(P) % P
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P) != BigInteger.ZERO) x = x * I % P
        if ((x * x - x2).mod(P) != BigInteger.ZERO) return null
        if (x == BigInteger.ZERO && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P - x
        return x
    }

    private fun add(a: Pt, b: Pt): Pt {
        val t = D * a.x * b.x % P * a.y % P * b.y % P
        val x = (a.x * b.y + b.x * a.y) % P * (BigInteger.ONE + t).modInverse(P) % P
        val y = (a.y * b.y + a.x * b.x) % P * (BigInteger.ONE - t).mod(P).modInverse(P) % P
        return Pt(x, y)
    }

    private fun mul(p: Pt, k: BigInteger): Pt {
        var r = ZERO; var q = p
        for (i in 0 until k.bitLength()) { if (k.testBit(i)) r = add(r, q); q = add(q, q) }
        return r
    }

    private fun le(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): BigInteger =
        BigInteger(1, bytes.copyOfRange(off, off + len).reversedArray())

    private fun decode(b: ByteArray): Pt? {
        val sign = (b[31].toInt() shr 7) and 1
        val c = b.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        val y = le(c)
        if (y >= P) return null
        return Pt(recoverX(y, sign) ?: return null, y)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val r = decode(signature.copyOfRange(0, 32)) ?: return false
        val s = le(signature, 32, 32)
        if (s >= L) return false
        val md = MessageDigest.getInstance("SHA-512")
        md.update(signature, 0, 32); md.update(publicKey); md.update(message)
        val h = le(md.digest()).mod(L)
        val lhs = mul(B, s)
        val rhs = add(r, mul(a, h))
        lhs.x == rhs.x && lhs.y == rhs.y
    } catch (_: Exception) { false }
}
