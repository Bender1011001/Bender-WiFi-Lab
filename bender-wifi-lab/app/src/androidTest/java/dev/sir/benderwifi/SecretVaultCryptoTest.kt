package dev.sir.benderwifi

import android.app.KeyguardManager
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sir.benderwifi.data.CredentialEntity
import dev.sir.benderwifi.data.SecretVault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.util.UUID

/** Real Android Keystore encryption/tamper checks, with no additional authentication prompt. */
@RunWith(AndroidJUnit4::class)
class SecretVaultCryptoTest {
    @Test fun unlockedRoundTripRejectsAadCiphertextIvAndWrappedKeyTampering(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=SecretVault(context)
        val secret="SYNTHETIC-SECRET-"+UUID.randomUUID()
        val id=UUID.randomUUID().toString()
        val encrypted=vault.encrypt(secret,id) // No authentication required for automatic ingestion.
        val record=CredentialEntity(id,null,"SYNTHETIC TEST ONLY",null,"instrumentation","unverified",0,encrypted.ciphertext,encrypted.iv,encrypted.wrappedKey)
        assertFalse(record.toString().contains(secret))
        assertTrue(record.wrappedKey.startsWith("v2:"))
        assertEquals(secret,vault.decrypt(record))
        fun rejectTamper(altered:CredentialEntity){
            assertEquals(secret,vault.decrypt(record))
            val failure=runCatching {vault.decrypt(altered)}.exceptionOrNull()
            assertNotNull("Tampering must fail authentication",failure)
            assertFalse("Expiry is not proof of tamper protection",failure is UserNotAuthenticatedException)
            assertTrue("Must fail cryptographic authentication",failure is GeneralSecurityException)
            assertEquals("Original must still decrypt after tamper rejection",secret,vault.decrypt(record))
        }
        rejectTamper(record.copy(id=UUID.randomUUID().toString()))
        rejectTamper(record.copy(ciphertext=flip(record.ciphertext)))
        rejectTamper(record.copy(iv=flip(record.iv)))
        rejectTamper(record.copy(wrappedKey=flip(record.wrappedKey)))
    }
    private fun flip(encoded:String):String {
        val prefix=if(encoded.startsWith("v2:"))"v2:" else ""
        val bytes=Base64.decode(encoded.removePrefix(prefix),Base64.NO_WRAP)
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        return prefix+Base64.encodeToString(bytes,Base64.NO_WRAP)
    }
}
