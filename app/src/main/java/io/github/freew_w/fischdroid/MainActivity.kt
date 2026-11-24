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
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.github.freew_w.fischdroid.config.Config
import io.github.freew_w.fischdroid.services.ScreenCaptureService
import io.github.freew_w.fischdroid.services.TapService
import io.github.freew_w.fischdroid.setup.PixelPoint
import io.github.freew_w.fischdroid.setup.RegionSelection
import io.github.freew_w.fischdroid.setup.SetupActivity
import io.github.freew_w.fischdroid.setup.SetupField
import io.github.freew_w.fischdroid.setup.SetupWizardActivity
import io.github.freew_w.fischdroid.setup.toHexColor

class MainActivity : AppCompatActivity() {
    /** Setup stages shown by the hero card, in the order the user has to satisfy them. */
    private enum class HeroStage { ACCESSIBILITY, CAPTURE, READY }

    private val boundFields = mutableListOf<Pair<EditText, () -> Any?>>()

    /**
     * The themed (stateful) tints of the idle capture button, captured before the first render so
     * the disabled state keeps Material's grayed-out look instead of a flat overridden color.
     */
    private var idleBackgroundTint: ColorStateList? = null
    private var idleContentColor: ColorStateList? = null
    private var idleIconTint: ColorStateList? = null

    /** Re-renders the hero whenever the capture service starts or stops. */
    private val captureStateListener: (Boolean) -> Unit = { renderStatus() }

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

        findViewById<View>(R.id.btn_toggle_capture).setOnClickListener {
            if (ScreenCaptureService.isActive) {
                startService(
                    Intent(this, ScreenCaptureService::class.java).putExtra(
                        "ACTION", "STOP"
                    )
                )
            } else {
                requestNotificationsThenCapture()
            }
        }
        findViewById<MaterialButton>(R.id.btn_toggle_capture).run {
            idleBackgroundTint = backgroundTintList
            idleContentColor = textColors
            idleIconTint = iconTint
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
        renderStatus()

        if (savedInstanceState == null && !Config.setupComplete) {
            wizardLauncher.launch(Intent(this, SetupWizardActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        ScreenCaptureService.addStateListener(captureStateListener)
    }

    override fun onStop() {
        ScreenCaptureService.removeStateListener(captureStateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Picks up an accessibility toggle the user just made in system settings.
        renderStatus()
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

    /**
     * Paints the hero card with the current setup stage: red until the accessibility service is
     * enabled, sand until capture is running, sea-blue once the macro can actually fish.
     */
    private fun renderStatus() {
        val accessibilityEnabled = TapService.isEnabled(this)
        val capturing = ScreenCaptureService.isActive
        val stage = when {
            !accessibilityEnabled -> HeroStage.ACCESSIBILITY
            !capturing -> HeroStage.CAPTURE
            else -> HeroStage.READY
        }

        val titleRes: Int
        val bodyRes: Int
        val cardColor: Int
        val badgeColor: Int
        val badgeIconColor: Int
        val textColor: Int
        val iconRes: Int
        when (stage) {
            HeroStage.ACCESSIBILITY -> {
                titleRes = R.string.home_hero_accessibility_title
                bodyRes = if (capturing) {
                    R.string.home_hero_accessibility_body_capturing
                } else {
                    R.string.home_hero_accessibility_body
                }
                cardColor = R.color.md_error_container
                badgeColor = R.color.md_error
                badgeIconColor = R.color.md_on_error
                textColor = R.color.md_on_error_container
                iconRes = R.drawable.ic_android
            }

            HeroStage.CAPTURE -> {
                titleRes = R.string.home_hero_capture_title
                bodyRes = R.string.home_hero_capture_body
                cardColor = R.color.md_tertiary_container
                badgeColor = R.color.md_tertiary
                badgeIconColor = R.color.md_on_tertiary
                textColor = R.color.md_on_tertiary_container
                iconRes = R.drawable.ic_play
            }

            HeroStage.READY -> {
                titleRes = R.string.home_hero_title
                bodyRes = R.string.home_hero_body
                cardColor = R.color.md_primary_container
                badgeColor = R.color.md_primary
                badgeIconColor = R.color.md_on_primary
                textColor = R.color.md_on_primary_container
                iconRes = R.drawable.ic_fish
            }
        }

        findViewById<MaterialCardView>(R.id.card_hero).setCardBackgroundColor(
            ContextCompat.getColor(this, cardColor)
        )
        val badge = findViewById<AppCompatImageView>(R.id.hero_badge)
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, badgeColor))
        badge.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, badgeIconColor))
        badge.setImageResource(iconRes)

        val resolvedTextColor = ContextCompat.getColor(this, textColor)
        findViewById<TextView>(R.id.tv_hero_title).apply {
            setText(titleRes)
            setTextColor(resolvedTextColor)
        }
        findViewById<TextView>(R.id.tv_hero_body).apply {
            setText(bodyRes)
            setTextColor(resolvedTextColor)
        }

        renderCaptureButton(capturing, accessibilityEnabled)
    }

    /**
     * One button serves both actions because only one of them is ever available: it starts capture
     * once accessibility is on and turns into a tonal "Stop capture" while a session runs. The idle
     * state restores the style's own stateful tints, so a gated button still grays out.
     */
    private fun renderCaptureButton(capturing: Boolean, accessibilityEnabled: Boolean) {
        val toggle = findViewById<MaterialButton>(R.id.btn_toggle_capture)
        toggle.isEnabled = capturing || accessibilityEnabled
        if (capturing) {
            val onContainer = ContextCompat.getColor(this, R.color.md_on_secondary_container)
            toggle.setText(R.string.stop_screen_capture)
            toggle.setIconResource(R.drawable.ic_stop)
            toggle.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.md_secondary_container)
            )
            toggle.setTextColor(onContainer)
            toggle.iconTint = ColorStateList.valueOf(onContainer)
        } else {
            toggle.setText(R.string.start_screen_capture)
            toggle.setIconResource(R.drawable.ic_play)
            toggle.backgroundTintList = idleBackgroundTint
            toggle.setTextColor(idleContentColor)
            toggle.iconTint = idleIconTint
        }
    }

    private fun requestNotificationsThenCapture() {
        if (!TapService.isEnabled(this)) {
            showAccessibilityRequired()
            return
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
        if (!TapService.isEnabled(this)) {
            showAccessibilityRequired()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        startMediaProjection.launch(manager.createScreenCaptureIntent())
    }

    private fun showAccessibilityRequired() {
        Toast.makeText(this, R.string.capture_requires_accessibility, Toast.LENGTH_LONG).show()
        renderStatus()
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
