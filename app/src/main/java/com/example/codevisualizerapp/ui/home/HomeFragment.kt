package com.example.codevisualizerapp.ui.home

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.codevisualizerapp.R
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private lateinit var filesRecyclerView: RecyclerView
    private lateinit var fileAdapter: FileAdapter
    private var homeFileList = mutableListOf<PyFile>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Load Files
        loadRecentFiles()

        // 2. Setup Adapter
        filesRecyclerView = view.findViewById(R.id.recycler_view_files)
        fileAdapter = FileAdapter(homeFileList,
            onFileClicked = { selectedFile ->
                val code = readFileContent(selectedFile.fileName)
                val bundle = Bundle()
                bundle.putString("CODE_CONTENT", code)
                findNavController().navigate(R.id.runCodeFragment, bundle)
            },
            onFileAction = { file, action ->
                when(action) {
                    "DELETE" -> deleteFile(file)
                    "RENAME" -> showRenameDialog(file)
                }
            }
        )
        filesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        filesRecyclerView.adapter = fileAdapter

        // 3. Navigation
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar_home)
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_run_code -> { findNavController().navigate(R.id.runCodeFragment); true }
                R.id.action_files -> { findNavController().navigate(R.id.filesFragment); true }
                R.id.action_settings -> { findNavController().navigate(R.id.settingsFragment); true }
                else -> false
            }
        }
        view.findViewById<View>(R.id.btn_see_all).setOnClickListener {
            findNavController().navigate(R.id.filesFragment)
        }
    }

    // --- HELPER FUNCTIONS ---

    private fun loadRecentFiles() {
        homeFileList.clear()
        val directory = requireContext().filesDir
        val files = directory.listFiles()

        if (files != null) {
            // Sort by date modified (newest first)
            val sortedFiles = files.sortedByDescending { it.lastModified() }

            // Take top 3
            for (file in sortedFiles.take(3)) {
                if (file.name.endsWith(".py")) {
                    val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(file.lastModified()))
                    homeFileList.add(PyFile(file.name, date))
                }
            }
        }
    }

    private fun readFileContent(fileName: String): String {
        return try {
            val file = File(requireContext().filesDir, fileName)
            file.readText()
        } catch (e: Exception) { "" }
    }

    private fun deleteFile(pyFile: PyFile) {
        val file = File(requireContext().filesDir, pyFile.fileName)
        if (file.exists()) {
            file.delete()
            homeFileList.remove(pyFile)
            fileAdapter.updateList(homeFileList)
            Toast.makeText(requireContext(), "Deleted ${pyFile.fileName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(pyFile: PyFile) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Rename File")
        val input = EditText(requireContext())
        input.setText(pyFile.fileName)
        builder.setView(input)

        builder.setPositiveButton("OK") { _, _ ->
            val newName = input.text.toString()
            if (newName.isNotEmpty()) {
                val validName = if (newName.endsWith(".py")) newName else "$newName.py"
                val oldFile = File(requireContext().filesDir, pyFile.fileName)
                val newFile = File(requireContext().filesDir, validName)
                if (oldFile.renameTo(newFile)) {
                    loadRecentFiles()
                    fileAdapter.updateList(homeFileList)
                }
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }
}