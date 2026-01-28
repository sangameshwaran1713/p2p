package com.p2pshare.app

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.p2pshare.app.database.AppDatabase
import com.p2pshare.app.database.TransferRecord
import kotlinx.coroutines.launch

/**
 * Activity for displaying transfer history.
 * 
 * This activity shows:
 * - Chronological list of all file transfers
 * - Transfer direction, status, and metadata
 * - Options to filter, search, and manage history
 * - Statistics about transfer activity
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HistoryActivity"
    }

    // UI Components
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var cvStats: MaterialCardView
    private lateinit var tvTotalTransfers: TextView
    private lateinit var tvSuccessfulTransfers: TextView
    private lateinit var tvTotalData: TextView
    private lateinit var fabClearHistory: FloatingActionButton

    // Components
    private lateinit var historyAdapter: TransferHistoryAdapter
    private lateinit var database: AppDatabase
    private lateinit var repository: AppDatabase.TransferRepository

    // State
    private var currentFilter = FilterType.ALL

    enum class FilterType {
        ALL, SENT, RECEIVED, SUCCESSFUL, FAILED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        
        // Setup action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history_activity_title)
        
        initializeViews()
        initializeComponents()
        setupRecyclerView()
        setupClickListeners()
        observeTransferHistory()
        loadStatistics()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_filter -> {
                showFilterDialog()
                true
            }
            R.id.action_stats -> {
                showStatisticsDialog()
                true
            }
            R.id.action_clear_all -> {
                showClearAllDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Initializes all view references.
     */
    private fun initializeViews() {
        recyclerView = findViewById(R.id.rv_history)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        cvStats = findViewById(R.id.cv_stats)
        tvTotalTransfers = findViewById(R.id.tv_total_transfers)
        tvSuccessfulTransfers = findViewById(R.id.tv_successful_transfers)
        tvTotalData = findViewById(R.id.tv_total_data)
        fabClearHistory = findViewById(R.id.fab_clear_history)
    }

    /**
     * Initializes database components.
     */
    private fun initializeComponents() {
        database = AppDatabase.getDatabase(this)
        repository = AppDatabase.TransferRepository(database.transferDao())
        historyAdapter = TransferHistoryAdapter { record ->
            showRecordDetailsDialog(record)
        }
    }

    /**
     * Sets up the RecyclerView with adapter and layout manager.
     */
    private fun setupRecyclerView() {
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
            setHasFixedSize(true)
        }
    }

    /**
     * Sets up click listeners for UI components.
     */
    private fun setupClickListeners() {
        fabClearHistory.setOnClickListener {
            showClearAllDialog()
        }
    }

    /**
     * Observes transfer history from the database.
     */
    private fun observeTransferHistory() {
        val liveData = when (currentFilter) {
            FilterType.ALL -> repository.getAllTransfers()
            FilterType.SENT -> repository.getTransfersByDirection(TransferRecord.TransferDirection.SENT)
            FilterType.RECEIVED -> repository.getTransfersByDirection(TransferRecord.TransferDirection.RECEIVED)
            FilterType.SUCCESSFUL -> repository.getSuccessfulTransfers()
            FilterType.FAILED -> repository.getFailedTransfers()
        }

        liveData.observe(this, Observer { transfers ->
            updateHistoryList(transfers)
        })
    }

    /**
     * Updates the history list with new data.
     */
    private fun updateHistoryList(transfers: List<TransferRecord>) {
        if (transfers.isEmpty()) {
            showEmptyState()
        } else {
            showHistoryList()
            historyAdapter.submitList(transfers)
        }
    }

    /**
     * Shows empty state when no transfers are available.
     */
    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        tvEmptyState.visibility = View.VISIBLE
        cvStats.visibility = View.GONE
        
        tvEmptyState.text = when (currentFilter) {
            FilterType.ALL -> "No transfer history yet.\nStart sharing files to see your activity here."
            FilterType.SENT -> "No sent files yet.\nSend a file to see it here."
            FilterType.RECEIVED -> "No received files yet.\nReceive a file to see it here."
            FilterType.SUCCESSFUL -> "No successful transfers yet."
            FilterType.FAILED -> "No failed transfers found.\nThat's good news!"
        }
    }

    /**
     * Shows history list when transfers are available.
     */
    private fun showHistoryList() {
        recyclerView.visibility = View.VISIBLE
        tvEmptyState.visibility = View.GONE
        cvStats.visibility = View.VISIBLE
    }

    /**
     * Loads and displays transfer statistics.
     */
    private fun loadStatistics() {
        lifecycleScope.launch {
            try {
                val stats = repository.getTransferStatistics()
                
                tvTotalTransfers.text = stats.totalTransfers.toString()
                tvSuccessfulTransfers.text = "${stats.successfulTransfers} (${String.format("%.1f", stats.getSuccessRate())}%)"
                tvTotalData.text = stats.getFormattedTotalBytes()
                
            } catch (e: Exception) {
                // Handle error silently for statistics
                tvTotalTransfers.text = "0"
                tvSuccessfulTransfers.text = "0 (0%)"
                tvTotalData.text = "0 B"
            }
        }
    }

    /**
     * Shows filter dialog to change the current filter.
     */
    private fun showFilterDialog() {
        val filterOptions = arrayOf(
            "All Transfers",
            "Sent Files",
            "Received Files", 
            "Successful Only",
            "Failed Only"
        )

        val currentSelection = currentFilter.ordinal

        AlertDialog.Builder(this)
            .setTitle("Filter History")
            .setSingleChoiceItems(filterOptions, currentSelection) { dialog, which ->
                currentFilter = FilterType.values()[which]
                observeTransferHistory() // Re-observe with new filter
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows detailed statistics dialog.
     */
    private fun showStatisticsDialog() {
        lifecycleScope.launch {
            try {
                val stats = repository.getTransferStatistics()
                val largestTransfer = repository.getLargestTransfer()
                val fastestTransfer = repository.getFastestTransfer()

                val message = buildString {
                    append("📊 Transfer Statistics\n\n")
                    append("Total Transfers: ${stats.totalTransfers}\n")
                    append("Successful: ${stats.successfulTransfers} (${String.format("%.1f", stats.getSuccessRate())}%)\n")
                    append("Failed: ${stats.failedTransfers}\n\n")
                    append("📁 Data Statistics\n\n")
                    append("Total Data Transferred: ${stats.getFormattedTotalBytes()}\n")
                    append("Average Speed: ${stats.getFormattedAverageSpeed()}\n")
                    append("Average Duration: ${stats.getFormattedAverageDuration()}\n\n")
                    
                    if (largestTransfer != null) {
                        append("🏆 Records\n\n")
                        append("Largest File: ${largestTransfer.fileName} (${largestTransfer.getFormattedFileSize()})\n")
                    }
                    
                    if (fastestTransfer != null) {
                        append("Fastest Transfer: ${fastestTransfer.getFormattedAverageSpeed()}\n")
                    }
                }

                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("Statistics")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()

            } catch (e: Exception) {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("Statistics")
                    .setMessage("Unable to load statistics at this time.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * Shows record details dialog for a specific transfer.
     */
    private fun showRecordDetailsDialog(record: TransferRecord) {
        val message = buildString {
            append("📄 File Details\n\n")
            append("Name: ${record.fileName}\n")
            append("Size: ${record.getFormattedFileSize()}\n")
            append("Type: ${record.getDirectionString()}\n")
            append("Status: ${record.getStatusDescription()}\n\n")
            
            append("⏱️ Transfer Details\n\n")
            append("Date: ${java.text.SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", java.util.Locale.getDefault()).format(record.timestamp)}\n")
            append("Duration: ${record.getFormattedDuration()}\n")
            
            if (record.success) {
                append("Speed: ${record.getFormattedAverageSpeed()}\n")
                append("Progress: ${record.getFormattedBytesTransferred()} / ${record.getFormattedFileSize()}\n")
            } else {
                append("Transferred: ${record.getFormattedBytesTransferred()}\n")
                if (record.errorMessage != null) {
                    append("Error: ${record.errorMessage}\n")
                }
            }
            
            if (record.peerDeviceName != null) {
                append("\n🔗 Connection\n\n")
                append("Peer Device: ${record.peerDeviceName}\n")
            }
            
            if (record.transferId != null) {
                append("Transfer ID: ${record.transferId}\n")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Transfer Details")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Delete") { _, _ ->
                showDeleteRecordDialog(record)
            }
            .show()
    }

    /**
     * Shows confirmation dialog for deleting a single record.
     */
    private fun showDeleteRecordDialog(record: TransferRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete Transfer Record")
            .setMessage("Are you sure you want to delete this transfer record?\n\n${record.fileName}")
            .setPositiveButton("Delete") { _, _ ->
                deleteRecord(record)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows confirmation dialog for clearing all history.
     */
    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All History")
            .setMessage("Are you sure you want to clear all transfer history? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllHistory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Deletes a single transfer record.
     */
    private fun deleteRecord(record: TransferRecord) {
        lifecycleScope.launch {
            try {
                repository.deleteTransfer(record)
                loadStatistics() // Refresh statistics
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("Error")
                        .setMessage("Failed to delete record: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /**
     * Clears all transfer history.
     */
    private fun clearAllHistory() {
        lifecycleScope.launch {
            try {
                repository.deleteAllTransfers()
                loadStatistics() // Refresh statistics
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("Error")
                        .setMessage("Failed to clear history: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }
}