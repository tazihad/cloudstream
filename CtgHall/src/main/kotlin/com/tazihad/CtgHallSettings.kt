package com.tazihad

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CloudStreamApp

class CtgHallSettings : BottomSheetDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val tmdbToggle = CheckBox(context).apply {
            text = "Enable TMDB integration"
            isChecked = CtgHallSettingsManager.isTmdbEnabled()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        val apiKeyLabel = TextView(context).apply {
            text = "TMDB API Key:"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        val input = EditText(context).apply {
            hint = "Enter TMDB API Key"
            setText(CtgHallSettingsManager.getApiKey() ?: "")

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        input.isEnabled = tmdbToggle.isChecked
        apiKeyLabel.isEnabled = tmdbToggle.isChecked

        tmdbToggle.setOnCheckedChangeListener { _, isChecked ->
            input.isEnabled = isChecked
            apiKeyLabel.isEnabled = isChecked
        }

        layout.addView(tmdbToggle)
        layout.addView(apiKeyLabel)
        layout.addView(input)

        return AlertDialog.Builder(context)
            .setTitle("CTG Hall Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val tmdbEnabled = tmdbToggle.isChecked
                val apiKey = input.text.toString().trim()

                if (!CtgHallSettingsManager.setTmdbEnabled(tmdbEnabled)) {
                    showToast("Failed to save TMDB settings. Please try again.")
                    return@setPositiveButton
                }

                if (tmdbEnabled) {
                    when {
                        apiKey.isEmpty() -> {
                            showToast("Please enter a valid API key")
                            return@setPositiveButton
                        }
                        apiKey.length != 32 -> {
                            showToast("API key should be 32 characters long")
                            return@setPositiveButton
                        }
                        !apiKey.matches(Regex("^[a-zA-Z0-9]+$")) -> {
                            showToast("API key should only contain letters and numbers")
                            return@setPositiveButton
                        }
                        else -> {
                            if (CtgHallSettingsManager.setApiKey(apiKey)) {
                                showToast("Settings saved successfully")
                            } else {
                                showToast("Failed to save API key. Please try again.")
                            }
                        }
                    }
                } else {
                    if (apiKey.isNotEmpty()) {
                        CtgHallSettingsManager.setApiKey(apiKey)
                    }
                    showToast("TMDB integration disabled. Settings saved.")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    private fun showToast(message: String) {
        try {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                Toast.makeText(CloudStreamApp.context, message, Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                // Ignore if both toast attempts fail
            }
        }
    }

    companion object {
        fun newInstance(): CtgHallSettings {
            return CtgHallSettings()
        }
    }
}