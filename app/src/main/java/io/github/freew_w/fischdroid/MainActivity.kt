package io.github.freew_w.fischdroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import io.github.freew_w.fischdroid.config.Config
import io.github.freew_w.fischdroid.services.ScreenCaptureService
import io.github.freew_w.fischdroid.setup.PixelPoint
import io.github.freew_w.fischdroid.setup.RegionSelection
import io.github.freew_w.fischdroid.setup.SetupActivity
import io.github.freew_w.fischdroid.setup.SetupField
import io.github.freew_w.fischdroid.setup.SetupWizardActivity
import io.github.freew_w.fischdroid.setup.toHexColor

class MainActivity : AppCompatActivity() {
    private val boundFields = mutableListOf<Pair<EditText, () -> Any?>>()

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

    private val wizardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        updateConfigUi()
    }

    private val setupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val values = SetupActivity.results(result.data) ?: return@registerForActivityResult

        values.getIntArray(SetupActivity.FIELD_ID_REGION)?.takeIf { it.size == 4 }?.let { region ->
            Config.trackX = region[0]
            Config.trackY = region[1]
            Config.trackW = region[2]
            Config.trackH = region[3]
        }
        if (values.containsKey(SetupActivity.FIELD_ID_FISH_COLOR)) {
            Config.fishColor = values.getInt(SetupActivity.FIELD_ID_FISH_COLOR)
        }
        values.getIntArray(SetupActivity.FIELD_ID_CAST_POINT)?.takeIf { it.size == 2 }?.let { point ->
            Config.castHoldX = point[0]
            Config.castHoldY = point[1]
        }

        updateConfigUi()
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

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_github -> {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/freew-w/fisch-droid"),
                        )
                    )
                    true
                }

                R.id.action_setup_wizard -> {
                    wizardLauncher.launch(Intent(this, SetupWizardActivity::class.java))
                    true
                }

                else -> false
            }
        }

        findViewById<View>(R.id.btn_enable_accessibility_service).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.enable_accessibility_hint, Toast.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btn_start_capture).setOnClickListener {
            requestNotificationsThenCapture()
        }

        findViewById<View>(R.id.btn_stop_capture).setOnClickListener {
            startService(
                Intent(this, ScreenCaptureService::class.java).putExtra(
                    "ACTION", "STOP"
                )
            )
        }

        findViewById<View>(R.id.btn_pick_track_region).setOnClickListener {
            openRegionPicker()
        }

        findViewById<View>(R.id.btn_pick_fish_color).setOnClickListener {
            openColorPicker()
        }

        findViewById<View>(R.id.btn_pick_cast_point).setOnClickListener {
            openCastPicker()
        }

        bindConfigField(
            findViewById(R.id.et_rod_control),
            get = { Config.rodControl },
            parse = String::toFloatOrNull,
            check = { it >= -0.3f },
            set = { Config.rodControl = it },
        )
        bindConfigField(
            findViewById(R.id.et_track_x),
            get = { Config.trackX },
            parse = String::toIntOrNull,
            check = { it >= 0 },
            set = { Config.trackX = it },
        )
        bindConfigField(
            findViewById(R.id.et_track_y),
            get = { Config.trackY },
            parse = String::toIntOrNull,
            check = { it >= 0 },
            set = { Config.trackY = it },
        )
        bindConfigField(
            findViewById(R.id.et_track_w),
            get = { Config.trackW },
            parse = String::toIntOrNull,
            check = { it > 0 },
            set = { Config.trackW = it },
        )
        bindConfigField(
            findViewById(R.id.et_track_h),
            get = { Config.trackH },
            parse = String::toIntOrNull,
            check = { it > 0 },
            set = { Config.trackH = it },
        )
        bindConfigField(
            findViewById(R.id.et_position_gain),
            get = { Config.positionGain },
            parse = String::toFloatOrNull,
            check = { it >= 0 },
            set = { Config.positionGain = it },
        )
        bindConfigField(
            findViewById(R.id.et_velocity_gain),
            get = { Config.velocityGain },
            parse = String::toFloatOrNull,
            check = { it >= 0 },
            set = { Config.velocityGain = it },
        )
        bindConfigField(
            findViewById(R.id.et_switch_band),
            get = { Config.switchBand },
            parse = String::toFloatOrNull,
            check = { it >= 0 },
            set = { Config.switchBand = it },
        )
        bindConfigField(
            findViewById(R.id.et_cast_hold_x),
            get = { Config.castHoldX },
            parse = String::toIntOrNull,
            check = { it >= 0 },
            set = { Config.castHoldX = it },
        )
        bindConfigField(
            findViewById(R.id.et_cast_hold_y),
            get = { Config.castHoldY },
            parse = String::toIntOrNull,
            check = { it >= 0 },
            set = { Config.castHoldY = it },
        )

        updateConfigUi()

        if (savedInstanceState == null && !Config.setupComplete) {
            wizardLauncher.launch(Intent(this, SetupWizardActivity::class.java))
        }
    }

    private fun openRegionPicker() {
        val initial = RegionSelection(
            x = Config.trackX,
            y = Config.trackY,
            width = Config.trackW,
            height = Config.trackH,
        )
        setupLauncher.launch(
            SetupActivity.intent(
                this,
                SetupField.region(
                    SetupActivity.FIELD_ID_REGION,
                    getString(R.string.setup_field_region),
                    initial,
                ),
            )
        )
    }

    private fun openColorPicker() {
        setupLauncher.launch(
            SetupActivity.intent(
                this,
                SetupField.color(
                    SetupActivity.FIELD_ID_FISH_COLOR,
                    getString(R.string.setup_field_color),
                    Config.fishColor,
                ),
            )
        )
    }

    private fun openCastPicker() {
        setupLauncher.launch(
            SetupActivity.intent(
                this,
                SetupField.point(
                    SetupActivity.FIELD_ID_CAST_POINT,
                    getString(R.string.setup_field_cast),
                    PixelPoint(Config.castHoldX, Config.castHoldY),
                ),
            )
        )
    }

    private fun updateConfigUi() {
        // The fish color card
        val color = Config.fishColor
        findViewById<View>(R.id.fish_color_swatch).backgroundTintList =
            ColorStateList.valueOf(color)
        findViewById<TextView>(R.id.tv_fish_color_hex).text = color.toHexColor()

        // The minigame fields
        boundFields.forEach { (view, get) ->
            view.setText(get().toString())
        }
    }

    private fun requestNotificationsThenCapture() {
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
        boundFields += view to get

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
