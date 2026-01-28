package com.p2pshare.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Unit tests for AesGcmCipher class.
 * Tests AES-256-GCM encryption/decryption functionality and edge cases.
 */
class AesGcmCipherTest {
    
    private lateinit var cipher: AesGcmCipher
    
    @Before
    fun setUp() {
        cipher = AesGcmCipher()
    }
    
    @Test
    fun testEncryptDecrypt_BasicFunctionality() {
        val plaintext = "Hello, World!".toByteArray()
        val key = cipher.generateKey()
        
        val encrypted = cipher.encrypt(plaintext, key)
        val decrypted = cipher.decrypt(encrypted, key)
        
        assertArrayEquals("Decrypted text should match original", plaintext, decrypted)
        cipher.clearKey(key)
    }
    
    @Test
    fun testEncryptDecrypt_EmptyData() {
        val plaintext = ByteArray(1) { 0x42 } // Single byte to avoid empty plaintext
        val key = cipher.generateKey()
        
        val encrypted = cipher.encrypt(plaintext, key)
        val decrypted = cipher.decrypt(encrypted, key)
        
        assertArrayEquals("Should handle minimal data", plaintext, decrypted)
        cipher.clearKey(key)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testEncrypt_EmptyPlaintext() {
        val plaintext = ByteArray(0)
        val key = cipher.generateKey()
        
        cipher.encrypt(plaintext, key)
    }
    
    @Test
    fun testEncryptDecrypt_LargeData() {
        val plaintext = ByteArray(10000) { (it % 256).toByte() }
        val key = cipher.generateKey()
        
        val encrypted = cipher.encrypt(plaintext, key)
        val decrypted = cipher.decrypt(encrypted, key)
        
        assertArrayEquals("Should handle large data", plaintext, decrypted)
        cipher.clearKey(key)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testEncrypt_InvalidKeyLength() {
        val plaintext = "test".toByteArray()
        val invalidKey = ByteArray(16) // AES-128 key instead of AES-256
        
        cipher.encrypt(plaintext, invalidKey)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testDecrypt_InvalidKeyLength() {
        val plaintext = "test".toByteArray()
        val validKey = cipher.generateKey()
        val encrypted = cipher.encrypt(plaintext, validKey)
        
        val invalidKey = ByteArray(16) // AES-128 key instead of AES-256
        cipher.decrypt(encrypted, invalidKey)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testEncrypt_InvalidIvLength() {
        val plaintext = "test".toByteArray()
        val key = cipher.generateKey()
        val invalidIv = ByteArray(16) // Wrong IV length
        
        cipher.encrypt(plaintext, key, invalidIv)
    }
    
    @Test
    fun testEncrypt_WithSpecificIv() {
        val plaintext = "test message".toByteArray()
        val key = cipher.generateKey()
        val iv = cipher.generateIv()
        
        val encrypted1 = cipher.encrypt(plaintext, key, iv)
        val encrypted2 = cipher.encrypt(plaintext, key, iv)
        
        assertArrayEquals("Same IV should produce same ciphertext", encrypted1, encrypted2)
        
        val decrypted = cipher.decrypt(encrypted1, key)
        assertArrayEquals("Should decrypt correctly", plaintext, decrypted)
        
        cipher.clearKey(key)
    }
    
    @Test
    fun testEncrypt_RandomIv() {
        val plaintext = "test message".toByteArray()
        val key = cipher.generateKey()
        
        val encrypted1 = cipher.encrypt(plaintext, key)
        val encrypted2 = cipher.encrypt(plaintext, key)
        
        assertFalse("Random IVs should produce different ciphertexts", 
            encrypted1.contentEquals(encrypted2))
        
        val decrypted1 = cipher.decrypt(encrypted1, key)
        val decrypted2 = cipher.decrypt(encrypted2, key)
        
        assertArrayEquals("Both should decrypt to same plaintext", decrypted1, decrypted2)
        assertArrayEquals("Should match original", plaintext, decrypted1)
        
        cipher.clearKey(key)
    }
    
    @Test(expected = AEADBadTagException::class)
    fun testDecrypt_TamperedCiphertext() {
        val plaintext = "test message".toByteArray()
        val key = cipher.generateKey()
        val encrypted = cipher.encrypt(plaintext, key)
        
        // Tamper with the ciphertext
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        
        cipher.decrypt(tampered, key)
    }
    
    @Test(expected = AEADBadTagException::class)
    fun testDecrypt_TamperedIv() {
        val plaintext = "test message".toByteArray()
        val key = cipher.generateKey()
        val encrypted = cipher.encrypt(plaintext, key)
        
        // Tamper with the IV (first 12 bytes)
        val tampered = encrypted.copyOf()
        tampered[0] = (tampered[0].toInt() xor 1).toByte()
        
        cipher.decrypt(tampered, key)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testDecrypt_TooShortData() {
        val key = cipher.generateKey()
        val tooShort = ByteArray(cipher.getIvLength() + cipher.getTagLength() - 1)
        
        cipher.decrypt(tooShort, key)
    }
    
    @Test
    fun testGenerateKey() {
        val key1 = cipher.generateKey()
        val key2 = cipher.generateKey()
        
        assertEquals("Key should be correct length", cipher.getKeyLength(), key1.size)
        assertEquals("Key should be correct length", cipher.getKeyLength(), key2.size)
        assertFalse("Keys should be different", key1.contentEquals(key2))
    }
    
    @Test
    fun testGenerateIv() {
        val iv1 = cipher.generateIv()
        val iv2 = cipher.generateIv()
        
        assertEquals("IV should be correct length", cipher.getIvLength(), iv1.size)
        assertEquals("IV should be correct length", cipher.getIvLength(), iv2.size)
        assertFalse("IVs should be different", iv1.contentEquals(iv2))
    }
    
    @Test
    fun testIsValidKey() {
        val validKey = cipher.generateKey()
        val invalidKey = ByteArray(16)
        
        assertTrue("Generated key should be valid", cipher.isValidKey(validKey))
        assertFalse("Wrong length key should be invalid", cipher.isValidKey(invalidKey))
    }
    
    @Test
    fun testIsValidIv() {
        val validIv = cipher.generateIv()
        val invalidIv = ByteArray(16)
        
        assertTrue("Generated IV should be valid", cipher.isValidIv(validIv))
        assertFalse("Wrong length IV should be invalid", cipher.isValidIv(invalidIv))
    }
    
    @Test
    fun testClearKey() {
        val key = cipher.generateKey()
        val originalKey = key.copyOf()
        
        cipher.clearKey(key)
        
        assertFalse("Key should be cleared", key.contentEquals(originalKey))
        assertTrue("Key should be all zeros", key.all { it == 0.toByte() })
    }
    
    @Test
    fun testClearKey_Null() {
        // Should not throw exception
        cipher.clearKey(null)
    }
    
    @Test
    fun testGetters() {
        assertEquals("Key length should be 32", 32, cipher.getKeyLength())
        assertEquals("IV length should be 12", 12, cipher.getIvLength())
        assertEquals("Tag length should be 16", 16, cipher.getTagLength())
    }
    
    @Test
    fun testEncryptedDataSize() {
        val plaintext = "test message".toByteArray()
        val key = cipher.generateKey()
        val encrypted = cipher.encrypt(plaintext, key)
        
        val expectedSize = plaintext.size + cipher.getIvLength() + cipher.getTagLength()
        assertEquals("Encrypted data should have correct size", expectedSize, encrypted.size)
        
        cipher.clearKey(key)
    }
    
    @Test
    fun testEncryptDecrypt_Performance() {
        val plaintext = ByteArray(1024 * 1024) { (it % 256).toByte() } // 1MB
        val key = cipher.generateKey()
        
        val startTime = System.currentTimeMillis()
        
        val encrypted = cipher.encrypt(plaintext, key)
        val decrypted = cipher.decrypt(encrypted, key)
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertArrayEquals("Large data should encrypt/decrypt correctly", plaintext, decrypted)
        assertTrue("Encryption/decryption should be reasonably fast", duration < 5000)
        
        cipher.clearKey(key)
    }
}