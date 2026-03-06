package com.example.codevisualizerapp.ui.files

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.example.codevisualizerapp.ui.home.FileAdapter
import com.example.codevisualizerapp.ui.home.PyFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FilesFragment : Fragment() {

    private lateinit var filesRecyclerView: RecyclerView
    private lateinit var fileAdapter: FileAdapter
    private var fullFileList = mutableListOf<PyFile>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filesRecyclerView = view.findViewById(R.id.recycler_view_all_files)

        // 1. LOAD REAL FILES
        loadFilesFromStorage()

        // 2. Setup Adapter
        fileAdapter = FileAdapter(fullFileList,
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

        // 3. Toolbar & Navigation
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar_files)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // --- FIX: Pass Empty Code for New File ---
        toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_create_new_file) {
                val bundle = Bundle()
                // We pass an empty string (or a comment) to override the default Bubble Sort
                bundle.putString("CODE_CONTENT", "")
                findNavController().navigate(R.id.runCodeFragment, bundle)
                true
            } else {
                false
            }
        }

        // 4. Search
        val searchEditText = view.findViewById<TextInputEditText>(R.id.search_bar_edit_text)
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().lowercase().trim()
                val filteredList = if (query.isEmpty()) fullFileList else fullFileList.filter { it.fileName.lowercase().contains(query) }
                fileAdapter.updateList(filteredList)
            }
        })
    }

    // --- HELPER FUNCTIONS ---

    private fun loadFilesFromStorage() {
        fullFileList.clear()
        val directory = requireContext().filesDir
        val files = directory.listFiles()

        if (files != null) {
            for (file in files) {
                if (file.name.endsWith(".py")) {
                    val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(file.lastModified()))
                    fullFileList.add(PyFile(file.name, date))
                }
            }
        }
    }

    private fun readFileContent(fileName: String): String {
        return try {
            val file = File(requireContext().filesDir, fileName)
            file.readText()
        } catch (e: Exception) {
            "# Error reading file"
        }
    }

    private fun deleteFile(pyFile: PyFile) {
        val file = File(requireContext().filesDir, pyFile.fileName)
        if (file.exists()) {
            file.delete()
            fullFileList.remove(pyFile)
            fileAdapter.updateList(fullFileList)
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
                    loadFilesFromStorage() // Reload list
                    fileAdapter.updateList(fullFileList)
                    Toast.makeText(requireContext(), "Renamed to $validName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }
}