package io.github.freew_w.fischdroid.setup

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import io.github.freew_w.fischdroid.R
import io.github.freew_w.fischdroid.config.Config

/**
 * First-run, three-step setup. Writes the values to [Config] and marks setup complete so the
 * wizard is skipped on later launches. It can be reopened from the main screen at any time.
 */
class SetupWizardActivity : AppCompatActivity() {

    companion object {
        private const val STEP_WELCOME = 0
        private const val STEP_REGION = 1
        private const val STEP_COLOR = 2
        private const val STEP_COUNT = 3

        private const val KEY_STEP = "step"
        private const val KEY_COLOR = "color"
    }

    private lateinit var progress: LinearProgressIndicator
    private lateinit var stepLabel: TextView
    private lateinit var stepWelcome: View
    private lateinit var stepRegion: View
    private lateinit var stepColor: View
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var regionStatus: TextView
    private lateinit var colorStatus: TextView
    private lateinit var colorSwatch: View
    private lateinit var colorHex: TextView

    private lateinit var tilX: TextInputLayout
    private lateinit var tilY: TextInputLayout
    private lateinit var tilW: TextInputLayout
    private lateinit var tilH: TextInputLayout
    private lateinit var etX: EditText
    private lateinit var etY: EditText
    private lateinit var etW: EditText
    private lateinit var etH: EditText

    private var step = STEP_WELCOME
    private var selectedColor = 0

    private val pickRegion = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val values = SetupActivity.results(result.data) ?: return@registerForActivityResult
        val region =
            values.getIntArray(SetupActivity.FIELD_ID_REGION) ?: return@registerForActivityResult
        if (region.size != 4) return@registerForActivityResult

        etX.setText(formatPixel(region[0]))
        etY.setText(formatPixel(region[1]))
        etW.setText(formatPixel(region[2]))
        etH.setText(formatPixel(region[3]))
        clearRegionErrors()
        regionStatus.text = getString(
            R.string.wizard_region_picked, region[0], region[1], region[2], region[3]
        )
    }

    private val pickColor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val values = SetupActivity.results(result.data) ?: return@registerForActivityResult
        if (!values.containsKey(SetupActivity.FIELD_ID_FISH_COLOR)) return@registerForActivityResult
        selectedColor = values.getInt(SetupActivity.FIELD_ID_FISH_COLOR)
        updateColorUi(picked = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_setup_wizard)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.wizard_root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        progress = findViewById(R.id.wizard_progress)
        stepLabel = findViewById(R.id.tv_step_label)
        stepWelcome = findViewById(R.id.step_welcome)
        stepRegion = findViewById(R.id.step_region)
        stepColor = findViewById(R.id.step_color)
        backButton = findViewById(R.id.btn_wizard_back)
        nextButton = findViewById(R.id.btn_wizard_next)
        regionStatus = findViewById(R.id.tv_wizard_region_status)
        colorStatus = findViewById(R.id.tv_wizard_color_status)
        colorSwatch = findViewById(R.id.wizard_color_swatch)
        colorHex = findViewById(R.id.tv_wizard_color_hex)

        tilX = findViewById(R.id.til_wizard_x)
        tilY = findViewById(R.id.til_wizard_y)
        tilW = findViewById(R.id.til_wizard_w)
        tilH = findViewById(R.id.til_wizard_h)
        etX = findViewById(R.id.et_wizard_x)
        etY = findViewById(R.id.et_wizard_y)
        etW = findViewById(R.id.et_wizard_w)
        etH = findViewById(R.id.et_wizard_h)

        progress.max = STEP_COUNT
        selectedColor = savedInstanceState?.getInt(KEY_COLOR, Config.fishColor) ?: Config.fishColor

        etX.setText(formatPixel(Config.trackX))
        etY.setText(formatPixel(Config.trackY))
        etW.setText(formatPixel(Config.trackW))
        etH.setText(formatPixel(Config.trackH))
        regionStatus.text = getString(
            R.string.wizard_region_status,
            Config.trackX,
            Config.trackY,
            Config.trackW,
            Config.trackH,
        )
        updateColorUi(picked = false)

        val toolbar = findViewById<MaterialToolbar>(R.id.wizard_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_skip_setup) {
                markCompleteAndClose()
                true
            } else {
                false
            }
        }

        backButton.setOnClickListener {
            if (step > STEP_WELCOME) showStep(step - 1)
        }
        nextButton.setOnClickListener {
            when (step) {
                STEP_WELCOME -> showStep(STEP_REGION)
                STEP_REGION -> if (validateRegion() != null) showStep(STEP_COLOR)
                else -> finishWizard()
            }
        }

        findViewById<View>(R.id.btn_wizard_pick_region).setOnClickListener {
            pickRegion.launch(
                SetupActivity.intent(
                    this,
                    listOf(
                        SetupField.region(
                            SetupActivity.FIELD_ID_REGION,
                            getString(R.string.setup_field_region),
                            readRegionOrNull(),
                        )
                    ),
                )
            )
        }
        findViewById<View>(R.id.btn_wizard_pick_color).setOnClickListener {
            pickColor.launch(
                SetupActivity.intent(
                    this,
                    listOf(
                        SetupField.color(
                            SetupActivity.FIELD_ID_FISH_COLOR,
                            getString(R.string.setup_field_color),
                            selectedColor,
                        )
                    ),
                )
            )
        }

        showStep(savedInstanceState?.getInt(KEY_STEP, STEP_WELCOME) ?: STEP_WELCOME)
    }

    private fun showStep(newStep: Int) {
        step = newStep.coerceIn(STEP_WELCOME, STEP_COLOR)
        stepWelcome.isVisible = step == STEP_WELCOME
        stepRegion.isVisible = step == STEP_REGION
        stepColor.isVisible = step == STEP_COLOR
        progress.setProgressCompat(step + 1, true)
        stepLabel.text = getString(R.string.wizard_step_label, step + 1, STEP_COUNT)
        backButton.isVisible = step > STEP_WELCOME

        if (step == STEP_COLOR) {
            nextButton.setText(R.string.action_finish)
            nextButton.setIconResource(R.drawable.ic_check)
        } else {
            nextButton.setText(R.string.action_next)
            nextButton.setIconResource(R.drawable.ic_arrow_right)
        }
    }

    private fun updateColorUi(picked: Boolean) {
        colorHex.text = selectedColor.toHexColor()
        colorSwatch.backgroundTintList = ColorStateList.valueOf(selectedColor)
        colorStatus.text = if (picked) {
            getString(R.string.wizard_color_picked, selectedColor.toHexColor())
        } else {
            getString(R.string.wizard_color_status, selectedColor.toHexColor())
        }
    }

    private fun readRegionOrNull(): RegionSelection? {
        val x = etX.text?.toString()?.trim()?.toIntOrNull()
        val y = etY.text?.toString()?.trim()?.toIntOrNull()
        val w = etW.text?.toString()?.trim()?.toIntOrNull()
        val h = etH.text?.toString()?.trim()?.toIntOrNull()
        if (x == null || y == null || w == null || h == null) return null
        if (x < 0 || y < 0 || w <= 0 || h <= 0) return null
        return RegionSelection(x, y, w, h)
    }

    private fun validateRegion(): RegionSelection? {
        val message = getString(R.string.wizard_region_invalid)
        val x = etX.text?.toString()?.trim()?.toIntOrNull()
        val y = etY.text?.toString()?.trim()?.toIntOrNull()
        val w = etW.text?.toString()?.trim()?.toIntOrNull()
        val h = etH.text?.toString()?.trim()?.toIntOrNull()

        tilX.error = if (x == null || x < 0) message else null
        tilY.error = if (y == null || y < 0) message else null
        tilW.error = if (w == null || w <= 0) message else null
        tilH.error = if (h == null || h <= 0) message else null

        if (x == null || y == null || w == null || h == null) return null
        if (x < 0 || y < 0 || w <= 0 || h <= 0) return null
        return RegionSelection(x, y, w, h)
    }

    private fun clearRegionErrors() {
        tilX.error = null
        tilY.error = null
        tilW.error = null
        tilH.error = null
    }

    private fun formatPixel(value: Int): String = String.format(java.util.Locale.US, "%d", value)

    private fun finishWizard() {
        val region = validateRegion() ?: run {
            showStep(STEP_REGION)
            return
        }

        Config.trackX = region.x
        Config.trackY = region.y
        Config.trackW = region.width
        Config.trackH = region.height
        Config.fishColor = selectedColor
        Config.setupComplete = true

        setResult(RESULT_OK)
        finish()
    }

    private fun markCompleteAndClose() {
        Config.setupComplete = true
        setResult(RESULT_OK)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
        outState.putInt(KEY_COLOR, selectedColor)
    }
}
