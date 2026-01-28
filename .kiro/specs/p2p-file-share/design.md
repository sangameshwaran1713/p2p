# Design Document

## Overview

The P2P File Share application is a secure, high-performance Android application that enables direct file sharing between devices using Wi-Fi Direct technology. The system combines QR code-based device pairing, end-to-end encryption, and chunked file transfer to provide a seamless offline file sharing experience.

The application follows a modular architecture with clear separation of concerns across networking, cryptography, UI, and data persistence layers. The design prioritizes security through ECDH key exchange and AES-GCM encryption, performance through parallel chunk transfer, and user experience through real-time progress monitoring and background operation.

## Architecture

The application follows a layered architecture pattern with the following components:

### Presentation Layer
- **MainActivity**: Entry point with send/receive options
- **SendActivity**: File selection and QR code display
- **ReceiveActivity**: QR code scanning interface
- **TransferActivity**: Real-time transfer progress monitoring
- **QrScannerActivity**: Camera-based QR code detection

### Business Logic Layer
- **WifiDirectManager**: Wi-Fi Direct connection management
- **TransferService**: Background file transfer orchestration
- **QrGenerator**: QR code generation for session info
- **Crypto Module**: ECDH key exchange and AES-GCM encryption

### Data Layer
- **File Transfer Module**: Chunked file transmission and reception
- **Room Database**: Transfer history persistence
- **File System**: Local file access and storage

### Network Layer
- **ServerSocket**: TCP server for file sender
- **Client Socket**: TCP client for file receiver
- **Wi-Fi Direct**: Device-to-device connectivity

## Components and Interfaces

### Wi-Fi Direct Module (com.p2pshare.app.wifi)

**WifiDirectManager**
```kotlin
interface WifiDirectCallback {
    fun onGroupCreated(groupInfo: WifiP2pGroup)
    fun onPeerAvailable(peer: WifiP2pDevice)
    fun onConnected(connectionInfo: WifiP2pInfo)
    fun onDisconnected()
}

class WifiDirectManager(context: Context, callback: WifiDirectCallback)
```

**WifiDirectReceiver**
- Handles Wi-Fi Direct system broadcasts
- Manages peer discovery and connection state changes
- Integrates with WifiDirectManager for callback delegation

### QR Code Module (com.p2pshare.app.qr)

**QrGenerator**
```kotlin
class QrGenerator {
    fun generateQrCode(sessionInfo: String): Bitmap
}
```

**QrScannerActivity**
- CameraX integration for camera preview
- ZXing-based QR code detection
- Returns scanned JSON to calling activity

### Cryptography Module (com.p2pshare.app.crypto)

**EcdhHelper**
```kotlin
class EcdhHelper {
    fun generateKeyPair(): KeyPair
    fun computeSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray
}
```

**Hkdf**
```kotlin
class Hkdf {
    fun deriveKey(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
}
```

**AesGcmCipher**
```kotlin
class AesGcmCipher {
    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray
}
```

### File Transfer Module (com.p2pshare.app.transfer)

**FileManifest**
```kotlin
data class FileManifest(
    val fileName: String,
    val fileSize: Long,
    val chunkSize: Int = 256 * 1024, // 256KB
    val chunkCount: Int,
    val chunkHashes: List<String> // SHA-256 hashes
)
```

**ChunkManager**
```kotlin
class ChunkManager {
    fun splitFile(file: File): List<ChunkRange>
    fun getReceivedChunks(): BooleanArray
    fun markChunkReceived(chunkIndex: Int)
    fun isTransferComplete(): Boolean
}
```

**FileSender**
```kotlin
class FileSender {
    suspend fun startServer(port: Int)
    suspend fun sendFile(file: File, encryptionKey: ByteArray)
}
```

**FileReceiver**
```kotlin
class FileReceiver {
    suspend fun connectToSender(ip: String, port: Int)
    suspend fun receiveFile(outputPath: String, encryptionKey: ByteArray)
}
```

### Transfer Service (com.p2pshare.app.service)

**TransferService**
```kotlin
class TransferService : Service() {
    val transferProgress: LiveData<TransferProgress>
    fun startSending(file: File)
    fun startReceiving(sessionInfo: SessionInfo)
}
```

## Data Models

### Session Information
```kotlin
data class SessionInfo(
    val ip: String,
    val port: Int,
    val sessionToken: String,
    val publicKey: String, // Base64 encoded
    val role: TransferRole,
    val expiryTime: Long
)

enum class TransferRole { SENDER, RECEIVER }
```

### Transfer Progress
```kotlin
data class TransferProgress(
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val progressPercentage: Float,
    val speedMbps: Float,
    val estimatedTimeRemaining: Long,
    val status: TransferStatus
)

enum class TransferStatus { CONNECTING, TRANSFERRING, COMPLETED, FAILED }
```

### Database Entities
```kotlin
@Entity(tableName = "transfer_records")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val fileSize: Long,
    val direction: TransferDirection,
    val timestamp: Long,
    val duration: Long,
    val success: Boolean
)

enum class TransferDirection { SENT, RECEIVED }
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Core File Transfer Properties

**Property 1: File Transfer Round Trip**
*For any* file selected for sharing, when the complete transfer process (send → receive) is executed, the received file should be identical to the original file
**Validates: Requirements 5.5**

**Property 2: Encryption Round Trip**
*For any* file data, when encrypted with AES-256-GCM and then decrypted with the same key, the result should be identical to the original data
**Validates: Requirements 4.3, 4.4**

**Property 3: Chunk Integrity Preservation**
*For any* file split into chunks, when each chunk is hashed with SHA-256, transmitted, and verified, all chunk hashes should match their original values
**Validates: Requirements 5.2, 5.4**

### Connection and Session Properties

**Property 4: QR Session Info Round Trip**
*For any* valid session information, when encoded as QR code and then scanned and parsed, the extracted session info should be identical to the original
**Validates: Requirements 2.3, 3.2**

**Property 5: ECDH Key Exchange Consistency**
*For any* two devices performing ECDH key exchange with secp256r1, both devices should derive identical shared secrets from their respective key pairs
**Validates: Requirements 4.1, 4.2**

**Property 6: Wi-Fi Direct Connection Establishment**
*For any* valid QR session info, when scanned by a receiver, a Wi-Fi Direct connection should be successfully established between sender and receiver
**Validates: Requirements 1.2, 3.3**

### Progress and State Management Properties

**Property 7: Transfer Progress Monotonicity**
*For any* active file transfer, the progress percentage should never decrease and should reach 100% when transfer completes successfully
**Validates: Requirements 6.1**

**Property 8: Background Service Persistence**
*For any* active file transfer, when the device screen turns off or user switches apps, the transfer should continue without interruption
**Validates: Requirements 7.3, 7.4**

**Property 9: Database Record Completeness**
*For any* completed file transfer, a transfer record should be stored in the database containing all required metadata (filename, size, direction, timestamp, success status)
**Validates: Requirements 8.1, 8.2**

### Security and Permission Properties

**Property 10: Cryptographic Key Cleanup**
*For any* completed transfer session, all cryptographic keys should be securely disposed of and not accessible in memory
**Validates: Requirements 4.5**

**Property 11: Permission Request Consistency**
*For any* feature requiring system permissions, the appropriate permissions should be requested before attempting to use the feature
**Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5**

### Module Integration Properties

**Property 12: Offline Operation Completeness**
*For any* file transfer operation, when internet connectivity is unavailable, all functionality should work using only Wi-Fi Direct
**Validates: Requirements 1.5**

**Property 13: Parallel Chunk Transfer Efficiency**
*For any* file larger than 256KB, when split into chunks, multiple chunks should be transmitted in parallel rather than sequentially
**Validates: Requirements 5.3**

## Error Handling

### Network Error Recovery
- **Connection Timeout**: Implement exponential backoff for Wi-Fi Direct connection attempts
- **Transfer Interruption**: Support resume capability using chunk tracking
- **Peer Disconnection**: Graceful cleanup of resources and user notification

### Cryptographic Error Handling
- **Key Exchange Failure**: Retry ECDH handshake with new ephemeral keys
- **Decryption Failure**: Request chunk retransmission for corrupted data
- **Authentication Failure**: Terminate session and clear all keys

### File System Error Handling
- **Insufficient Storage**: Check available space before transfer initiation
- **File Access Denied**: Validate file permissions before sharing
- **Corrupted Chunks**: Verify SHA-256 hashes and request retransmission

### UI Error Handling
- **Camera Access Denied**: Provide alternative QR input method
- **QR Code Malformed**: Display user-friendly error messages
- **Permission Denied**: Guide user through permission granting process

## Testing Strategy

### Unit Testing Approach
The application will use JUnit 5 for unit testing with the following focus areas:
- Cryptographic function correctness (ECDH, HKDF, AES-GCM)
- File chunking and reassembly logic
- QR code generation and parsing
- Database operations and data integrity
- Permission handling workflows

### Property-Based Testing Approach
The application will use **Kotest Property Testing** for Android Kotlin development. Each property-based test will run a minimum of 100 iterations to ensure comprehensive coverage across the input space.

**Property Test Configuration:**
- Test framework: Kotest (io.kotest:kotest-property:5.8.0)
- Minimum iterations: 100 per property test
- Each property test must include a comment referencing the design document property
- Comment format: `// Feature: p2p-file-share, Property X: [property description]`

**Key Property Tests:**
- File transfer round-trip with various file sizes and types
- Encryption/decryption round-trip with random data
- QR code generation/scanning round-trip with session data
- Chunk integrity across different chunk sizes and file types
- Progress calculation accuracy across transfer scenarios
- Database persistence across various transfer outcomes

**Test Data Generation:**
- Random file content generators for transfer testing
- Session info generators with valid/invalid combinations
- Network condition simulators for error testing
- Permission state generators for access control testing

### Integration Testing
- End-to-end transfer scenarios between simulated devices
- Wi-Fi Direct connection establishment and teardown
- Background service lifecycle during transfers
- Database consistency across application restarts

### Performance Testing
- Large file transfer performance (>1GB files)
- Concurrent chunk transfer optimization
- Memory usage during chunked transfers
- Battery consumption during background transfers

The dual testing approach ensures both specific functionality validation through unit tests and general correctness verification through property-based tests, providing comprehensive coverage of the application's behavior across all possible inputs and scenarios.