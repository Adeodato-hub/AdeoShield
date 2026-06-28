package es.adeodato.dnsguardian.security

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinManager {
    private const val PREFS = "guardian_secure_prefs"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_FAILS = "pin_fails"
    private const val KEY_LOCK_UNTIL = "pin_lock_until"

    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 60_000L  // 1 min de bloqueo tras MAX_FAILS

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPinSet(ctx: Context): Boolean = prefs(ctx).contains(KEY_HASH)

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt)
        prefs(ctx).edit {
            putString(KEY_SALT, b64(salt))
            putString(KEY_HASH, b64(hash))
            remove(KEY_FAILS); remove(KEY_LOCK_UNTIL)
        }
    }

    sealed class Result {
        object Ok : Result()
        data class Wrong(val remaining: Int) : Result()
        data class Locked(val msLeft: Long) : Result()
        object NoPin : Result()
    }

    fun verifyPin(ctx: Context, pin: String): Result {
        val p = prefs(ctx)
        if (!isPinSet(ctx)) return Result.NoPin

        val now = System.currentTimeMillis()
        val lockUntil = p.getLong(KEY_LOCK_UNTIL, 0L)
        if (now < lockUntil) return Result.Locked(lockUntil - now)

        val salt = b64d(p.getString(KEY_SALT, null) ?: return Result.NoPin)
        val stored = p.getString(KEY_HASH, null) ?: return Result.NoPin
        val candidate = b64(pbkdf2(pin.toCharArray(), salt))

        return if (constantTimeEquals(candidate, stored)) {
            p.edit { remove(KEY_FAILS); remove(KEY_LOCK_UNTIL) }
            Result.Ok
        } else {
            val fails = p.getInt(KEY_FAILS, 0) + 1
            if (fails >= MAX_FAILS) {
                p.edit { putInt(KEY_FAILS, 0); putLong(KEY_LOCK_UNTIL, now + LOCK_MS) }
                Result.Locked(LOCK_MS)
            } else {
                p.edit { putInt(KEY_FAILS, fails) }
                Result.Wrong(MAX_FAILS - fails)
            }
        }
    }

    private fun pbkdf2(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = a.toByteArray(); val bb = b.toByteArray()
        if (ba.size != bb.size) return false
        var r = 0
        for (i in ba.indices) r = r or (ba[i].toInt() xor bb[i].toInt())
        return r == 0
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)
}