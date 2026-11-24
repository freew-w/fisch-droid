package io.github.freew_w.fischdroid.setup

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import io.github.freew_w.fischdroid.R
import io.github.freew_w.fischdroid.config.Config

/**
 * Full-screen pixel picker used by the first-run wizard and from the main screen.
 *
 * A tap moves the cursor, the four arrow buttons nudge it by exactly one image pixel, the
 * zoom buttons or a pinch scale the image, and the confirm button commits the pixel under the
 * cursor to the active field. Regions need two confirms (top-left, then bottom-right) and are
 * returned as x, y, width, height.
 */
class SetupActivity : AppCompatActivity(), PixelPickerView.OnCursorChangedListener {

    companion object {
        const val EXTRA_FIELD = "io.github.freew_w.fischdroid.setup.FIELD"
        const val EXTRA_IMAGE_URI = "io.github.freew_w.fischdroid.setup.IMAGE_URI"
        const val EXTRA_RESULTS = "io.github.freew_w.fischdroid.setup.RESULTS"

        const val FIELD_ID_REGION = "track_region"
        const val FIELD_ID_FISH_COLOR = "fish_line_color"
        const val FIELD_ID_CAST_POINT = "cast_point"

        private const val KEY_REGION_FIRST = "region_first"
        private const val KEY_RESULTS = "results"
        private const val KEY_CURSOR_X = "cursor_x"
        private const val KEY_CURSOR_Y = "cursor_y"
        private const val KEY_IMAGE_URI = "image_uri"

        fun intent(context: Context, field: SetupField, imageUri: Uri? = null): Intent {
            return Intent(context, SetupActivity::class.java)
                .putExtra(EXTRA_FIELD, field)
                .apply {
                    if (imageUri != null) putExtra(EXTRA_IMAGE_URI, imageUri.toString())
                }
        }

        /** Reads the values returned by [SetupActivity], keyed by [SetupField.id]. */
        fun results(intent: Intent?): Bundle? = intent?.getBundleExtra(EXTRA_RESULTS)
    }

    private lateinit var picker: PixelPickerView
    private lateinit var confirmButton: MaterialButton
    private lateinit var fieldLabel: TextView
    private lateinit var fieldValue: TextView
    private lateinit var fieldHint: TextView
    private lateinit var fieldProgress: LinearProgressIndicator
    private lateinit var cursorLabel: TextView
    private lateinit var cursorColorLabel: TextView
    private lateinit var cursorSwatch: View
    private lateinit var emptyState: View

    private var field: SetupField? = null
    private var totalSteps = 1
    private var result: Any? = null
    private var regionFirst: PixelPoint? = null

    private var imageLoaded = false
    private var imageUri: Uri? = null
    private var restoredCursor: PixelPoint? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_setup)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        field = readField(intent)
        val active = field
        if (active == null) {
            finish()
            return
        }
        totalSteps = if (active.type == SetupFieldType.REGION) 2 else 1

        picker = findViewById(R.id.pixel_picker)
        confirmButton = findViewById(R.id.btn_setup_confirm)
        fieldLabel = findViewById(R.id.tv_setup_field_label)
        fieldValue = findViewById(R.id.tv_setup_field_value)
        fieldHint = findViewById(R.id.tv_setup_field_hint)
        fieldProgress = findViewById(R.id.setup_field_progress)
        cursorLabel = findViewById(R.id.tv_setup_cursor)
        cursorColorLabel = findViewById(R.id.tv_setup_cursor_color)
        cursorSwatch = findViewById(R.id.setup_cursor_swatch)
        emptyState = findViewById(R.id.setup_empty_state)

        picker.onCursorChanged = this
        fieldProgress.max = totalSteps

        val toolbar = findViewById<MaterialToolbar>(R.id.setup_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_setup_load_image -> {
                    pickImage.launch(arrayOf("image/*"))
                    true
                }

                R.id.action_setup_fit -> {
                    picker.fitToScreen()
                    true
                }

                else -> false
            }
        }

        findViewById<View>(R.id.btn_setup_load_image).setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }

        findViewById<View>(R.id.btn_nudge_up).setOnClickListener { nudge(0, -1) }
        findViewById<View>(R.id.btn_nudge_down).setOnClickListener { nudge(0, 1) }
        findViewById<View>(R.id.btn_nudge_left).setOnClickListener { nudge(-1, 0) }
        findViewById<View>(R.id.btn_nudge_right).setOnClickListener { nudge(1, 0) }
        findViewById<View>(R.id.btn_setup_zoom_in).setOnClickListener { zoom(true) }
        findViewById<View>(R.id.btn_setup_zoom_out).setOnClickListener { zoom(false) }

        confirmButton.setOnClickListener {
            if (isDone()) saveAndFinish() else confirm()
        }

        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        }

        updateUi()
        showEmptyCursorReadout()

        val restoredUri = savedInstanceState?.getString(KEY_IMAGE_URI)
        val initialUri = restoredUri
            ?: intent.getStringExtra(EXTRA_IMAGE_URI)
            ?: Config.lastImageUri
        if (initialUri != null) loadImage(initialUri.toUri())
    }

    override fun onCursorChanged(x: Int, y: Int, color: Int) {
        updateCursorReadout(x, y, color)
    }

    private fun nudge(dx: Int, dy: Int) {
        if (!imageLoaded) {
            Toast.makeText(this, R.string.setup_no_image, Toast.LENGTH_SHORT).show()
            return
        }
        if (picker.nudge(dx, dy)) {
            picker.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun zoom(zoomIn: Boolean) {
        if (!imageLoaded) {
            Toast.makeText(this, R.string.setup_no_image, Toast.LENGTH_SHORT).show()
            return
        }
        val changed = if (zoomIn) picker.zoomIn() else picker.zoomOut()
        if (changed) {
            picker.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun confirm() {
        if (!imageLoaded) {
            Toast.makeText(this, R.string.setup_no_image, Toast.LENGTH_SHORT).show()
            return
        }

        val active = field ?: return
        val point = picker.cursor

        when (active.type) {
            SetupFieldType.COLOR -> {
                val color = picker.colorAtCursor()
                result = color
                showFeedback(getString(R.string.setup_color_saved, color.toHexColor()))
            }

            SetupFieldType.POINT -> {
                result = point.toIntArray()
                showFeedback(getString(R.string.setup_point_saved))
            }

            SetupFieldType.REGION -> {
                val first = regionFirst
                if (first == null) {
                    regionFirst = point
                    showFeedback(getString(R.string.setup_top_left_saved, point.x, point.y))
                    updateUi()
                    return
                }
                result = RegionSelection.between(first, point).toIntArray()
                regionFirst = null
                showFeedback(getString(R.string.setup_region_saved))
            }
        }

        focusActiveFieldCursor()
        updateUi()
    }

    private fun saveAndFinish() {
        if (field == null || !isDone()) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULTS, resultsBundle()))
        finish()
    }

    private fun updateUi() {
        val active = field ?: return
        if (isDone()) {
            fieldLabel.text = getString(R.string.setup_all_set)
            fieldValue.text = getString(R.string.setup_ready_to_save)
            fieldHint.text = getString(R.string.setup_hint_done)
            fieldProgress.setProgressCompat(totalSteps, true)
            confirmButton.setText(R.string.action_save)
            confirmButton.isEnabled = true
            return
        }

        val regionStep = active.type == SetupFieldType.REGION && regionFirst != null
        val stepText = when {
            active.type == SetupFieldType.REGION && regionStep ->
                getString(R.string.setup_region_bottom_right_step)

            active.type == SetupFieldType.REGION ->
                getString(R.string.setup_region_top_left_step)

            active.type == SetupFieldType.COLOR ->
                getString(R.string.setup_color_step)

            else -> getString(R.string.setup_point_step)
        }

        fieldLabel.text = getString(R.string.setup_field_label, active.label, stepText)
        fieldValue.text = when {
            regionStep -> regionFirst?.let {
                getString(R.string.setup_value_top_left, it.x, it.y)
            } ?: getString(R.string.setup_value_unset)

            else -> valueLabel(active)
        }
        fieldHint.text = when (active.type) {
            SetupFieldType.REGION -> if (regionStep) {
                getString(R.string.setup_hint_region_second)
            } else {
                getString(R.string.setup_hint_region_first)
            }

            SetupFieldType.COLOR -> getString(R.string.setup_hint_color)
            SetupFieldType.POINT -> getString(R.string.setup_hint_point)
        }

        fieldProgress.setProgressCompat(completedSteps(), true)

        confirmButton.setText(
            when (active.type) {
                SetupFieldType.REGION -> if (regionStep) {
                    R.string.setup_confirm_bottom_right
                } else {
                    R.string.setup_confirm_top_left
                }

                SetupFieldType.COLOR -> R.string.setup_confirm_sample
                SetupFieldType.POINT -> R.string.setup_confirm_point
            }
        )
        confirmButton.isEnabled = imageLoaded
    }

    private fun isDone(): Boolean = result != null

    private fun completedSteps(): Int {
        if (isDone()) return totalSteps
        return if (field?.type == SetupFieldType.REGION && regionFirst != null) 1 else 0
    }

    private fun valueLabel(field: SetupField): String {
        val saved = result
        if (saved != null) return formatValue(field.type, saved)

        return when (field.type) {
            SetupFieldType.REGION -> field.initialRegion?.let {
                formatValue(field.type, it.toIntArray())
            }

            SetupFieldType.POINT -> field.initialPoint?.let {
                formatValue(field.type, it.toIntArray())
            }

            SetupFieldType.COLOR -> field.initialColor?.toHexColor()
        } ?: getString(R.string.setup_value_unset)
    }

    private fun formatValue(type: SetupFieldType, value: Any): String {
        return when (type) {
            SetupFieldType.REGION -> (value as? IntArray)?.takeIf { it.size == 4 }?.let {
                getString(R.string.setup_value_region, it[0], it[1], it[2], it[3])
            } ?: getString(R.string.setup_value_unset)

            SetupFieldType.POINT -> (value as? IntArray)?.takeIf { it.size == 2 }?.let {
                getString(R.string.setup_value_point, it[0], it[1])
            } ?: getString(R.string.setup_value_unset)

            SetupFieldType.COLOR -> (value as? Int)?.toHexColor()
                ?: getString(R.string.setup_value_unset)
        }
    }

    private fun updateCursorReadout(x: Int, y: Int, color: Int) {
        cursorLabel.text = getString(R.string.setup_cursor, x, y)
        cursorColorLabel.text = getString(R.string.setup_cursor_color, color.toHexColor())
        cursorSwatch.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun showEmptyCursorReadout() {
        cursorLabel.setText(R.string.setup_cursor_empty)
        cursorColorLabel.text = ""
        cursorSwatch.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    }

    private fun focusActiveFieldCursor() {
        if (!imageLoaded || isDone()) return
        val active = field ?: return

        val point = when (active.type) {
            SetupFieldType.REGION -> if (regionFirst == null) {
                active.initialRegion?.let { PixelPoint(it.x, it.y) }
            } else {
                null
            }

            SetupFieldType.POINT -> active.initialPoint
            SetupFieldType.COLOR -> null
        }
        point?.let { picker.setCursor(it.x, it.y) }
    }

    private fun cursorForActiveField(): PixelPoint? {
        restoredCursor?.let {
            restoredCursor = null
            return it
        }
        if (isDone()) return null
        val active = field ?: return null
        return when (active.type) {
            SetupFieldType.REGION -> active.initialRegion?.let { PixelPoint(it.x, it.y) }
            SetupFieldType.POINT -> active.initialPoint
            SetupFieldType.COLOR -> null
        }
    }

    private fun loadImage(uri: Uri) {
        Thread {
            val bitmap = runCatching {
                contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bitmap == null) {
                    Toast.makeText(this, R.string.setup_load_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                imageUri = uri
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                Config.lastImageUri = uri.toString()
                imageLoaded = true
                emptyState.isVisible = false
                picker.setImage(bitmap, cursorForActiveField())
                updateUi()
            }
        }.start()
    }

    private fun showFeedback(text: String) {
        Snackbar.make(findViewById(R.id.setup_root), text, Snackbar.LENGTH_SHORT)
            .setAnchorView(R.id.setup_controls)
            .show()
    }

    private fun resultsBundle(): Bundle {
        val bundle = Bundle()
        val id = field?.id ?: return bundle
        when (val value = result) {
            is Int -> bundle.putInt(id, value)
            is IntArray -> bundle.putIntArray(id, value)
        }
        return bundle
    }

    private fun restoreState(state: Bundle) {
        regionFirst = state.getIntArray(KEY_REGION_FIRST)?.takeIf { it.size == 2 }?.let {
            PixelPoint(it[0], it[1])
        }
        state.getBundle(KEY_RESULTS)?.let { saved ->
            val active = field ?: return@let
            when (active.type) {
                SetupFieldType.COLOR -> {
                    if (saved.containsKey(active.id)) {
                        result = saved.getInt(active.id)
                    }
                }

                SetupFieldType.REGION, SetupFieldType.POINT -> {
                    saved.getIntArray(active.id)?.let { result = it }
                }
            }
        }
        if (state.containsKey(KEY_CURSOR_X) && state.containsKey(KEY_CURSOR_Y)) {
            restoredCursor = PixelPoint(state.getInt(KEY_CURSOR_X), state.getInt(KEY_CURSOR_Y))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        regionFirst?.let { outState.putIntArray(KEY_REGION_FIRST, it.toIntArray()) }
        outState.putBundle(KEY_RESULTS, resultsBundle())
        if (picker.hasImage) {
            outState.putInt(KEY_CURSOR_X, picker.cursor.x)
            outState.putInt(KEY_CURSOR_Y, picker.cursor.y)
        }
        imageUri?.let { outState.putString(KEY_IMAGE_URI, it.toString()) }
    }

    @Suppress("DEPRECATION")
    private fun readField(intent: Intent?): SetupField? =
        intent?.getSerializableExtra(EXTRA_FIELD) as? SetupField
}
