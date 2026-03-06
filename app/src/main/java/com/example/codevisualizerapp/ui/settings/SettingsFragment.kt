package com.example.codevisualizerapp.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.codevisualizerapp.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Set up the Toolbar (Back Button)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar_settings)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        // 2. Initialize Buttons
        val btnLight = view.findViewById<MaterialButton>(R.id.button_theme_light)
        val btnDark = view.findViewById<MaterialButton>(R.id.button_theme_dark)
        val btnSystem = view.findViewById<MaterialButton>(R.id.button_theme_system)

        // 3. Set Click Listeners for Theme Switching
        btnLight.setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            Toast.makeText(context, "Theme set to Light", Toast.LENGTH_SHORT).show()
        }

        btnDark.setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            Toast.makeText(context, "Theme set to Dark", Toast.LENGTH_SHORT).show()
        }

        btnSystem.setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            Toast.makeText(context, "Theme set to System Default", Toast.LENGTH_SHORT).show()
        }
    }
}