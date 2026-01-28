# Requirements Document

## Introduction

The P2P File Share application enables secure, high-speed file sharing between Android devices using Wi-Fi Direct technology, QR code pairing, and end-to-end encryption. The system allows users to share files directly without internet connectivity, using modern cryptographic protocols for security and chunked transfer for performance.

## Glossary

- **P2P_File_Share_System**: The complete Android application for peer-to-peer file sharing with package name com.p2pshare.app
- **Sender_Device**: The Android device that initiates file sharing and creates the Wi-Fi Direct group
- **Receiver_Device**: The Android device that scans the QR code and receives the shared file
- **Wi-Fi_Direct_Manager**: The component in com.p2pshare.app.wifi package responsible for Wi-Fi Direct peer discovery and connection management
- **QR_Session_Info**: JSON data containing connection details (IP, port, session token, public key) encoded in QR format using ZXing
- **File_Manifest**: Metadata structure containing file information (name, size, chunk count, SHA-256 checksums)
- **Chunk_Transfer**: The process of splitting files into 256KB segments for parallel transmission using coroutines
- **ECDH_Handshake**: Elliptic Curve Diffie-Hellman key exchange using secp256r1 curve for establishing shared encryption keys
- **Transfer_Service**: Android foreground service in com.p2pshare.app.service package that manages file transfer operations
- **Session_Token**: Temporary authentication token valid for one file transfer session
- **CameraX_Scanner**: Camera implementation using androidx.camera libraries for QR code detection
- **ZXing_Generator**: QR code generation using com.google.zxing MultiFormatWriter
- **Room_Database**: Local SQLite database using androidx.room for transfer history storage
- **ServerSocket_Implementation**: TCP server socket for file sender functionality
- **Material_Design_UI**: User interface following Google Material Design guidelines using XML layouts

## Requirements

### Requirement 1

**User Story:** As a user, I want to share files with nearby devices without internet connectivity, so that I can transfer files quickly and securely in offline environments.

#### Acceptance Criteria

1. WHEN a user selects a file for sharing, THE P2P_File_Share_System SHALL create a Wi-Fi Direct group and generate QR_Session_Info
2. WHEN a Receiver_Device scans the QR code, THE P2P_File_Share_System SHALL automatically establish Wi-Fi Direct connection between devices
3. WHEN devices are connected, THE P2P_File_Share_System SHALL transfer files using encrypted peer-to-peer communication
4. WHEN file transfer completes, THE P2P_File_Share_System SHALL store transfer records in local database
5. WHEN no internet connection is available, THE P2P_File_Share_System SHALL operate fully offline using Wi-Fi Direct

### Requirement 2

**User Story:** As a sender, I want to initiate file sharing by selecting a file and displaying a QR code, so that receivers can easily connect and receive the file.

#### Acceptance Criteria

1. WHEN a user selects a file from device storage, THE P2P_File_Share_System SHALL validate file accessibility and size
2. WHEN file selection is confirmed, THE P2P_File_Share_System SHALL create Wi-Fi Direct group as group owner
3. WHEN Wi-Fi Direct group is established, THE P2P_File_Share_System SHALL generate QR_Session_Info containing connection details
4. WHEN QR_Session_Info is generated, THE P2P_File_Share_System SHALL display QR code on sender screen
5. WHEN QR code is displayed, THE P2P_File_Share_System SHALL wait for receiver connection with session timeout

### Requirement 3

**User Story:** As a receiver, I want to scan a QR code to automatically connect and receive files, so that I can receive shared files without manual network configuration.

#### Acceptance Criteria

1. WHEN receiver launches QR scanner, THE P2P_File_Share_System SHALL activate camera and QR detection
2. WHEN QR code is successfully scanned, THE P2P_File_Share_System SHALL parse QR_Session_Info and validate session token
3. WHEN QR_Session_Info is valid, THE P2P_File_Share_System SHALL connect to sender using Wi-Fi Direct
4. WHEN Wi-Fi Direct connection is established, THE P2P_File_Share_System SHALL perform ECDH_Handshake for encryption
5. WHEN encryption is established, THE P2P_File_Share_System SHALL begin receiving file transfer

### Requirement 4

**User Story:** As a user, I want files to be transferred securely with end-to-end encryption, so that my data remains private during transmission.

#### Acceptance Criteria

1. WHEN devices establish connection, THE P2P_File_Share_System SHALL perform ECDH key exchange using secp256r1 curve
2. WHEN ECDH_Handshake completes, THE P2P_File_Share_System SHALL derive AES-256-GCM encryption keys using HKDF-SHA256
3. WHEN file transfer begins, THE P2P_File_Share_System SHALL encrypt all file data using AES-256-GCM before transmission
4. WHEN encrypted data is received, THE P2P_File_Share_System SHALL decrypt using derived keys and verify integrity
5. WHEN session ends, THE P2P_File_Share_System SHALL securely dispose of all cryptographic keys

### Requirement 5

**User Story:** As a user, I want large files to transfer quickly and reliably, so that I can share files of any size efficiently.

#### Acceptance Criteria

1. WHEN file transfer begins, THE P2P_File_Share_System SHALL split files into 256KB chunks for parallel transmission
2. WHEN chunks are created, THE P2P_File_Share_System SHALL generate SHA-256 hash for each chunk for integrity verification
3. WHEN transmitting chunks, THE P2P_File_Share_System SHALL send multiple chunks in parallel using coroutines
4. WHEN chunks are received, THE P2P_File_Share_System SHALL verify chunk integrity and request retransmission for corrupted chunks
5. WHEN all chunks are received, THE P2P_File_Share_System SHALL reassemble file and verify complete file integrity

### Requirement 6

**User Story:** As a user, I want to see real-time transfer progress and speed, so that I can monitor the file sharing process.

#### Acceptance Criteria

1. WHEN file transfer is active, THE P2P_File_Share_System SHALL display transfer progress as percentage completed
2. WHEN transfer is in progress, THE P2P_File_Share_System SHALL calculate and display current transfer speed in MB/s
3. WHEN transfer speed is calculated, THE P2P_File_Share_System SHALL estimate and display remaining time (ETA)
4. WHEN transfer progress updates, THE P2P_File_Share_System SHALL refresh UI display without blocking main thread
5. WHEN transfer completes or fails, THE P2P_File_Share_System SHALL display final status and transfer statistics

### Requirement 7

**User Story:** As a user, I want file transfers to continue in the background, so that transfers are not interrupted when I use other apps or the screen turns off.

#### Acceptance Criteria

1. WHEN file transfer begins, THE P2P_File_Share_System SHALL start Transfer_Service as foreground service
2. WHEN Transfer_Service is active, THE P2P_File_Share_System SHALL display persistent notification showing transfer progress
3. WHEN device screen turns off, THE P2P_File_Share_System SHALL continue file transfer without interruption
4. WHEN user switches to other apps, THE P2P_File_Share_System SHALL maintain transfer progress in background
5. WHEN transfer completes, THE P2P_File_Share_System SHALL stop Transfer_Service and remove notification

### Requirement 8

**User Story:** As a user, I want to view my transfer history, so that I can track previously sent and received files.

#### Acceptance Criteria

1. WHEN file transfer completes, THE P2P_File_Share_System SHALL store transfer record with file metadata in local database
2. WHEN transfer record is created, THE P2P_File_Share_System SHALL include file name, size, direction, timestamp, and success status
3. WHEN user accesses history, THE P2P_File_Share_System SHALL display chronological list of all transfer records
4. WHEN displaying history, THE P2P_File_Share_System SHALL show transfer direction (sent/received) and completion status
5. WHEN history is displayed, THE P2P_File_Share_System SHALL allow user to clear individual records or entire history

### Requirement 9

**User Story:** As a developer, I want the application to follow Android best practices and use modern libraries, so that the app is maintainable, secure, and compatible with current Android versions.

#### Acceptance Criteria

1. WHEN building the application, THE P2P_File_Share_System SHALL target Android SDK 34 with minimum SDK 23 for broad device compatibility
2. WHEN implementing QR functionality, THE P2P_File_Share_System SHALL use ZXing library (com.journeyapps:zxing-android-embedded:4.3.0 and com.google.zxing:core:3.5.1) for QR generation and scanning
3. WHEN implementing camera features, THE P2P_File_Share_System SHALL use CameraX libraries (androidx.camera:camera-core:1.3.1, camera-camera2:1.3.1, camera-lifecycle:1.3.1, camera-view:1.3.1) for QR code scanning functionality
4. WHEN implementing database operations, THE P2P_File_Share_System SHALL use Room database (androidx.room:room-runtime:2.6.1, room-compiler:2.6.1, room-ktx:2.6.1) with Kotlin coroutines support
5. WHEN implementing asynchronous operations, THE P2P_File_Share_System SHALL use Kotlin coroutines (kotlinx-coroutines-core:1.7.3, kotlinx-coroutines-android:1.7.3) for non-blocking operations
6. WHEN implementing UI components, THE P2P_File_Share_System SHALL use AndroidX libraries (androidx.core:core-ktx:1.13.0, androidx.appcompat:appcompat:1.7.0, com.google.android.material:material:1.12.0)

### Requirement 10

**User Story:** As a developer, I want the application architecture to be modular and well-organized, so that different components can be developed and tested independently.

#### Acceptance Criteria

1. WHEN organizing code structure, THE P2P_File_Share_System SHALL implement Wi-Fi Direct functionality in com.p2pshare.app.wifi package
2. WHEN implementing QR functionality, THE P2P_File_Share_System SHALL organize QR generation and scanning in com.p2pshare.app.qr package
3. WHEN implementing security features, THE P2P_File_Share_System SHALL organize cryptographic functions in com.p2pshare.app.crypto package
4. WHEN implementing file transfer, THE P2P_File_Share_System SHALL organize transfer logic in com.p2pshare.app.transfer package
5. WHEN implementing background services, THE P2P_File_Share_System SHALL organize service classes in com.p2pshare.app.service package

### Requirement 11

**User Story:** As a user, I want the application to have a clean and intuitive interface, so that I can easily navigate and use all features.

#### Acceptance Criteria

1. WHEN designing user interface, THE P2P_File_Share_System SHALL implement Material Design guidelines using XML layouts (not Compose)
2. WHEN creating main interface, THE P2P_File_Share_System SHALL provide MainActivity with options to send or receive files
3. WHEN implementing send functionality, THE P2P_File_Share_System SHALL provide SendActivity with file picker and QR code display
4. WHEN implementing receive functionality, THE P2P_File_Share_System SHALL provide ReceiveActivity with QR scanner integration
5. WHEN showing transfer progress, THE P2P_File_Share_System SHALL provide TransferActivity with real-time progress display and transfer statistics

### Requirement 12

**User Story:** As a user, I want the application to handle Android permissions properly, so that all features work correctly while respecting system security.

#### Acceptance Criteria

1. WHEN accessing Wi-Fi Direct, THE P2P_File_Share_System SHALL request ACCESS_WIFI_STATE and CHANGE_WIFI_STATE permissions
2. WHEN using camera for QR scanning, THE P2P_File_Share_System SHALL request CAMERA permission with runtime permission handling
3. WHEN accessing device storage, THE P2P_File_Share_System SHALL request appropriate storage permissions for file access
4. WHEN creating network connections, THE P2P_File_Share_System SHALL request INTERNET and ACCESS_NETWORK_STATE permissions
5. WHEN running foreground service, THE P2P_File_Share_System SHALL request FOREGROUND_SERVICE permission and declare service in manifest

### Requirement 13

**User Story:** As a developer, I want the core modules to implement specific technical functionality, so that the system provides robust Wi-Fi Direct, cryptography, and file transfer capabilities.

#### Acceptance Criteria

1. WHEN implementing Wi-Fi Direct module, THE P2P_File_Share_System SHALL provide WifiDirectManager with peer discovery, group creation, connection management, and callbacks for onGroupCreated, onPeerAvailable, onConnected, onDisconnected
2. WHEN implementing cryptography module, THE P2P_File_Share_System SHALL provide EcdhHelper for secp256r1 keypair generation and shared secret computation, Hkdf for HKDF-SHA256 key derivation, and AesGcmCipher for AES-256-GCM encryption/decryption
3. WHEN implementing file transfer module, THE P2P_File_Share_System SHALL provide FileManifest with file metadata, ChunkManager for 256KB chunk splitting and resume capability, FileSender with ServerSocket and parallel chunk transmission, and FileReceiver with client connection and chunk reassembly
4. WHEN implementing QR module, THE P2P_File_Share_System SHALL provide QrGenerator accepting JSON strings and returning Bitmap using ZXing MultiFormatWriter, and QrScannerActivity with CameraX preview and QR detection returning scanned JSON
5. WHEN implementing transfer service, THE P2P_File_Share_System SHALL provide TransferService as foreground service with persistent notification, LiveData progress exposure, and coroutine-based file transfer execution without blocking main thread