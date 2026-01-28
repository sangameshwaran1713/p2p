package com.p2pshare.app.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.InvalidKeyException
import java.security.KeyPair
import java.security.NoSuchAlgorithmException

/**
 * Unit tests for EcdhHelper class.
 * Tests specific functionality and edge cases for ECDH key operations.
 */
class EcdhHelperTest {
    
    private lateinit var ecdhHelper: EcdhHelper
    
    @Before
    fun setUp() {
        ecdhHelper = EcdhHelper()
    }
    
    @Test
    fun testGenerateKeyPair_Success() {
        val keyPair = ecdhHelper.generateKeyPair()
        
        assertNotNull("Key pair should not be null", keyPair)
        assertNotNull("Private key should not be null", keyPair.private)
        assertNotNull("Public key should not be null", keyPair.public)
        assertEquals("Private key algorithm should be EC", "EC", keyPair.private.algorithm)
        assertEquals("Public key algorithm should be EC", "EC", keyPair.public.algorithm)
    }
    
    @Test
    fun testGenerateKeyPair_UniqueKeys() {
        val keyPair1 = ecdhHelper.generateKeyPair()
        val keyPair2 = ecdhHelper.generateKeyPair()
        
        assertFalse("Private keys should be different", 
            keyPair1.private.encoded.contentEquals(keyPair2.private.encoded))
        assertFalse("Public keys should be different", 
            keyPair1.public.encoded.contentEquals(keyPair2.public.encoded))
    }
    
    @Test
    fun testComputeSharedSecret_Success() {
        val aliceKeyPair = ecdhHelper.generateKeyPair()
        val bobKeyPair = ecdhHelper.generateKeyPair()
        
        val aliceSecret = ecdhHelper.computeSharedSecret(aliceKeyPair.private, bobKeyPair.public)
        val bobSecret = ecdhHelper.computeSharedSecret(bobKeyPair.private, aliceKeyPair.public)
        
        assertNotNull("Alice's shared secret should not be null", aliceSecret)
        assertNotNull("Bob's shared secret should not be null", bobSecret)
        assertArrayEquals("Shared secrets should be identical", aliceSecret, bobSecret)
        assertEquals("Shared secret should be 32 bytes for secp256r1", 32, aliceSecret.size)
    }
    
    @Test
    fun testComputeSharedSecret_Deterministic() {
        val aliceKeyPair = ecdhHelper.generateKeyPair()
        val bobKeyPair = ecdhHelper.generateKeyPair()
        
        val secret1 = ecdhHelper.computeSharedSecret(aliceKeyPair.private, bobKeyPair.public)
        val secret2 = ecdhHelper.computeSharedSecret(aliceKeyPair.private, bobKeyPair.public)
        
        assertArrayEquals("Shared secret computation should be deterministic", secret1, secret2)
    }
    
    @Test(expected = InvalidKeyException::class)
    fun testComputeSharedSecret_InvalidKey() {
        val validKeyPair = ecdhHelper.generateKeyPair()
        // This should throw InvalidKeyException when trying to use mismatched key types
        ecdhHelper.computeSharedSecret(validKeyPair.private, validKeyPair.private as java.security.PublicKey)
    }
    
    @Test
    fun testIsValidPublicKey_ValidKey() {
        val keyPair = ecdhHelper.generateKeyPair()
        assertTrue("Generated public key should be valid", ecdhHelper.isValidPublicKey(keyPair.public))
    }
    
    @Test
    fun testIsValidPublicKey_NullKey() {
        // Create a mock invalid key by using a different algorithm
        try {
            val keyGen = java.security.KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val rsaKeyPair = keyGen.generateKeyPair()
            
            assertFalse("RSA key should not be valid for ECDH", 
                ecdhHelper.isValidPublicKey(rsaKeyPair.public))
        } catch (e: NoSuchAlgorithmException) {
            // RSA might not be available in test environment, skip this test
            assertTrue("RSA not available for testing", true)
        }
    }
    
    @Test
    fun testClearPrivateKey_NoException() {
        val keyPair = ecdhHelper.generateKeyPair()
        
        // Should not throw any exception
        ecdhHelper.clearPrivateKey(keyPair.private)
        ecdhHelper.clearPrivateKey(null)
    }
    
    @Test
    fun testKeyPairGeneration_Performance() {
        val startTime = System.currentTimeMillis()
        
        // Generate multiple key pairs to test performance
        for (i in 1..10) {
            ecdhHelper.generateKeyPair()
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        // Key generation should be reasonably fast (less than 5 seconds for 10 keys)
        assertTrue("Key generation should be reasonably fast", duration < 5000)
    }
    
    @Test
    fun testSharedSecretGeneration_Performance() {
        val aliceKeyPair = ecdhHelper.generateKeyPair()
        val bobKeyPair = ecdhHelper.generateKeyPair()
        
        val startTime = System.currentTimeMillis()
        
        // Compute shared secret multiple times
        for (i in 1..100) {
            ecdhHelper.computeSharedSecret(aliceKeyPair.private, bobKeyPair.public)
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        // Shared secret computation should be fast (less than 1 second for 100 computations)
        assertTrue("Shared secret computation should be fast", duration < 1000)
    }
}