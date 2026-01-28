package com.p2pshare.app.crypto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import javax.crypto.AEADBadTagException

/**
 * Property-based tests for AesGcmCipher to verify encryption round-trip consistency.
 * 
 * Feature: p2p-file-share, Property 2: Encryption Round Trip
 * Validates: Requirements 4.3, 4.4
 */
class AesGcmCipherPropertyTest : StringSpec({
    
    val cipher = AesGcmCipher()
    
    "Encryption round trip should preserve original data" {
        // Feature: p2p-file-share, Property 2: Encryption Round Trip
        checkAll<ByteArray>(iterations = 100, Arb.byteArray(Arb.int(1..10000))) { plaintext ->
            val key = cipher.generateKey()
            
            // Encrypt then decrypt should return original data
            val encrypted = cipher.encrypt(plaintext, key)
            val decrypted = cipher.decrypt(encrypted, key)
            
            decrypted shouldBe plaintext
            
            // Clean up key
            cipher.clearKey(key)
        }
    }
    
    "Encryption with same key and different IVs should produce different ciphertexts" {
        checkAll<ByteArray>(iterations = 100, Arb.byteArray(Arb.int(1..1000))) { plaintext ->
            val key = cipher.generateKey()
            
            // Encrypt same plaintext twice with same key (different random IVs)
            val encrypted1 = cipher.encrypt(plaintext, key)
            val encrypted2 = cipher.encrypt(plaintext, key)
            
            // Ciphertexts should be different due to different IVs
            encrypted1 shouldNotBe encrypted2
            
            // But both should decrypt to same plaintext
            val decrypted1 = cipher.decrypt(encrypted1, key)
            val decrypted2 = cipher.decrypt(encrypted2, key)
            
            decrypted1 shouldBe plaintext
            decrypted2 shouldBe plaintext
            
            cipher.clearKey(key)
        }
    }
    
    "Encryption with specific IV should be deterministic" {
        checkAll<ByteArray>(iterations = 50, Arb.byteArray(Arb.int(1..1000))) { plaintext ->
            val key = cipher.generateKey()
            val iv = cipher.generateIv()
            
            // Encrypt same data with same key and IV multiple times
            val encrypted1 = cipher.encrypt(plaintext, key, iv)
            val encrypted2 = cipher.encrypt(plaintext, key, iv)
            
            // Results should be identical
            encrypted1 shouldBe encrypted2
            
            // And should decrypt correctly
            val decrypted = cipher.decrypt(encrypted1, key)
            decrypted shouldBe plaintext
            
            cipher.clearKey(key)
        }
    }
    
    "Different keys should produce different ciphertexts" {
        checkAll<ByteArray>(iterations = 50, Arb.byteArray(Arb.int(1..1000))) { plaintext ->
            val key1 = cipher.generateKey()
            val key2 = cipher.generateKey()
            val iv = cipher.generateIv()
            
            // Encrypt same plaintext with different keys but same IV
            val encrypted1 = cipher.encrypt(plaintext, key1, iv)
            val encrypted2 = cipher.encrypt(plaintext, key2, iv)
            
            // Ciphertexts should be different
            encrypted1 shouldNotBe encrypted2
            
            // Each should decrypt correctly with its own key
            val decrypted1 = cipher.decrypt(encrypted1, key1)
            val decrypted2 = cipher.decrypt(encrypted2, key2)
            
            decrypted1 shouldBe plaintext
            decrypted2 shouldBe plaintext
            
            cipher.clearKey(key1)
            cipher.clearKey(key2)
        }
    }
    
    "Tampered ciphertext should fail authentication" {
        checkAll<ByteArray>(iterations = 50, Arb.byteArray(Arb.int(10..1000))) { plaintext ->
            val key = cipher.generateKey()
            val encrypted = cipher.encrypt(plaintext, key)
            
            // Tamper with the ciphertext (flip a bit in the middle)
            val tampered = encrypted.copyOf()
            val tamperIndex = tampered.size / 2
            tampered[tamperIndex] = (tampered[tamperIndex].toInt() xor 1).toByte()
            
            // Decryption should fail with authentication error
            var authenticationFailed = false
            try {
                cipher.decrypt(tampered, key)
            } catch (e: AEADBadTagException) {
                authenticationFailed = true
            } catch (e: Exception) {
                // Other exceptions might occur depending on where tampering happened
                authenticationFailed = true
            }
            
            authenticationFailed shouldBe true
            
            cipher.clearKey(key)
        }
    }
    
    "Generated keys should be valid and unique" {
        checkAll<Int>(iterations = 100, Arb.int()) { _ ->
            val key1 = cipher.generateKey()
            val key2 = cipher.generateKey()
            
            // Keys should be valid
            cipher.isValidKey(key1) shouldBe true
            cipher.isValidKey(key2) shouldBe true
            
            // Keys should be different
            key1 shouldNotBe key2
            
            // Keys should have correct length
            key1.size shouldBe cipher.getKeyLength()
            key2.size shouldBe cipher.getKeyLength()
            
            cipher.clearKey(key1)
            cipher.clearKey(key2)
        }
    }
    
    "Generated IVs should be valid and unique" {
        checkAll<Int>(iterations = 100, Arb.int()) { _ ->
            val iv1 = cipher.generateIv()
            val iv2 = cipher.generateIv()
            
            // IVs should be valid
            cipher.isValidIv(iv1) shouldBe true
            cipher.isValidIv(iv2) shouldBe true
            
            // IVs should be different (with very high probability)
            iv1 shouldNotBe iv2
            
            // IVs should have correct length
            iv1.size shouldBe cipher.getIvLength()
            iv2.size shouldBe cipher.getIvLength()
        }
    }
    
    "Encrypted data should always be larger than plaintext" {
        checkAll<ByteArray>(iterations = 50, Arb.byteArray(Arb.int(1..1000))) { plaintext ->
            val key = cipher.generateKey()
            val encrypted = cipher.encrypt(plaintext, key)
            
            // Encrypted data includes IV + ciphertext + authentication tag
            val expectedMinSize = plaintext.size + cipher.getIvLength() + cipher.getTagLength()
            encrypted.size shouldBe expectedMinSize
            
            cipher.clearKey(key)
        }
    }
})