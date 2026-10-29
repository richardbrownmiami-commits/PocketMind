package com.pocketmind.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.transition.MaterialFadeThrough
import com.pocketmind.R
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.clearMemory
import com.pocketmind.remote.RemoteSessionClient
import com.pocketmind.remote.RemoteSessionManager
import com.pocketmind.tools.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), RemoteSessionManager.UiListener, RemoteSessionManager.ControlTarget {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var remoteBanner: View
    private lateinit var remoteBannerStatus: TextView
    private lateinit var remoteStopButton: MaterialButton
    private val exportLogRequest = 4101
    private val frameHandler = Handler(Looper.getMainLooper())
    private var captureInFlight = false
    private var captureEnabled = false

    private val captureFrame = object : Runnable {
        override fun run() {
            if (!captureEnabled || !RemoteSessionManager.active || !RemoteSessionManager.appForeground) return
            val decor = window.decorView
            if (!decor.hasWindowFocus() || decor.width <= 0 || decor.height <= 0) {
                frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
                return
            }
            if (captureInFlight) return
            captureInFlight = true
            val sourceWidth = decor.width
            val sourceHeight = decor.height
            val frameWidth = min(sourceWidth, MAX_FRAME_WIDTH)
            val frameHeight = max(1, sourceHeight * frameWidth / sourceWidth)
            val bitmap = try { Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888) }
            catch (_: Exception) {
                captureInFlight = false
                frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
                return
            }
            val bounds = Rect(0, 0, sourceWidth, sourceHeight)
            try {
                PixelCopy.request(window, bounds, bitmap, { result ->
                try {
                    if (result == PixelCopy.SUCCESS && captureEnabled && RemoteSessionManager.active &&
                        RemoteSessionManager.appForeground && window.decorView.hasWindowFocus()) {
                        val jpeg = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                        if (jpeg.size() <= MAX_JPEG_BYTES) RemoteSessionManager.sendFrame(jpeg.toByteArray())
                    }
                } catch (_: Exception) {
                    // A failed or oversized frame is discarded; never fall back to full-screen capture.
                } finally {
                    bitmap.recycle()
                    captureInFlight = false
                    if (captureEnabled && RemoteSessionManager.active) frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
                }
                }, frameHandler)
            } catch (_: Exception) {
                bitmap.recycle()
                captureInFlight = false
                if (captureEnabled && RemoteSessionManager.active) frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info(this, "ACTIVITY_CREATE", "savedState=${savedInstanceState != null}")
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.overflowIcon = ContextCompat.getDrawable(this, R.drawable.ic_more_vert)
        bottomNavigation = findViewById(R.id.bottom_navigation)
        remoteBanner = findViewById(R.id.remote_session_banner)
        remoteBannerStatus = findViewById(R.id.remote_session_banner_status)
        remoteStopButton = findViewById(R.id.remote_session_stop_button)
        remoteStopButton.setOnClickListener { stopRemoteSession() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ChatFragment(), TAG_CHAT)
                .commit()
        }
        bottomNavigation.setOnItemSelectedListener { item -> showTab(item.itemId) }
        supportFragmentManager.addOnBackStackChangedListener { syncSelectedTab() }
        renderRemoteState(RemoteSessionManager.active, RemoteSessionManager.status)
    }

    override fun onStart() {
        super.onStart()
        AppLog.info(this, "ACTIVITY_START")
    }

    override fun onResume() {
        super.onResume()
        RemoteSessionManager.bindUi(this, this)
        RemoteSessionManager.setAppForeground(true)
        if (RemoteSessionManager.active) startCapture()
    }

    override fun onPause() {
        stopCapture()
        RemoteSessionManager.setAppForeground(false)
        // Keep pairing alive for sharing the code, but detach all capture/input while not foreground.
        if (isFinishing && RemoteSessionManager.active) RemoteSessionManager.stop(applicationContext)
        super.onPause()
    }

    override fun onStop() {
        AppLog.info(this, "ACTIVITY_STOP")
        super.onStop()
    }

    private fun showTab(itemId: Int): Boolean {
        val destination = when (itemId) {
            R.id.nav_chat -> TAG_CHAT
            R.id.nav_models -> TAG_MODELS
            R.id.nav_rwkv -> TAG_RWKV
            R.id.nav_memory -> TAG_MEMORY
            else -> return false
        }
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current?.tag == destination) return true
        val fragment = when (destination) {
            TAG_CHAT -> ChatFragment()
            TAG_MODELS -> ModelsFragment()
            TAG_RWKV -> RwkvFragment()
            else -> MemoryFragment()
        }
        current?.exitTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
        current?.returnTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
        fragment.enterTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
        fragment.reenterTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
        AppLog.info(this, "NAVIGATE", "destination=$destination")
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment, destination)
            .addToBackStack(destination)
            .commit()
        return true
    }

    private fun syncSelectedTab() {
        if (!::bottomNavigation.isInitialized) return
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val selectedId = when (current?.tag) {
            TAG_CHAT -> R.id.nav_chat
            TAG_MODELS -> R.id.nav_models
            TAG_RWKV -> R.id.nav_rwkv
            TAG_MEMORY -> R.id.nav_memory
            else -> return
        }
        if (bottomNavigation.selectedItemId != selectedId) bottomNavigation.selectedItemId = selectedId
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_remote_test)?.setTitle(
            if (RemoteSessionManager.active) R.string.remote_stop else R.string.remote_start
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_remote_test -> {
            if (RemoteSessionManager.active) stopRemoteSession() else showRemoteSessionSetup()
            true
        }
        R.id.menu_about -> {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
        R.id.menu_export_logs -> {
            exportLogs()
            true
        }
        R.id.menu_clear_memory -> {
            MemoryFragment.confirmClearMemory(this) { clearMemoryFromToolbar() }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showRemoteSessionSetup() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.remote_disclosure)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
        })
        val relayInput = TextInputEditText(this).apply {
            hint = getString(R.string.remote_relay_hint)
            setSingleLine(true)
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_RELAY, com.pocketmind.BuildConfig.DEFAULT_REMOTE_RELAY_URL))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        panel.addView(relayInput)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remote_start_title)
            .setView(panel)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remote_start) { _, _ ->
                val relay = relayInput.text?.toString().orEmpty().trim()
                if (RemoteSessionClient.parseRelayUrl(relay) == null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.remote_invalid_relay_title)
                        .setMessage(R.string.remote_invalid_relay)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_RELAY, relay).apply()
                    startRemoteSession(relay)
                }
            }
            .show()
    }

    private fun startRemoteSession(relay: String) {
        try {
            val code = RemoteSessionManager.start(this, relay)
            RemoteSessionManager.bindUi(this, this)
            renderRemoteState(true, getString(R.string.remote_connecting))
            startCapture()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remote_pairing_title)
                .setMessage(getString(R.string.remote_pairing_message, code.chunked(5).joinToString("-"), relay))
                .setNegativeButton(R.string.remote_stop) { _, _ -> stopRemoteSession() }
                .setNeutralButton(R.string.remote_copy_code) { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("PocketMind session code", code))
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remote_start_failed_title)
                .setMessage(e.message ?: getString(R.string.remote_start_failed))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun stopRemoteSession() {
        stopCapture()
        RemoteSessionManager.stop(applicationContext)
        renderRemoteState(false, getString(R.string.remote_stopped))
    }

    override fun onRemoteSessionChanged(active: Boolean, status: String) {
        runOnUiThread {
            renderRemoteState(active, status)
            if (!active) stopCapture()
        }
    }

    private fun renderRemoteState(active: Boolean, status: String) {
        if (!::remoteBanner.isInitialized) return
        remoteBanner.visibility = if (active) View.VISIBLE else View.GONE
        remoteBannerStatus.text = getString(R.string.remote_active_banner, status)
        toolbar.title = if (active) getString(R.string.remote_toolbar_active) else getString(R.string.app_name)
        invalidateOptionsMenu()
    }

    private fun startCapture() {
        if (!RemoteSessionManager.active || !RemoteSessionManager.appForeground) return
        captureEnabled = true
        frameHandler.removeCallbacks(captureFrame)
        frameHandler.post(captureFrame)
    }

    private fun stopCapture() {
        captureEnabled = false
        frameHandler.removeCallbacks(captureFrame)
    }

    /** Remote pointer events are delivered only to PocketMind's own decor view. */
    override fun remoteTap(x: Double, y: Double) {
        if (!canAcceptRemoteInput()) return
        val decor = window.decorView
        val downTime = SystemClock.uptimeMillis()
        dispatchLocalTouch(decor, MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
        dispatchLocalTouch(decor, MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y)
    }

    override fun remoteSwipe(x: Double, y: Double, x2: Double, y2: Double) {
        if (!canAcceptRemoteInput()) return
        val decor = window.decorView
        val downTime = SystemClock.uptimeMillis()
        dispatchLocalTouch(decor, MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
        dispatchLocalTouch(decor, MotionEvent.ACTION_MOVE, downTime, downTime + SWIPE_DURATION_MS / 2, (x + x2) / 2, (y + y2) / 2)
        dispatchLocalTouch(decor, MotionEvent.ACTION_UP, downTime, downTime + SWIPE_DURATION_MS, x2, y2)
    }

    private fun dispatchLocalTouch(decor: View, action: Int, downTime: Long, eventTime: Long, x: Double, y: Double) {
        if (!canAcceptRemoteInput()) return
        val px = (x * decor.width).toFloat().coerceIn(0f, max(0f, decor.width - 1f))
        val py = (y * decor.height).toFloat().coerceIn(0f, max(0f, decor.height - 1f))
        val event = MotionEvent.obtain(downTime, eventTime, action, px, py, 0)
        try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    override fun remoteText(text: String) {
        if (!canAcceptRemoteInput() || text.length > MAX_REMOTE_TEXT_LENGTH) return
        val focused = currentFocus as? EditText ?: return
        if (!focused.isShown || !focused.hasFocus() || focused.rootView !== window.decorView) return
        focused.setText(text)
        focused.setSelection(focused.text?.length ?: 0)
    }

    private fun canAcceptRemoteInput(): Boolean =
        !isFinishing && !isDestroyed && window.decorView.hasWindowFocus() &&
            RemoteSessionManager.active && RemoteSessionManager.appForeground

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun exportLogs() {
        AppLog.info(this, "LOG_EXPORT_REQUEST")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "pocketmind-diagnostics.log")
        }, exportLogRequest)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportLogRequest || resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data ?: return
        uiScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(AppLog.exportText(this@MainActivity).toByteArray(Charsets.UTF_8))
                } ?: error("Could not open destination")
                AppLog.info(this@MainActivity, "LOG_EXPORT_SUCCESS")
            } catch (e: Exception) {
                AppLog.error(this@MainActivity, "LOG_EXPORT_FAILED", e)
            }
        }
    }

    private fun clearMemoryFromToolbar() {
        val appContext = applicationContext
        uiScope.launch {
            withContext(Dispatchers.IO) {
                val db = AppDatabase(appContext)
                try { clearMemory(db) } finally { db.close() }
            }
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? MemoryFragment)?.refresh()
        }
    }

    override fun onDestroy() {
        stopCapture()
        RemoteSessionManager.bindUi(null, null)
        AppLog.info(this, "ACTIVITY_DESTROY")
        uiScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TRANSITION_DURATION_MS = 250L
        private const val TAG_CHAT = "chat"
        private const val TAG_MODELS = "models"
        private const val TAG_RWKV = "rwkv"
        private const val TAG_MEMORY = "memory"
        private const val PREFS = "remote_test"
        private const val PREF_RELAY = "relay_url"
        private const val FRAME_INTERVAL_MS = 350L
        private const val MAX_FRAME_WIDTH = 480
        private const val MAX_JPEG_BYTES = 256 * 1024
        private const val JPEG_QUALITY = 48
        private const val SWIPE_DURATION_MS = 240L
        private const val MAX_REMOTE_TEXT_LENGTH = 1000
    }
}
