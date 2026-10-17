package dev.muhib.s23optimizer.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.muhib.s23optimizer.core.agent.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from audit/journal/export data. Key material never leaves AndroidKeyStore. */
class AgentSettings(context: Context) {
    private val preferences = context.getSharedPreferences("agent_credentials", Context.MODE_PRIVATE)
    private val alias = "s23_agent_api_key_v1"

    fun config(): ModelConfig {
        val provider = runCatching { Provider.valueOf(preferences.getString("provider", null).orEmpty()) }.getOrDefault(Provider.ANTHROPIC)
        return ModelConfig(provider, preferences.getString("model", provider.defaultModel).orEmpty(), preferences.getString("base_url", provider.defaultUrl).orEmpty())
    }
    fun hasKey(): Boolean = preferences.contains("ciphertext")

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun save(config: ModelConfig, newKey: String) {
        config.validate()
        require(newKey.length <= 4096 && newKey.none(Char::isISOControl)) { "API key is invalid." }
        val previous = this.config()
        val changedEndpoint = config.provider != previous.provider || config.baseUrl.trimEnd('/') != previous.baseUrl.trimEnd('/')
        require(!changedEndpoint || newKey.isNotBlank() || !hasKey()) { "Re-enter your API key when changing provider or endpoint." }
        val edit = preferences.edit().putString("provider", config.provider.name).putString("model", config.model).putString("base_url", config.baseUrl)
        if (newKey.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(newKey.toByteArray(Charsets.UTF_8))
            edit.putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            edit.putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(edit.commit()) { "Could not save Agent settings." }
    }

    fun clearKey() { check(preferences.edit().remove("ciphertext").remove("iv").commit()) { "Could not remove API key." } }

    fun client(): ModelClient {
        val config = config().also { it.validate() }
        // Do not propagate crypto exception details or ciphertext to UI/logging.
        val plaintext = try {
            val encrypted = requireNotNull(preferences.getString("ciphertext", null))
            val iv = requireNotNull(preferences.getString("iv", null))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { error("API key unavailable. Save it again in Agent settings.") }
        return when (config.provider) {
            Provider.ANTHROPIC -> AnthropicModelClient(config, plaintext)
            Provider.OPENAI -> OpenAiModelClient(config, plaintext)
        }
    }
}
