package com.hocus.vision

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class SecretCipherTest {
    @Test fun encryptsAndRestoresWithoutStoringPlaintext() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "not-a-real-api-key-for-local-tests"
        val (iv, encrypted) = SecretCipher.encrypt(secret, key)
        assertFalse(encrypted.contentEquals(secret.toByteArray()))
        assertEquals(secret, SecretCipher.decrypt(iv, encrypted, key))
        assertFalse(iv.contentEquals(SecretCipher.encrypt(secret, key).first))
    }
    @Test fun modifiedCiphertextFailsAuthentication() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val (iv, encrypted) = SecretCipher.encrypt("not-a-real-secret", key)
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        try { SecretCipher.decrypt(iv, encrypted, key); fail("Tampered secret must fail") }
        catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test fun modelIdCannotRedirectARequestOrInjectQueryParameters() {
        assertTrue(ApiKeyStore.validModel("gemini-3.1-flash-lite"))
        for (model in listOf("https://evil.example", "../models/test", "gemini-flash?key=test", "gemini-foo/bar"))
            assertFalse(ApiKeyStore.validModel(model))
    }
}
