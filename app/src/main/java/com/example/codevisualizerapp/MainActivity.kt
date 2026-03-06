package com.example.codevisualizerapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Setup Navigation Controller
        // We find the NavHostFragment that holds all your app's screens
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // 2. Setup the "Run" Button (FAB)
        val fabRun = findViewById<FloatingActionButton>(R.id.fab_run)
        fabRun.setOnClickListener {
            navController.navigate(R.id.runCodeFragment)
        }

        // 3. CHECK IF APP WAS OPENED FROM ANOTHER APP (WhatsApp, Files, etc.)
        handleIncomingIntent(intent, navController)
    }

    // Function to read the file content sent by another app
    private fun handleIncomingIntent(intent: Intent?, navController: NavController) {
        // ACTION_VIEW or ACTION_EDIT means another app wants us to view/edit a file
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            val uri: Uri? = intent.data
            if (uri != null) {
                try {
                    // Read content from the URI (File path)
                    val contentResolver = applicationContext.contentResolver
                    val inputStream = contentResolver.openInputStream(uri)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val codeBuilder = StringBuilder()
                    var line: String?

                    // Read the file line by line
                    while (reader.readLine().also { line = it } != null) {
                        codeBuilder.append(line).append("\n")
                    }
                    reader.close()
                    inputStream?.close()

                    // Get the final code string
                    val codeContent = codeBuilder.toString()

                    // Prepare to send this code to the Run Page
                    val bundle = Bundle()
                    bundle.putString("CODE_CONTENT", codeContent)

                    // Navigate to RunCodeFragment with the code
                    // We use a small delay to ensure the app is fully loaded first
                    fabRun.post {
                        navController.navigate(R.id.runCodeFragment, bundle)
                    }

                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Helper property to access the FAB easily inside the post block
    private val fabRun: FloatingActionButton
        get() = findViewById(R.id.fab_run)
}