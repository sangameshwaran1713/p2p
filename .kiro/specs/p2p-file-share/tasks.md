# Implementation Plan

- [x] 1. Set up Android project structure and dependencies



  - Create Android project with Kotlin, minimum SDK 23, target SDK 34
  - Configure build.gradle with all required dependencies (ZXing, CameraX, Room, Coroutines)
  - Set up package structure (wifi, qr, crypto, transfer, service packages)
  - Configure AndroidManifest.xml with required permissions





  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 10.1, 10.2, 10.3, 10.4, 10.5, 12.1, 12.2, 12.3, 12.4, 12.5_

- [x] 2. Implement cryptography module


  - [ ] 2.1 Create EcdhHelper class for secp256r1 key generation and shared secret computation
    - Implement generateKeyPair() method using EC KeyPairGenerator


    - Implement computeSharedSecret() method for ECDH key agreement
    - _Requirements: 4.1, 13.2_
  


  - [ ] 2.2 Write property test for ECDH key exchange consistency
    - **Property 5: ECDH Key Exchange Consistency**
    - **Validates: Requirements 4.1, 4.2**


  
  - [x] 2.3 Create Hkdf class for HKDF-SHA256 key derivation


    - Implement deriveKey() method using HKDF-SHA256 algorithm
    - Support custom salt, info, and output length parameters





    - _Requirements: 4.2, 13.2_
  
  - [ ] 2.4 Create AesGcmCipher class for AES-256-GCM encryption/decryption
    - Implement encrypt() method with IV generation
    - Implement decrypt() method with integrity verification


    - _Requirements: 4.3, 4.4, 13.2_
  
  - [ ] 2.5 Write property test for encryption round trip
    - **Property 2: Encryption Round Trip**
    - **Validates: Requirements 4.3, 4.4**


  
  - [x] 2.6 Write unit tests for cryptographic functions


    - Test ECDH key generation and shared secret computation
    - Test HKDF key derivation with various parameters





    - Test AES-GCM encryption/decryption with different data sizes
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 3. Implement Wi-Fi Direct module



  - [ ] 3.1 Create WifiDirectManager class with peer discovery and connection management
    - Implement peer discovery using WifiP2pManager
    - Implement group creation as group owner
    - Implement connection management with callbacks


    - Provide onGroupCreated, onPeerAvailable, onConnected, onDisconnected callbacks
    - _Requirements: 2.2, 13.1_


  
  - [x] 3.2 Create WifiDirectReceiver for handling Wi-Fi Direct broadcasts





    - Handle WIFI_P2P_STATE_CHANGED_ACTION
    - Handle WIFI_P2P_PEERS_CHANGED_ACTION
    - Handle WIFI_P2P_CONNECTION_CHANGED_ACTION


    - Integrate with WifiDirectManager callbacks
    - _Requirements: 1.2, 3.3, 13.1_
  
  - [x] 3.3 Write property test for Wi-Fi Direct connection establishment


    - **Property 6: Wi-Fi Direct Connection Establishment**
    - **Validates: Requirements 1.2, 3.3**


  
  - [ ] 3.4 Write unit tests for Wi-Fi Direct functionality
    - Test peer discovery and group creation
    - Test connection state management


    - Test callback invocation
    - _Requirements: 2.2, 1.2, 3.3_

- [ ] 4. Implement QR code module
  - [x] 4.1 Create QrGenerator class for QR code generation


    - Implement generateQrCode() method using ZXing MultiFormatWriter
    - Accept JSON session info and return Bitmap


    - Configure QR code size and error correction
    - _Requirements: 2.4, 13.4_


  
  - [ ] 4.2 Create QrScannerActivity with CameraX integration
    - Implement CameraX preview for camera display
    - Integrate ZXing for QR code detection
    - Return scanned JSON to calling activity
    - Handle camera permissions and errors

    - _Requirements: 3.1, 3.2, 13.4_
  
  - [ ] 4.3 Write property test for QR session info round trip
    - **Property 4: QR Session Info Round Trip**
    - **Validates: Requirements 2.3, 3.2**


  
  - [ ] 4.4 Write unit tests for QR functionality
    - Test QR code generation with various session data
    - Test QR code scanning and JSON parsing
    - Test error handling for malformed QR codes
    - _Requirements: 2.4, 3.1, 3.2_

- [ ] 5. Implement file transfer module
  - [ ] 5.1 Create FileManifest data class for file metadata
    - Define fileName, fileSize, chunkSize, chunkCount, chunkHashes fields
    - Implement JSON serialization/deserialization
    - _Requirements: 5.1, 5.2, 13.3_
  
  - [ ] 5.2 Create ChunkManager class for file chunking and tracking
    - Implement splitFile() method for 256KB chunk creation


    - Implement chunk tracking with BooleanArray for resume capability
    - Generate SHA-256 hash for each chunk
    - _Requirements: 5.1, 5.2, 5.4, 13.3_


  
  - [ ] 5.3 Write property test for chunk integrity preservation
    - **Property 3: Chunk Integrity Preservation**


    - **Validates: Requirements 5.2, 5.4**
  
  - [x] 5.4 Create FileSender class with ServerSocket implementation


    - Implement TCP server socket for file transmission
    - Perform ECDH handshake with receiver



    - Send file manifest and chunks in parallel using coroutines
    - _Requirements: 5.3, 13.3_
  
  - [x] 5.5 Create FileReceiver class with client socket implementation


    - Implement TCP client connection to sender
    - Perform ECDH handshake with sender
    - Receive manifest and request missing chunks
    - Reassemble file from received chunks


    - _Requirements: 5.4, 5.5, 13.3_
  
  - [ ] 5.6 Write property test for file transfer round trip
    - **Property 1: File Transfer Round Trip**
    - **Validates: Requirements 5.5**


  
  - [ ] 5.7 Write property test for parallel chunk transfer efficiency
    - **Property 13: Parallel Chunk Transfer Efficiency**
    - **Validates: Requirements 5.3**
  


  - [ ] 5.8 Write unit tests for file transfer components
    - Test file chunking and reassembly
    - Test manifest creation and parsing
    - Test chunk integrity verification
    - _Requirements: 5.1, 5.2, 5.4, 5.5_



- [ ] 6. Checkpoint - Ensure all core modules are working
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Implement transfer service for background operation
  - [ ] 7.1 Create TransferService as foreground service
    - Extend Service class with foreground service capabilities
    - Create persistent notification for transfer progress
    - Implement service lifecycle management
    - _Requirements: 7.1, 7.2, 13.5_
  
  - [ ] 7.2 Integrate FileSender and FileReceiver with TransferService
    - Execute file transfers using coroutines within service scope
    - Expose LiveData for transfer progress monitoring
    - Handle transfer completion and error scenarios
    - _Requirements: 7.3, 7.4, 7.5, 13.5_
  


  - [ ] 7.3 Write property test for background service persistence
    - **Property 8: Background Service Persistence**
    - **Validates: Requirements 7.3, 7.4**
  

  - [ ] 7.4 Write unit tests for transfer service
    - Test service lifecycle and notification management
    - Test progress reporting and LiveData updates
    - Test transfer execution in background
    - _Requirements: 7.1, 7.2, 7.5_



- [-] 8. Implement database layer with Room

  - [ ] 8.1 Create TransferRecord entity for transfer history
    - Define entity with id, fileName, fileSize, direction, timestamp, duration, success fields
    - Configure Room annotations and relationships
    - _Requirements: 8.2, 13.5_
  
  - [ ] 8.2 Create TransferDao interface for database operations
    - Implement insert, query, delete operations for transfer records


    - Support chronological ordering and filtering
    - _Requirements: 8.3, 8.5_
  
  - [x] 8.3 Create AppDatabase class with Room configuration


    - Configure database version and entity relationships
    - Provide DAO access methods
    - _Requirements: 8.1, 13.5_
  
  - [ ] 8.4 Write property test for database record completeness
    - **Property 9: Database Record Completeness**
    - **Validates: Requirements 8.1, 8.2**
  
  - [ ] 8.5 Write unit tests for database operations
    - Test transfer record insertion and retrieval
    - Test chronological ordering and filtering
    - Test record deletion functionality
    - _Requirements: 8.1, 8.2, 8.3, 8.5_

- [ ] 9. Implement UI activities and layouts
  - [ ] 9.1 Create MainActivity with send/receive options
    - Design Material Design layout with send and receive buttons
    - Implement navigation to SendActivity and ReceiveActivity
    - Handle permission requests and app initialization
    - _Requirements: 11.2_
  
  - [ ] 9.2 Create SendActivity with file picker and QR display
    - Implement file picker using Intent.ACTION_GET_CONTENT
    - Integrate WifiDirectManager for group creation
    - Display QR code using QrGenerator
    - Handle file selection validation
    - _Requirements: 2.1, 2.3, 2.4, 11.3_
  
  - [ ] 9.3 Create ReceiveActivity with QR scanner integration
    - Launch QrScannerActivity for QR code scanning
    - Parse scanned session info and validate
    - Integrate WifiDirectManager for connection
    - Navigate to TransferActivity after connection
    - _Requirements: 3.2, 3.3, 11.4_
  
  - [ ] 9.4 Create TransferActivity for progress monitoring
    - Display real-time transfer progress, speed, and ETA
    - Observe TransferService LiveData for updates
    - Handle transfer completion and error states
    - Provide transfer statistics and final status
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 11.5_
  
  - [ ] 9.5 Create XML layouts for all activities
    - Design activity_main.xml with Material Design components
    - Design activity_send.xml with file picker and QR display
    - Design activity_receive.xml with scanner integration
    - Design activity_transfer.xml with progress indicators
    - Design activity_qr_scanner.xml with camera preview
    - _Requirements: 11.1_
  
  - [ ]* 9.6 Write property test for transfer progress monotonicity
    - **Property 7: Transfer Progress Monotonicity**
    - **Validates: Requirements 6.1**
  
  - [ ]* 9.7 Write unit tests for UI activities
    - Test activity navigation and lifecycle
    - Test progress display and updates
    - Test error handling and user feedback
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [ ] 10. Implement transfer history functionality
  - [ ] 10.1 Create HistoryActivity for displaying transfer records
    - Implement RecyclerView with transfer record adapter
    - Display chronological list of all transfers
    - Show transfer direction, status, and metadata
    - _Requirements: 8.3, 8.4_
  
  - [ ] 10.2 Add history management features
    - Implement individual record deletion
    - Implement bulk history clearing
    - Add navigation from MainActivity to HistoryActivity
    - _Requirements: 8.5_
  
  - [ ] 10.3 Create item_history.xml layout for RecyclerView items
    - Design layout showing file name, size, direction, timestamp
    - Include status indicators and action buttons
    - _Requirements: 8.4_
  
  - [ ]* 10.4 Write unit tests for history functionality
    - Test history display and record formatting
    - Test record deletion and bulk operations
    - Test navigation and user interactions
    - _Requirements: 8.3, 8.4, 8.5_

- [ ] 11. Implement security and permission handling
  - [x] 11.1 Add runtime permission handling for all required permissions


    - Implement camera permission handling for QR scanning
    - Implement storage permission handling for file access
    - Implement Wi-Fi and network permission handling
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_


  

  - [ ] 11.2 Implement secure key management and cleanup
    - Ensure cryptographic keys are securely disposed after use
    - Implement memory clearing for sensitive data
    - _Requirements: 4.5_
  
  - [ ]* 11.3 Write property test for cryptographic key cleanup
    - **Property 10: Cryptographic Key Cleanup**
    - **Validates: Requirements 4.5**
  
  - [ ]* 11.4 Write property test for permission request consistency
    - **Property 11: Permission Request Consistency**
    - **Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5**
  
  - [ ]* 11.5 Write unit tests for security and permissions
    - Test permission request flows and handling

    - Test key cleanup and memory management

    - Test secure session termination
    - _Requirements: 4.5, 12.1, 12.2, 12.3, 12.4, 12.5_

- [ ] 12. Final integration and testing
  - [x] 12.1 Integrate all modules for end-to-end functionality

    - Connect SendActivity with WifiDirectManager, QrGenerator, and TransferService
    - Connect ReceiveActivity with QrScanner, WifiDirectManager, and TransferService
    - Ensure proper data flow between all components
    - _Requirements: 1.1, 1.3, 1.4_
  
  - [ ] 12.2 Implement offline operation validation
    - Ensure all functionality works without internet connectivity
    - Test Wi-Fi Direct operation in offline mode
    - _Requirements: 1.5_
  
  - [ ]* 12.3 Write property test for offline operation completeness
    - **Property 12: Offline Operation Completeness**
    - **Validates: Requirements 1.5**



  
  - [ ]* 12.4 Write integration tests for end-to-end scenarios
    - Test complete send-receive workflow
    - Test error recovery and edge cases
    - Test performance with large files
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 13. Final checkpoint - Complete system validation
  - Ensure all tests pass, ask the user if questions arise.