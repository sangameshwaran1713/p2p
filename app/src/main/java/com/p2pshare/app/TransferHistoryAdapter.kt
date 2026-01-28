package com.p2pshare.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.p2pshare.app.database.TransferRecord
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for displaying transfer history records.
 * 
 * This adapter handles:
 * - Displaying transfer records in a list format
 * - Showing transfer direction, status, and metadata
 * - Handling item click events for detailed view
 * - Efficient updates using DiffUtil
 */
class TransferHistoryAdapter(
    private val onItemClick: (TransferRecord) -> Unit
) : ListAdapter<TransferRecord, TransferHistoryAdapter.TransferViewHolder>(TransferDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransferViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return TransferViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: TransferViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * ViewHolder for transfer record items.
     */
    class TransferViewHolder(
        itemView: View,
        private val onItemClick: (TransferRecord) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivDirection: ImageView = itemView.findViewById(R.id.iv_direction)
        private val ivStatus: ImageView = itemView.findViewById(R.id.iv_status)
        private val tvFileName: TextView = itemView.findViewById(R.id.tv_file_name)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tv_file_size)
        private val tvDirection: TextView = itemView.findViewById(R.id.tv_direction)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tv_timestamp)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_status)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tv_speed)

        private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

        fun bind(record: TransferRecord) {
            // Set file information
            tvFileName.text = record.fileName
            tvFileSize.text = record.getFormattedFileSize()
            tvTimestamp.text = dateFormat.format(record.timestamp)

            // Set direction
            when (record.direction) {
                TransferRecord.TransferDirection.SENT -> {
                    ivDirection.setImageResource(R.drawable.ic_send)
                    ivDirection.setColorFilter(ContextCompat.getColor(itemView.context, R.color.primary_color))
                    tvDirection.text = "Sent"
                    tvDirection.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary_color))
                }
                TransferRecord.TransferDirection.RECEIVED -> {
                    ivDirection.setImageResource(R.drawable.ic_receive)
                    ivDirection.setColorFilter(ContextCompat.getColor(itemView.context, R.color.md_theme_light_tertiary))
                    tvDirection.text = "Received"
                    tvDirection.setTextColor(ContextCompat.getColor(itemView.context, R.color.md_theme_light_tertiary))
                }
            }

            // Set status
            if (record.success) {
                ivStatus.setImageResource(R.drawable.ic_check_circle)
                ivStatus.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                tvStatus.text = "Completed"
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                
                // Show speed for successful transfers
                if (record.averageSpeed > 0) {
                    tvSpeed.text = record.getFormattedAverageSpeed()
                    tvSpeed.visibility = View.VISIBLE
                } else {
                    tvSpeed.visibility = View.GONE
                }
            } else {
                ivStatus.setImageResource(R.drawable.ic_error_circle)
                ivStatus.setColorFilter(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                tvStatus.text = "Failed"
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                
                // Show progress for failed transfers
                val progressText = "${record.getFormattedBytesTransferred()} / ${record.getFormattedFileSize()}"
                tvSpeed.text = progressText
                tvSpeed.visibility = View.VISIBLE
            }

            // Set click listener
            itemView.setOnClickListener {
                onItemClick(record)
            }

            // Add ripple effect
            itemView.isClickable = true
            itemView.isFocusable = true
        }
    }

    /**
     * DiffUtil callback for efficient list updates.
     */
    class TransferDiffCallback : DiffUtil.ItemCallback<TransferRecord>() {
        override fun areItemsTheSame(oldItem: TransferRecord, newItem: TransferRecord): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TransferRecord, newItem: TransferRecord): Boolean {
            return oldItem == newItem
        }
    }
}