package com.p2pshare.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.p2pshare.app.service.TransferService
import com.p2pshare.app.service.TransferServiceHelper

/**
 * Activity for monitoring file transfer progress.
 * 
 * This activity displays:
 * - Real-time transfer progress, speed, and ETA
 * - File information and transfer statistics
 * - Transfer completion and error states
 * - Options to cancel transfer or view results
 */
class TransferActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TransferActivity"
    }

    // UI Components
    private lateinit var tvFileName: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvTransferType: TextView
    private lateinit var tvProgressPercentage: TextView
    private lateinit var tvProgressDetails: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnDone: MaterialButton
    private lateinit var cvProgress: MaterialCardView
    private lateinit var cvResult: MaterialCardView

    // Service binding
    private var transferService: TransferService? = null
    private var isBound = false

    // Transfer info from intent
    private var transferType: String = ""
    private var fileName: String = ""
    private var fileSize: Long = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TransferService.TransferBinder
            transferService = binder.getService()
            isBound = true
            observeTransferProgress()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer)
        
        initializeViews()
        getTransferInfo()
        setupClickListeners()
        bindToTransferService()
        updateFileInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    /**
     * Initializes all view references.
     */
    private fun initializeViews() {
        tvFileName = findViewById(R.id.tv_file_name)
        tvFileSize = findViewById(R.id.tv_file_size)
        tvTransferType = findViewById(R.id.tv_transfer_type)
        tvProgressPercentage = findViewById(R.id.tv_progress_percentage)
        tvProgressDetails = findViewById(R.id.tv_progress_details)
        tvSpeed = findViewById(R.id.tv_speed)
        tvEta = findViewById(R.id.tv_eta)
        tvStatus = findViewById(R.id.tv_status)
        progressIndicator = findViewById(R.id.progress_indicator)
        progressBar = findViewById(R.id.progress_bar)
        btnCancel = findViewById(R.id.btn_cancel)
        btnDone = findViewById(R.id.btn_done)
        cvProgress = findViewById(R.id.cv_progress)
        cvResult = findViewById(R.id.cv_result)
    }

    /**
     * Gets transfer information from intent extras.
     */
    private fun getTransferInfo() {
        transferType = intent.getStringExtra("transfer_type") ?: "unknown"
        fileName = intent.getStringExtra("file_name") ?: "Unknown File"
        fileSize = intent.getLongExtra("file_size", 0L)
    }

    /**
     * Sets up click listeners for buttons.
     */
    private fun setupClickListeners() {
        btnCancel.setOnClickListener {
            cancelTransfer()
        }

        btnDone.setOnClickListener {
            finish()
        }

        // Back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    /**
     * Binds to the TransferService to observe progress.
     */
    private fun bindToTransferService() {
        val intent = Intent(this, TransferService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Updates file information display.
     */
    private fun updateFileInfo() {
        tvFileName.text = fileName
        tvFileSize.text = TransferServiceHelper.formatFileSize(fileSize)
        tvTransferType.text = when (transferType) {
            "send" -> "Sending File"
            "receive" -> "Receiving File"
            else -> "File Transfer"
        }
    }

    /**
     * Observes transfer progress from the service.
     */
    private fun observeTransferProgress() {
        val service = transferService ?: return

        // Observe transfer progress
        service.transferProgress.observe(this, Observer { progress ->
            updateProgressUI(progress)
        })

        // Observe transfer state
        service.transferState.observe(this, Observer { state ->
            updateStateUI(state)
        })
    }

    /**
     * Updates progress UI with current transfer progress.
     */
    private fun updateProgressUI(progress: TransferService.TransferProgress) {
        // Update progress percentage
        val percentage = progress.progressPercentage.toInt()
        tvProgressPercentage.text = "$percentage%"
        progressIndicator.progress = percentage

        // Update progress details
        val transferred = TransferServiceHelper.formatFileSize(progress.bytesTransferred)
        val total = TransferServiceHelper.formatFileSize(progress.totalBytes)
        tvProgressDetails.text = "$transferred / $total"

        // Update speed
        if (progress.speedBytesPerSecond > 0) {
            tvSpeed.text = TransferServiceHelper.formatTransferSpeed(progress.speedBytesPerSecond)
        } else {
            tvSpeed.text = "Calculating..."
        }

        // Update ETA
        if (progress.estimatedTimeRemainingMs > 0) {
            tvEta.text = TransferServiceHelper.formatDuration(progress.estimatedTimeRemainingMs)
        } else {
            tvEta.text = "Calculating..."
        }

        // Show progress card
        cvProgress.visibility = View.VISIBLE
    }

    /**
     * Updates state UI based on transfer state.
     */
    private fun updateStateUI(state: TransferService.TransferState) {
        when (state) {
            TransferService.TransferState.IDLE -> {
                tvStatus.text = "Initializing..."
                showProgress(true)
            }
            
            TransferService.TransferState.PREPARING -> {
                tvStatus.text = "Preparing transfer..."
                showProgress(true)
            }
            
            TransferService.TransferState.CONNECTING -> {
                tvStatus.text = "Connecting to peer..."
                showProgress(true)
            }
            
            TransferService.TransferState.TRANSFERRING -> {
                tvStatus.text = when (transferType) {
                    "send" -> "Sending file..."
                    "receive" -> "Receiving file..."
                    else -> "Transferring file..."
                }
                showProgress(false)
                btnCancel.isEnabled = true
            }
            
            TransferService.TransferState.COMPLETED -> {
                tvStatus.text = "Transfer completed successfully!"
                showTransferComplete(true)
            }
            
            TransferService.TransferState.FAILED -> {
                val errorMessage = transferService?.getCurrentProgress()?.errorMessage
                tvStatus.text = errorMessage ?: "Transfer failed"
                showTransferComplete(false)
            }
            
            TransferService.TransferState.CANCELLED -> {
                tvStatus.text = "Transfer cancelled"
                showTransferComplete(false)
            }
        }
    }

    /**
     * Shows or hides indeterminate progress indicator.
     */
    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Shows transfer completion UI.
     */
    private fun showTransferComplete(success: Boolean) {
        showProgress(false)
        cvProgress.visibility = View.GONE
        cvResult.visibility = View.VISIBLE
        
        btnCancel.visibility = View.GONE
        btnDone.visibility = View.VISIBLE
        
        // Update result message
        val resultMessage = if (success) {
            when (transferType) {
                "send" -> "File sent successfully!"
                "receive" -> "File received successfully!"
                else -> "Transfer completed!"
            }
        } else {
            "Transfer was not completed"
        }
        
        findViewById<TextView>(R.id.tv_result_message).text = resultMessage
        
        // Update result icon
        val resultIcon = findViewById<android.widget.ImageView>(R.id.iv_result_icon)
        if (success) {
            resultIcon.setImageResource(R.drawable.ic_check_circle)
            resultIcon.setColorFilter(getColor(android.R.color.holo_green_dark))
        } else {
            resultIcon.setImageResource(R.drawable.ic_error_circle)
            resultIcon.setColorFilter(getColor(android.R.color.holo_red_dark))
        }
    }

    /**
     * Cancels the ongoing transfer.
     */
    private fun cancelTransfer() {
        TransferServiceHelper.cancelTransfer(this)
        btnCancel.isEnabled = false
        tvStatus.text = "Cancelling transfer..."
    }

    /**
     * Handles back button press during transfer.
     */
    override fun onBackPressed() {
        val service = transferService
        if (service != null && service.isTransferActive()) {
            // Show confirmation dialog for active transfer
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Transfer in Progress")
                .setMessage("A file transfer is currently in progress. Do you want to cancel it?")
                .setPositiveButton("Cancel Transfer") { _, _ ->
                    cancelTransfer()
                    super.onBackPressed()
                }
                .setNegativeButton("Continue Transfer", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}