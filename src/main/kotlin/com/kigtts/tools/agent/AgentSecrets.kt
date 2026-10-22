package com.kigtts.tools.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kigtts.plugin.api.PluginStorage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AgentSecrets { fun read(provider: Provider): String; fun write(provider: Provider, value: String); fun exists(provider: Provider): Boolean
    fun readCustom(id: String): String = error("此密钥存储不支持自定义供应商")
    fun writeCustom(id: String, value: String) { error("此密钥存储不支持自定义供应商") }
    fun existsCustom(id: String): Boolean = false
}
internal fun AgentSecrets.read(settings: AgentSettings): String = settings.customProviderId?.takeIf { settings.provider == Provider.CUSTOM }?.let(::readCustom) ?: read(settings.provider)
internal fun AgentSecrets.exists(settings: AgentSettings): Boolean = settings.customProviderId?.takeIf { settings.provider == Provider.CUSTOM }?.let(::existsCustom) ?: exists(settings.provider)
internal fun AgentSecrets.write(settings: AgentSettings, value: String) { val id = settings.customProviderId?.takeIf { settings.provider == Provider.CUSTOM }; if (id == null) write(settings.provider, value) else writeCustom(id, value) }

/** Encryption key never leaves AndroidKeystore; ciphertext stays in this plugin's own config. */
class KeystoreAgentSecrets(private val storage: PluginStorage) : AgentSecrets {
    private val alias = "KIGTTS.tools.cloud-agent.keys.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun exists(provider: Provider) = existsKey(provider.name)
    override fun existsCustom(id: String) = existsKey(customKey(id))
    override fun readCustom(id: String) = readKey(customKey(id))
    override fun writeCustom(id: String, value: String) = writeKey(customKey(id), value)
    private fun customKey(id: String): String { require(id.matches(Regex("[a-f0-9-]{36}"))); return "custom-$id" }
    private fun existsKey(id: String) = storage.file("config/key-$id.bin").isFile
    override fun read(provider: Provider) = readKey(provider.name)
    @Synchronized private fun readKey(id: String): String {
        val file = storage.file("config/key-$id.bin")
        if (!file.exists()) return ""
        try {
            val bytes = Base64.decode(file.readText(Charsets.UTF_8), Base64.NO_WRAP)
            require(bytes.size in 29..8192)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) { throw IllegalStateException("密钥无法解密，请重新填写") }
    }
    override fun write(provider: Provider, value: String) = writeKey(provider.name, value)
    @Synchronized private fun writeKey(id: String, value: String) {
        require(value.length <= 4096 && !value.contains('\n') && !value.contains('\r'))
        val file = storage.file("config/key-$id.bin")
        if (value.isBlank()) { file.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs(); file.writeText(Base64.encodeToString(bytes, Base64.NO_WRAP), Charsets.UTF_8)
    }
}
