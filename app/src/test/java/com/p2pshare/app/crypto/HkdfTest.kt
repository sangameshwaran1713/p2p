package com.p2pshare.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Hkdf class.
 * Tests HKDF-SHA256 key derivation functionality and edge cases.
 */
class HkdfTest {
    
    private lateinit var hkdf: Hkdf
    
    @Before
    fun setUp() {
        hkdf = Hkdf()
    }
    
    @Test
    fun testDeriveKey_BasicFunctionality() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info = "application info".toByteArray()
        val length = 32
        
        val derivedKey = hkdf.deriveKey(ikm, salt, info, length)
        
        assertNotNull("Derived key should not be null", derivedKey)
        assertEquals("Derived key should have requested length", length, derivedKey.size)
    }
    
    @Test
    fun testDeriveKey_EmptySalt() {
        val ikm = "input key material".toByteArray()
        val salt = ByteArray(0)
        val info = "application info".toByteArray()
        val length = 32
        
        val derivedKey = hkdf.deriveKey(ikm, salt, info, length)
        
        assertEquals("Should work with empty salt", length, derivedKey.size)
    }
    
    @Test
    fun testDeriveKey_EmptyInfo() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info = ByteArray(0)
        val length = 32
        
        val derivedKey = hkdf.deriveKey(ikm, salt, info, length)
        
        assertEquals("Should work with empty info", length, derivedKey.size)
    }
    
    @Test
    fun testDeriveKey_ZeroLength() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        val length = 0
        
        val derivedKey = hkdf.deriveKey(ikm, salt, info, length)
        
        assertEquals("Should return empty array for zero length", 0, derivedKey.size)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testDeriveKey_NegativeLength() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        
        hkdf.deriveKey(ikm, salt, info, -1)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testDeriveKey_TooLargeLength() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        val maxLength = 255 * 32 // 255 * HashLen for SHA-256
        
        hkdf.deriveKey(ikm, salt, info, maxLength + 1)
    }
    
    @Test
    fun testDeriveKey_MaximumLength() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        val maxLength = 255 * 32 // 255 * HashLen for SHA-256
        
        val derivedKey = hkdf.deriveKey(ikm, salt, info, maxLength)
        
        assertEquals("Should handle maximum length", maxLength, derivedKey.size)
    }
    
    @Test
    fun testDeriveKey_Deterministic() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info = "application info".toByteArray()
        val length = 32
        
        val key1 = hkdf.deriveKey(ikm, salt, info, length)
        val key2 = hkdf.deriveKey(ikm, salt, info, length)
        
        assertArrayEquals("HKDF should be deterministic", key1, key2)
    }
    
    @Test
    fun testDeriveKey_DifferentInputs() {
        val ikm1 = "input key material 1".toByteArray()
        val ikm2 = "input key material 2".toByteArray()
        val salt = "salt".toByteArray()
        val info = "application info".toByteArray()
        val length = 32
        
        val key1 = hkdf.deriveKey(ikm1, salt, info, length)
        val key2 = hkdf.deriveKey(ikm2, salt, info, length)
        
        assertFalse("Different inputs should produce different keys", 
            key1.contentEquals(key2))
    }
    
    @Test
    fun testDeriveKey_DifferentSalts() {
        val ikm = "input key material".toByteArray()
        val salt1 = "salt1".toByteArray()
        val salt2 = "salt2".toByteArray()
        val info = "application info".toByteArray()
        val length = 32
        
        val key1 = hkdf.deriveKey(ikm, salt1, info, length)
        val key2 = hkdf.deriveKey(ikm, salt2, info, length)
        
        assertFalse("Different salts should produce different keys", 
            key1.contentEquals(key2))
    }
    
    @Test
    fun testDeriveKey_DifferentInfo() {
        val ikm = "input key material".toByteArray()
        val salt = "salt".toByteArray()
        val info1 = "application info 1".toByteArray()
        val info2 = "application info 2".toByteArray()
        val length = 32
        
        val key1 = hkdf.deriveKey(ikm, salt, info1, length)
        val key2 = hkdf.deriveKey(ikm, salt, info2, length)
        
        assertFalse("Different info should produce different keys", 
            key1.contentEquals(key2))
    }
    
    @Test
    fun testDeriveAesKey() {
        val sharedSecret = "shared secret from ECDH".toByteArray()
        
        val aesKey = hkdf.deriveAesKey(sharedSecret)
        
        assertEquals("AES key should be 32 bytes", 32, aesKey.size)
    }
    
    @Test
    fun testDeriveAesKey_WithSaltAndContext() {
        val sharedSecret = "shared secret from ECDH".toByteArray()
        val salt = "random salt".toByteArray()
        val context = "P2P-FileShare-AES"
        
        val aesKey = hkdf.deriveAesKey(sharedSecret, salt, context)
        
        assertEquals("AES key should be 32 bytes", 32, aesKey.size)
    }
    
    @Test
    fun testDeriveHmacKey() {
        val sharedSecret = "shared secret from ECDH".toByteArray()
        
        val hmacKey = hkdf.deriveHmacKey(sharedSecret)
        
        assertEquals("HMAC key should be 32 bytes", 32, hmacKey.size)
    }
    
    @Test
    fun testDeriveHmacKey_WithSaltAndContext() {
        val sharedSecret = "shared secret from ECDH".toByteArray()
        val salt = "random salt".toByteArray()
        val context = "P2P-FileShare-HMAC"
        
        val hmacKey = hkdf.deriveHmacKey(sharedSecret, salt, context)
        
        assertEquals("HMAC key should be 32 bytes", 32, hmacKey.size)
    }
    
    @Test
    fun testClearKeyMaterial() {
        val keyMaterial = "sensitive key material".toByteArray()
        val originalContent = keyMaterial.copyOf()
        
        hkdf.clearKeyMaterial(keyMaterial)
        
        assertFalse("Key material should be cleared", 
            keyMaterial.contentEquals(originalContent))
        assertTrue("Key material should be all zeros", 
            keyMaterial.all { it == 0.toByte() })
    }
    
    @Test
    fun testClearKeyMaterial_Null() {
        // Should not throw exception
        hkdf.clearKeyMaterial(null)
    }
    
    @Test
    fun testDeriveKey_LargeInputs() {
        val ikm = ByteArray(1000) { it.toByte() }
        val salt = ByteArray(100) { (it * 2).toByte() }
        val info = ByteArray(200) { (it * 3).toByte() }
        val length = 64
        
        val derivedKey = hkdf.deriveKey(ikm, salt, info, length)
        
        assertEquals("Should handle large inputs", length, derivedKey.size)
    }
}