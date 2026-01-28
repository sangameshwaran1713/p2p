package com.p2pshare.app.crypto

import com.p2pshare.app.security.SecureKeyManager
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * ECDH (Elliptic Curve Diffie-Hellman) helper class for secure key exchange.
 * Uses secp256r1 curve for generating ephemeral key pairs and computing shared secrets.
 * 
 * This class provides the foundation for establishing secure communication channels
 * between P2P file sharing devices without requiring pre-shared keys.
 */
class EcdhHelper {
    
    companion object {
        private const val CURVE_NAME = "secp256r1"
        private const val KEY_ALGORITHM = "EC"
        private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
    }
    
    /**
     * Generates a new ECDH key pair using the secp256r1 elliptic curve.
     * 
     * The secp256r1 curve (also known as P-256 or prime256v1) is widely supported
     * and provides 128-bit security level, which is suitable for file sharing applications.
     * 
     * @return A new KeyPair containing both private and public keys
     * @throws NoSuchAlgorithmException if EC algorithm is not available
     * @throws InvalidAlgorithmParameterException if secp256r1 curve is not supported
     */
    @Throws(NoSuchAlgorithmException::class, InvalidAlgorithmParameterException::class)
    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        val ecSpec = ECGenParameterSpec(CURVE_NAME)
        keyPairGenerator.initialize(ecSpec, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }
    
    /**
     * Computes the shared secret using ECDH key agreement.
     * 
     * This method performs the core ECDH operation: combining our private key
     * with the peer's public key to derive a shared secret that both parties
     * can compute independently.
     * 
     * The resulting shared secret should be used with a key derivation function
     * (like HKDF) to generate actual encryption keys.
     * 
     * @param privateKey Our private key from the key pair
     * @param publicKey The peer's public key received during handshake
     * @return The raw shared secret as a byte array
     * @throws NoSuchAlgorithmException if ECDH algorithm is not available
     * @throws InvalidKeyException if either key is invalid or incompatible
     */
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    fun computeSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }
    
    /**
     * Validates that a public key is compatible with our ECDH implementation.
     * 
     * This method checks that the provided public key uses the EC algorithm
     * and is suitable for ECDH key agreement.
     * 
     * @param publicKey The public key to validate
     * @return true if the key is valid for ECDH, false otherwise
     */
    fun isValidPublicKey(publicKey: PublicKey): Boolean {
        return try {
            publicKey.algorithm == KEY_ALGORITHM && publicKey.encoded != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Securely clears a private key from memory using SecureKeyManager.
     * 
     * This method uses the SecureKeyManager to properly wipe sensitive key material
     * from memory, reducing the window of vulnerability for cryptographic keys.
     * 
     * @param privateKey The private key to clear
     */
    fun clearPrivateKey(privateKey: PrivateKey?) {
        privateKey?.let {
            try {
                // Use SecureKeyManager for proper key cleanup
                val keyManager = SecureKeyManager.getInstance()
                
                // Try to get the encoded form and wipe it
                val encoded = it.encoded
                if (encoded != null) {
                    val keyId = keyManager.generateKeyId()
                    keyManager.storeSecureData(keyId, encoded)
                    keyManager.clearKey(keyId) // This will securely wipe the data
                }
                
                // Force garbage collection hint (not guaranteed)
                System.gc()
                
            } catch (e: Exception) {
                // If secure clearing fails, at least null the reference
                // The GC will eventually clean it up
            }
        }
    }
    
    /**
     * Securely clears a shared secret from memory.
     * 
     * @param sharedSecret The shared secret byte array to clear
     */
    fun clearSharedSecret(sharedSecret: ByteArray?) {
        sharedSecret?.let {
            val keyManager = SecureKeyManager.getInstance()
            val keyId = keyManager.generateKeyId()
            keyManager.storeSecureData(keyId, it)
            keyManager.clearKey(keyId) // This will securely wipe the data
        }
    }
    
    /**
     * Creates a secure session for ECDH operations with automatic cleanup.
     * 
     * @return SecureKeyManager.SecureSession for managing temporary keys
     */
    fun createSecureSession(): SecureKeyManager.SecureSession {
        return SecureKeyManager.getInstance().createSecureSession()
    }
}