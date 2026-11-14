package io.github.freew_w.fischdroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.freew_w.fischdroid.minigame.Detector
import io.github.freew_w.fischdroid.services.ScreenCaptureService

class MainActivity : AppCompatActivity() {
    private val startMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            startForegroundService(intent)
        }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        launchScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.enable_accessibility_hint, Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                launchScreenCapture()
            }
        }

        findViewById<Button>(R.id.btnStopCapture).setOnClickListener {
            startService(
                Intent(this, ScreenCaptureService::class.java).putExtra(
                    "ACTION", "STOP"
                )
            )
        }

        val rodControlEdittext = findViewById<EditText>(R.id.rodControl)
        rodControlEdittext.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                rodControlEdittext.setText("0.")
            } else {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(rodControlEdittext.windowToken, 0)

                val text = rodControlEdittext.text.toString()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Please enter a value", Toast.LENGTH_SHORT).show()
                    return@OnFocusChangeListener
                }

                val control = text.toFloatOrNull()
                if (control == null) {
                    Toast.makeText(this, "Please enter a valid value", Toast.LENGTH_SHORT).show()
                    rodControlEdittext.setText("0.0")
                    return@OnFocusChangeListener
                }

                Detector.updateControl(control)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchScreenCapture()
        }
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startMediaProjection.launch(manager.createScreenCaptureIntent())
    }
}