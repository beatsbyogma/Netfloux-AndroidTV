package com.cinetv.data.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffre les mots de passe des partages réseau avant de les ranger.
 *
 * La clé vit dans le coffre matériel d'Android (Keystore) : elle ne quitte jamais la TV
 * et aucune autre application ne peut s'en servir. Un mot de passe copié depuis les
 * fichiers de l'app est donc illisible ailleurs.
 */
object SecretBox {

    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "netfloux_shares"
    private const val MARK = "enc:"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(MARK)

    /** Le texte chiffré, prêt à être rangé. Un texte vide reste vide. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return MARK + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /**
     * Le texte d'origine. Une valeur rangée avant le chiffrement est rendue telle quelle ;
     * une valeur illisible (clé perdue après une réinitialisation) donne un texte vide,
     * et le mot de passe sera simplement redemandé.
     */
    fun open(stored: String): String {
        if (!isSealed(stored)) return stored
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(MARK), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            }
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
