package com.p2pshare.app.crypto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.security.KeyPair

/**
 * Property-based tests for EcdhHelper to verify ECDH key exchange consistency.
 * 
 * Feature: p2p-file-share, Property 5: ECDH Key Exchange Consistency
 * Validates: Requirements 4.1, 4.2
 */
class EcdhHelperPropertyTest : StringSpec({
    
    val ecdhHelper = EcdhHelper()
    
    "ECDH key exchange should produce identical shared secrets for both parties" {
        // Feature: p2p-file-share, Property 5: ECDH Key Exchange Consistency
        checkAll<Int>(iterations = 100, Arb.int()) { _ ->
            // Generate key pairs for Alice and Bob
            val aliceKeyPair: KeyPair = ecdhHelper.generateKeyPair()
            val bobKeyPair: KeyPair = ecdhHelper.generateKeyPair()
            
            // Alice computes shared secret using her private key and Bob's public key
            val aliceSharedSecret = ecdhHelper.computeSharedSecret(
                aliceKeyPair.private,
                bobKeyPair.public
            )
            
            // Bob computes shared secret using his private key and Alice's public key
            val bobSharedSecret = ecdhHelper.computeSharedSecret(
                bobKeyPair.private,
                aliceKeyPair.public
            )
            
            // Both parties should derive the same shared secret
            aliceSharedSecret shouldBe bobSharedSecret
            
            // Shared secret should not be empty
            aliceSharedSecret.size shouldNotBe 0
            bobSharedSecret.size shouldNotBe 0
        }
    }
    
    "Generated key pairs should always be unique" {
        checkAll<Int>(iterations = 100, Arb.int()) { _ ->
            val keyPair1 = ecdhHelper.generateKeyPair()
            val keyPair2 = ecdhHelper.generateKeyPair()
            
            // Private keys should be different
            keyPair1.private.encoded shouldNotBe keyPair2.private.encoded
            
            // Public keys should be different
            keyPair1.public.encoded shouldNotBe keyPair2.public.encoded
        }
    }
    
    "Public key validation should correctly identify valid EC keys" {
        checkAll<Int>(iterations = 50, Arb.int()) { _ ->
            val keyPair = ecdhHelper.generateKeyPair()
            
            // Generated public keys should always be valid
            ecdhHelper.isValidPublicKey(keyPair.public) shouldBe true
        }
    }
    
    "Shared secret should be deterministic for same key pairs" {
        checkAll<Int>(iterations = 50, Arb.int()) { _ ->
            val aliceKeyPair = ecdhHelper.generateKeyPair()
            val bobKeyPair = ecdhHelper.generateKeyPair()
            
            // Compute shared secret multiple times with same keys
            val secret1 = ecdhHelper.computeSharedSecret(
                aliceKeyPair.private,
                bobKeyPair.public
            )
            val secret2 = ecdhHelper.computeSharedSecret(
                aliceKeyPair.private,
                bobKeyPair.public
            )
            
            // Results should be identical
            secret1 shouldBe secret2
        }
    }
    
    "Shared secret should have expected length for secp256r1" {
        checkAll<Int>(iterations = 50, Arb.int()) { _ ->
            val aliceKeyPair = ecdhHelper.generateKeyPair()
            val bobKeyPair = ecdhHelper.generateKeyPair()
            
            val sharedSecret = ecdhHelper.computeSharedSecret(
                aliceKeyPair.private,
                bobKeyPair.public
            )
            
            // secp256r1 should produce 32-byte (256-bit) shared secrets
            sharedSecret.size shouldBe 32
        }
    }
})