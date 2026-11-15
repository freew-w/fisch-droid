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
import io.github.freew_w.fischdroid.config.Config
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

        bindConfigField(
            findViewById(R.id.rodControl),
            get = { Config.rodControl },
            parse = String::toFloatOrNull,
            check = { it >= -0.3f },
            set = { Config.rodControl = it },
        )
        bindConfigField(
            findViewById(R.id.trackX),
            get = { Config.trackX },
            parse = String::toIntOrNull,
            check = { it > 0 },
            set = { Config.trackX = it },
        )
        bindConfigField(
            findViewById(R.id.trackY),
            get = { Config.trackY },
            parse = String::toIntOrNull,
            check = { it > 0 },
            set = { Config.trackY = it },
        )
        bindConfigField(
            findViewById(R.id.trackW),
            get = { Config.trackW },
            parse = String::toIntOrNull,
            check = { it > 0 },
            set = { Config.trackW = it },
        )
        bindConfigField(
            findViewById(R.id.trackH),
            get = { Config.trackH },
            parse = String::toIntOrNull,
            check = { it > 0 },
            set = { Config.trackH = it },
        )

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

    /**
     * Shows [get]'s current value and writes a parsed value back when the field loses focus.
     * Invalid or empty input leaves the stored value untouched and restores the field text.
     */
    private fun <T> bindConfigField(
        view: EditText,
        get: () -> T,
        parse: (String) -> T?,
        check: (T) -> Boolean,
        set: (T) -> Unit,
    ) {
        view.setText(get().toString())
        view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.selectAll()
                return@OnFocusChangeListener
            }

            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)

            val value = parse(view.text.toString().trim())
            if (value == null || !check(value)) {
                Toast.makeText(this, R.string.invalid_config_value, Toast.LENGTH_SHORT).show()
            } else {
                set(value)
            }
            view.setText(get().toString())
        }
    }
}