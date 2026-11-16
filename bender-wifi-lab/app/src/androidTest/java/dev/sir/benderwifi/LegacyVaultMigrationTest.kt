package dev.sir.benderwifi

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Run after an actual disposable-emulator credential unlock; never skip auth failures. */
class LegacyVaultMigrationTest {
    @Test fun oldRecordMigratesWithoutLosingMetadataAndFailurePreservesData()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LabDatabase::class.java).build()
        val vault=SecretVault(context)
        val repo=LabRepository(db,vault)
        try {
            val old=legacyRecord("SYNTHETIC LEGACY SECRET")
            val damaged=old.copy(ciphertext=flip(old.ciphertext))
            db.dao().putCredential(damaged)
            val failure=runCatching {repo.revealCredential(old.id)}.exceptionOrNull()
            assertTrue(failure is GeneralSecurityException)
            assertFalse("Unlock the emulator before running this class",failure is UserNotAuthenticatedException)
            assertEquals(damaged,db.dao().credential(old.id))
            db.dao().putCredential(old)
            assertEquals("SYNTHETIC LEGACY SECRET",repo.revealCredential(old.id))
            val current=db.dao().credential(old.id)!!
            assertTrue(current.wrappedKey.startsWith("v2:"))
            assertEquals(old.copy(ciphertext=current.ciphertext,iv=current.iv,wrappedKey=current.wrappedKey),current)
            assertEquals("SYNTHETIC LEGACY SECRET",repo.revealCredential(old.id))
            assertEquals("SYNTHETIC LEGACY SECRET",vault.decrypt(old)) // Legacy key retained.
            db.dao().deleteCredential(old.id)
            assertEquals(0,db.dao().migrateCredential(old.id,current.wrappedKey,current.ciphertext,current.iv,current.wrappedKey))
            assertNull(db.dao().credential(old.id))
        } finally {db.close()}
    }
    private fun legacyRecord(secret:String):CredentialEntity {
        val alias="bender_wifi_vault_rsa_v1"
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(!store.containsAlias(alias))KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(30,KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(false).build())
            generateKeyPair()
        }
        val key=ByteArray(32).also {SecureRandom().nextBytes(it)}
        val aes=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"));updateAAD("legacy-fixture".toByteArray())}
        val wrap=Cipher.getInstance("RSA/ECB/OAEPPadding").apply {init(Cipher.ENCRYPT_MODE,store.getCertificate(alias).publicKey,OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT))}
        return try {CredentialEntity("legacy-fixture",null,"Legacy synthetic",null,"instrumentation","unverified",123,encode(aes.doFinal(secret.toByteArray())),encode(aes.iv),encode(wrap.doFinal(key)))} finally {key.fill(0)}
    }
    private fun encode(bytes:ByteArray)=Base64.encodeToString(bytes,Base64.NO_WRAP)
    private fun flip(text:String):String {val bytes=Base64.decode(text,Base64.NO_WRAP);bytes[0]=(bytes[0].toInt() xor 1).toByte();return encode(bytes)}
}
