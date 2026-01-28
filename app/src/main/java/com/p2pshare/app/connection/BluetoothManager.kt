package com.p2pshare.app.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.io.*
import java.util.*

/**
 * Manager for Bluetooth file transfer connections.
 * 
 * This class provides Bluetooth functionality as a fallback option
 * when Wi-Fi Direct and Hotspot methods are not available.
 */
class BluetoothManager(
    private val context: Context,
    private val callback: BluetoothCallback
) {
    
    companion object {
        private const val TAG = "BluetoothManager"
        private const val SERVICE_NAME = "P2PFileShare"
        private val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        private const val DISCOVERY_TIMEOUT_MS = 30000L // 30 seconds
    }
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var isDiscovering = false
    private var isServer = false
    
    private var discoveryTimeoutRunnable: Runnable? = null
    
    /**
     * Callback interface for Bluetooth events.
     */
    interface BluetoothCallback {
        fun onBluetoothReady(deviceName: String, isDiscoverable: Boolean)
        fun onDeviceDiscovered(device: BluetoothDevice)
        fun onConnectionEstablished(device: BluetoothDevice, isServer: Boolean)
        fun onConnectionFailed(error: String)
        fun onDisconnected()
        fun onDataReceived(data: ByteArray)
        fun onDataSent(bytesCount: Int)
        fun onError(error: String)
    }
    
    /**
     * Bluetooth device discovery receiver.
     */
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { callback.onDeviceDiscovered(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isDiscovering = false
                    cancelDiscoveryTimeout()
                }
            }
        }
    }
    
    /**
     * Initializes Bluetooth for file sharing.
     */
    fun initialize(): Boolean {
        return try {
            if (bluetoothAdapter == null) {
                callback.onError("Bluetooth not supported on this device")
                return false
            }
            
            if (!bluetoothAdapter.isEnabled) {
                callback.onError("Bluetooth is not enabled")
                return false
            }
            
            // Register discovery receiver
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(discoveryReceiver, filter)
            
            // Make device discoverable
            makeDiscoverable()
            
            val deviceName = bluetoothAdapter.name ?: "Unknown Device"
            callback.onBluetoothReady(deviceName, true)
            
            true
        } catch (e: Exception) {
            callback.onError("Bluetooth initialization failed: ${e.message}")
            false
        }
    }
    
    /**
     * Starts as server (sender) waiting for connections.
     */
    fun startAsServer(): Boolean {
        return try {
            if (bluetoothAdapter == null) {
                callback.onError("Bluetooth not available")
                return false
            }
            
            isServer = true
            
            // Create server socket
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            
            // Accept connections in background thread
            Thread {
                try {
                    val socket = serverSocket?.accept()
                    socket?.let { clientSocket ->
                        this.clientSocket = clientSocket
                        handler.post {
                            callback.onConnectionEstablished(clientSocket.remoteDevice, true)
                        }
                    }
                } catch (e: IOException) {
                    handler.post {
                        callback.onConnectionFailed("Server connection failed: ${e.message}")
                    }
                }
            }.start()
            
            true
        } catch (e: Exception) {
            callback.onConnectionFailed("Failed to start server: ${e.message}")
            false
        }
    }
    
    /**
     * Connects to a server device (receiver).
     */
    fun connectToDevice(device: BluetoothDevice): Boolean {
        return try {
            isServer = false
            
            // Stop discovery if running
            if (isDiscovering) {
                bluetoothAdapter?.cancelDiscovery()
            }
            
            // Connect in background thread
            Thread {
                try {
                    val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    socket.connect()
                    
                    clientSocket = socket
                    handler.post {
                        callback.onConnectionEstablished(device, false)
                    }
                } catch (e: IOException) {
                    handler.post {
                        callback.onConnectionFailed("Connection failed: ${e.message}")
                    }
                }
            }.start()
            
            true
        } catch (e: Exception) {
            callback.onConnectionFailed("Failed to connect: ${e.message}")
            false
        }
    }
    
    /**
     * Starts device discovery.
     */
    fun startDiscovery(): Boolean {
        return try {
            if (bluetoothAdapter == null) {
                callback.onError("Bluetooth not available")
                return false
            }
            
            if (isDiscovering) {
                return true // Already discovering
            }
            
            // Cancel any ongoing discovery
            bluetoothAdapter.cancelDiscovery()
            
            // Start new discovery
            val success = bluetoothAdapter.startDiscovery()
            if (success) {
                isDiscovering = true
                startDiscoveryTimeout()
            } else {
                callback.onError("Failed to start device discovery")
            }
            
            success
        } catch (e: Exception) {
            callback.onError("Discovery failed: ${e.message}")
            false
        }
    }
    
    /**
     * Stops device discovery.
     */
    fun stopDiscovery() {
        try {
            if (isDiscovering) {
                bluetoothAdapter?.cancelDiscovery()
                isDiscovering = false
                cancelDiscoveryTimeout()
            }
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }
    
    /**
     * Sends data over Bluetooth connection.
     */
    fun sendData(data: ByteArray): Boolean {
        return try {
            val socket = clientSocket
            if (socket == null || !socket.isConnected) {
                callback.onError("No active Bluetooth connection")
                return false
            }
            
            Thread {
                try {
                    val outputStream = socket.outputStream
                    outputStream.write(data)
                    outputStream.flush()
                    
                    handler.post {
                        callback.onDataSent(data.size)
                    }
                } catch (e: IOException) {
                    handler.post {
                        callback.onError("Failed to send data: ${e.message}")
                    }
                }
            }.start()
            
            true
        } catch (e: Exception) {
            callback.onError("Send failed: ${e.message}")
            false
        }
    }
    
    /**
     * Starts listening for incoming data.
     */
    fun startListening() {
        val socket = clientSocket ?: return
        
        Thread {
            try {
                val inputStream = socket.inputStream
                val buffer = ByteArray(1024)
                
                while (socket.isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        handler.post {
                            callback.onDataReceived(data)
                        }
                    }
                }
            } catch (e: IOException) {
                handler.post {
                    callback.onDisconnected()
                }
            }
        }.start()
    }
    
    /**
     * Sends a file over Bluetooth connection.
     */
    fun sendFile(file: File): Boolean {
        return try {
            val socket = clientSocket
            if (socket == null || !socket.isConnected) {
                callback.onError("No active Bluetooth connection")
                return false
            }
            
            Thread {
                try {
                    val outputStream = socket.outputStream
                    val fileInputStream = FileInputStream(file)
                    
                    // Send file size first
                    val fileSize = file.length()
                    val fileSizeBytes = fileSize.toString().toByteArray()
                    outputStream.write(fileSizeBytes.size)
                    outputStream.write(fileSizeBytes)
                    
                    // Send file name
                    val fileName = file.name.toByteArray()
                    outputStream.write(fileName.size)
                    outputStream.write(fileName)
                    
                    // Send file data
                    val buffer = ByteArray(1024)
                    var totalSent = 0
                    var bytesRead: Int
                    
                    while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalSent += bytesRead
                        
                        handler.post {
                            callback.onDataSent(totalSent)
                        }
                    }
                    
                    outputStream.flush()
                    fileInputStream.close()
                    
                } catch (e: IOException) {
                    handler.post {
                        callback.onError("File transfer failed: ${e.message}")
                    }
                }
            }.start()
            
            true
        } catch (e: Exception) {
            callback.onError("File send failed: ${e.message}")
            false
        }
    }
    
    /**
     * Receives a file over Bluetooth connection.
     */
    fun receiveFile(outputDirectory: File): Boolean {
        return try {
            val socket = clientSocket
            if (socket == null || !socket.isConnected) {
                callback.onError("No active Bluetooth connection")
                return false
            }
            
            Thread {
                try {
                    val inputStream = socket.inputStream
                    
                    // Read file size
                    val fileSizeLength = inputStream.read()
                    val fileSizeBytes = ByteArray(fileSizeLength)
                    inputStream.read(fileSizeBytes)
                    val fileSize = String(fileSizeBytes).toLong()
                    
                    // Read file name
                    val fileNameLength = inputStream.read()
                    val fileNameBytes = ByteArray(fileNameLength)
                    inputStream.read(fileNameBytes)
                    val fileName = String(fileNameBytes)
                    
                    // Create output file
                    val outputFile = File(outputDirectory, fileName)
                    val fileOutputStream = FileOutputStream(outputFile)
                    
                    // Read file data
                    val buffer = ByteArray(1024)
                    var totalReceived = 0L
                    var bytesRead = 0
                    
                    while (totalReceived < fileSize && inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val bytesToWrite = minOf(bytesRead.toLong(), fileSize - totalReceived).toInt()
                        fileOutputStream.write(buffer, 0, bytesToWrite)
                        totalReceived += bytesToWrite
                        
                        handler.post {
                            callback.onDataReceived(buffer.copyOf(bytesToWrite))
                        }
                    }
                    
                    fileOutputStream.close()
                    
                } catch (e: IOException) {
                    handler.post {
                        callback.onError("File receive failed: ${e.message}")
                    }
                }
            }.start()
            
            true
        } catch (e: Exception) {
            callback.onError("File receive failed: ${e.message}")
            false
        }
    }
    
    /**
     * Disconnects the current connection.
     */
    fun disconnect() {
        try {
            clientSocket?.close()
            clientSocket = null
            
            serverSocket?.close()
            serverSocket = null
            
            callback.onDisconnected()
        } catch (e: Exception) {
            callback.onError("Disconnect failed: ${e.message}")
        }
    }
    
    /**
     * Gets paired devices.
     */
    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }
    
    /**
     * Checks if Bluetooth is available and enabled.
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Makes device discoverable.
     */
    private fun makeDiscoverable() {
        try {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 5 minutes
            }
            // Note: This requires starting an activity, which should be handled by the calling activity
        } catch (e: Exception) {
            // Ignore if can't make discoverable
        }
    }
    
    /**
     * Starts discovery timeout timer.
     */
    private fun startDiscoveryTimeout() {
        cancelDiscoveryTimeout()
        discoveryTimeoutRunnable = Runnable {
            if (isDiscovering) {
                stopDiscovery()
                callback.onError("Device discovery timed out")
            }
        }
        handler.postDelayed(discoveryTimeoutRunnable!!, DISCOVERY_TIMEOUT_MS)
    }
    
    /**
     * Cancels discovery timeout timer.
     */
    private fun cancelDiscoveryTimeout() {
        discoveryTimeoutRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            discoveryTimeoutRunnable = null
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        stopDiscovery()
        disconnect()
        
        try {
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: Exception) {
            // Receiver may not be registered
        }
        
        cancelDiscoveryTimeout()
    }
}