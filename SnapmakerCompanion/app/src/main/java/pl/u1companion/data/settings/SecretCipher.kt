package pl.u1companion.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Szyfrowanie sekretów (klucze API, tokeny) kluczem AES-256-GCM z Android Keystore.
 * Klucz nie opuszcza sprzętowego/systemowego magazynu; w DataStore trzymamy tylko "enc1:<iv>:<szyfrogram>".
 * Wartość bez prefiksu traktujemy jako starą, jawną (migracja przy następnym zapisie).
 */
object SecretCipher {
    private const val ALIAS = "u1companion_secrets_v1"
    private const val PREFIX = "enc1:"
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + b64(c.iv) + ":" + b64(ct)
    }

    /** Zwraca jawny tekst; dla wartości niezaszyfrowanych (stary format) zwraca je bez zmian; przy błędzie - "". */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val (iv, ct) = stored.removePrefix(PREFIX).split(':', limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, unb64(iv))) }
            String(c.doFinal(unb64(ct)), Charsets.UTF_8)
        }.getOrDefault("") // np. klucz usunięty po przywróceniu kopii - użytkownik wpisze ponownie
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
