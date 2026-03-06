package com.example.codevisualizerapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.codevisualizerapp.R

// Ensure data class PyFile is defined in PyFile.kt

class FileAdapter(
    private var files: MutableList<PyFile>,
    private val onFileClicked: (PyFile) -> Unit,
    private val onFileAction: (PyFile, String) -> Unit // Callback for Rename/Delete
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.file_name)
        val fileDate: TextView = view.findViewById(R.id.file_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_card, parent, false)
        return FileViewHolder(view)
    }

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.fileName.text = file.fileName
        holder.fileDate.text = file.dateSaved

        // 1. Normal Click (Open File)
        holder.itemView.setOnClickListener {
            onFileClicked(file)
        }

        // 2. LONG PRESS (Show Popup Menu)
        holder.itemView.setOnLongClickListener { view ->
            val popup = PopupMenu(view.context, view)
            popup.menu.add("Rename")
            popup.menu.add("Delete")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Rename" -> onFileAction(file, "RENAME")
                    "Delete" -> onFileAction(file, "DELETE")
                }
                true
            }
            popup.show()
            true // Return true to indicate the long press is consumed
        }
    }

    fun updateList(filteredList: List<PyFile>) {
        files.clear()
        files.addAll(filteredList)
        notifyDataSetChanged()
    }
}