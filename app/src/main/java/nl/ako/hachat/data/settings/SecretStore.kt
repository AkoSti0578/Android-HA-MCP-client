package nl.ako.hachat.data.settings

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * AES-256-GCM via Tink; de keyset zelf is versleuteld met een niet-exporteerbare
 * sleutel in de Android Keystore. Ciphertexts gaan als Base64 de DataStore in.
 */
interface SecretCipher {
    fun encrypt(plain: String, purpose: String): String
    fun decrypt(cipher: String, purpose: String): String?
}

class TinkSecretCipher(context: Context) : SecretCipher {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plain: String, purpose: String): String =
        Base64.encodeToString(aead.encrypt(plain.toByteArray(), purpose.toByteArray()), Base64.NO_WRAP)

    override fun decrypt(cipher: String, purpose: String): String? = runCatching {
        String(aead.decrypt(Base64.decode(cipher, Base64.NO_WRAP), purpose.toByteArray()))
    }.getOrNull()

    private companion object {
        const val KEYSET_NAME = "ako_secret_keyset"
        const val KEYSET_PREFS = "ako_secret_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://ako_ha_chat_master_key"
    }
}
