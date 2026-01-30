Overview
The P2P File Share application is a secure, high-performance Android application that enables direct file sharing between devices using Wi-Fi Direct technology. The system combines QR code-based device pairing, end-to-end encryption, and chunked file transfer to provide a seamless offline file sharing experience.

The application follows a modular architecture with clear separation of concerns across networking, cryptography, UI, and data persistence layers. The design prioritizes security through ECDH key exchange and AES-GCM encryption, performance through parallel chunk transfer, and user experience through real-time progress monitoring and background operation.

Architecture
The application follows a layered architecture pattern with the following components:

Presentation Layer
MainActivity: Entry point with send/receive options
SendActivity: File selection and QR code display
ReceiveActivity: QR code scanning interface
TransferActivity: Real-time transfer progress monitoring
QrScannerActivity: Camera-based QR code detection

Business Logic Layer
WifiDirectManager: Wi-Fi Direct connection management
TransferService: Background file transfer orchestration
QrGenerator: QR code generation for session info
Crypto Module: ECDH key exchange and AES-GCM encryption

Data Layer
File Transfer Module: Chunked file transmission and reception
Room Database: Transfer history persistence
File System: Local file access and storage

Network Layer
ServerSocket: TCP server for file sender
Client Socket: TCP client for file receiver
Wi-Fi Direct: Device-to-device connectivity
