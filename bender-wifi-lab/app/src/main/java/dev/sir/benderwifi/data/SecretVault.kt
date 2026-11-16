package dev.sir.benderwifi.data

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Public-key wrapping permits encrypted ingestion while locked. New records use
 * the phone's unlocked state rather than an additional authentication timeout.
 * Legacy authentication-bound keys are retained until their records can be read. */
class SecretVault(private val context:Context?=null) {
    private val alias="bender_wifi_vault_rsa_v2"
    private val legacyAlias="bender_wifi_vault_rsa_v1"
    private val prefix="v2:"
    private val oaep=OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT)
    @Synchronized private fun store():KeyStore {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(!store.containsAlias(alias))KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
            val spec=KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1)
                .setUserAuthenticationRequired(false)
            // Android 12-14 could delete unlocked-device keys when the screen
            // lock was removed. API 35 fixes that; older versions also have the
            // app's device-lock check below and credential-encrypted storage.
            if(Build.VERSION.SDK_INT>=35)spec.setUnlockedDeviceRequired(true)
            initialize(spec.build())
            generateKeyPair()
        }
        return store
    }
    data class Encrypted(val ciphertext:String,val iv:String,val wrappedKey:String)
    fun encrypt(secret:String,recordId:String):Encrypted {
        val store=store()
        val publicKey=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(store.getCertificate(alias).publicKey.encoded))
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        val plain=secret.toByteArray(Charsets.UTF_8)
        return try {
            val aes=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"));updateAAD(recordId.toByteArray(Charsets.UTF_8))}
            val wrap=Cipher.getInstance("RSA/ECB/OAEPPadding").apply {init(Cipher.ENCRYPT_MODE,publicKey,oaep)}
            Encrypted(encode(aes.doFinal(plain)),encode(aes.iv),prefix+encode(wrap.doFinal(key)))
        } finally {key.fill(0);plain.fill(0)}
    }
    fun isLegacy(record:CredentialEntity)=!record.wrappedKey.startsWith(prefix)
    fun decrypt(record:CredentialEntity):String {
        check(context==null || !context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {"Unlock your phone to reveal credentials."}
        val legacy=isLegacy(record)
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val keyAlias=if(legacy)legacyAlias else alias
        val wrappingKey=store.getKey(keyAlias,null) ?: throw GeneralSecurityException("Original vault key is unavailable; the encrypted record has been preserved.")
        val unwrap=Cipher.getInstance("RSA/ECB/OAEPPadding").apply {init(Cipher.DECRYPT_MODE,wrappingKey,oaep)}
        val key=unwrap.doFinal(decode(if(legacy)record.wrappedKey else record.wrappedKey.removePrefix(prefix)))
        return try {
            val aes=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,decode(record.iv)));updateAAD(record.id.toByteArray(Charsets.UTF_8))}
            val bytes=aes.doFinal(decode(record.ciphertext))
            try {bytes.toString(Charsets.UTF_8)}finally {bytes.fill(0)}
        }finally {key.fill(0)}
    }
    private fun encode(bytes:ByteArray)=Base64.encodeToString(bytes,Base64.NO_WRAP)
    private fun decode(value:String)=Base64.decode(value,Base64.NO_WRAP)
}
