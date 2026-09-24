package io.horizontalsystems.monerokit.util

import timber.log.Timber

/**
 * Key derivation in native code. The JCA's PBKDF2 on Android is the pure-Java Bouncy Castle
 * provider: 600k rounds take about a second on a fast core and several on a small one, while
 * the native loop over OpenSSL's SHA-256 core takes tens of milliseconds.
 */
object NativeCrypto {

    /** False when libmonerujo could not be loaded; callers then fall back to the JCA. */
    val isAvailable: Boolean = try {
        System.loadLibrary("monerujo")
        true
    } catch (e: Throwable) {
        Timber.w(e, "NativeCrypto: libmonerujo not loaded")
        false
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018) of the raw [password] bytes. The native copy of the
     * password is wiped before this returns; the caller owns [password] and should wipe it.
     */
    fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        require(iterations >= 1) { "iterations must be >= 1" }
        require(keyLengthBytes in 1..MAX_KEY_LENGTH) { "keyLengthBytes must be in 1..$MAX_KEY_LENGTH" }
        check(isAvailable) { "libmonerujo is not loaded" }
        return pbkdf2HmacSha256J(password, salt, iterations, keyLengthBytes)
            ?: throw IllegalStateException("PBKDF2 failed")
    }

    private const val MAX_KEY_LENGTH = 1024

    @JvmStatic
    private external fun pbkdf2HmacSha256J(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray?
}
