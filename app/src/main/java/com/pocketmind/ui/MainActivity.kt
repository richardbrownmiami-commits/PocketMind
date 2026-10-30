package com.pocketmind.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import com.pocketmind.R
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.clearMemory
import com.pocketmind.tools.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bottomNavigation: BottomNavigationView
    private var suppressNavigationLogging = false
    private val exportLogRequest = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info(this, "ACTIVITY_CREATE", "savedState=${savedInstanceState != null}")
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.overflowIcon = ContextCompat.getDrawable(this, R.drawable.ic_more_vert)
        bottomNavigation = findViewById(R.id.bottom_navigation)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ChatFragment(), TAG_CHAT)
                .commit()
        }
        bottomNavigation.setOnItemSelectedListener { item ->
            val destination = navigationDestination(item.itemId)
            if (!suppressNavigationLogging) AppLog.info(this, "UI_CONTROL_TAPPED", "screen=main control=bottom_navigation destination=$destination")
            val accepted = showTab(item.itemId)
            if (!suppressNavigationLogging) AppLog.info(this, "NAVIGATION_SELECTION_RESULT", "destination=$destination outcome=${if (accepted) "accepted" else "rejected"}")
            accepted
        }
        bottomNavigation.setOnItemReselectedListener { item ->
            AppLog.info(this, "UI_CONTROL_TAPPED", "screen=main control=bottom_navigation_reselect destination=${navigationDestination(item.itemId)}")
            AppLog.info(this, "NAVIGATION_SELECTION_RESULT", "destination=${navigationDestination(item.itemId)} outcome=already_selected")
        }
        supportFragmentManager.addOnBackStackChangedListener {
            val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
            bottomNavigation.visibility = if (current?.tag == TAG_DEBUG_LOGGER) View.GONE else View.VISIBLE
            AppLog.info(this, "NAVIGATION_STATE_CHANGED", "destination=${current?.tag ?: "none"} backStackEntries=${supportFragmentManager.backStackEntryCount}")
            syncSelectedTab()
        }
        if (supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag == TAG_DEBUG_LOGGER) {
            bottomNavigation.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.info(this, "ACTIVITY_START")
    }

    override fun onStop() {
        AppLog.info(this, "ACTIVITY_STOP")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AppLog.info(this, "ACTIVITY_RESUME")
    }

    override fun onPause() {
        AppLog.info(this, "ACTIVITY_PAUSE")
        super.onPause()
    }

    private fun showTab(itemId: Int): Boolean {
        val destination = when (itemId) {
            R.id.nav_chat -> TAG_CHAT
            R.id.nav_models -> TAG_MODELS
            R.id.nav_rwkv -> TAG_RWKV
            R.id.nav_memory -> TAG_MEMORY
            R.id.nav_settings -> TAG_SETTINGS
            else -> return false
        }
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current?.tag == destination) {
            if (!suppressNavigationLogging) AppLog.info(this, "NAVIGATION_NO_OP", "destination=$destination reason=already_current")
            return true
        }
        val fragment = when (destination) {
            TAG_CHAT -> ChatFragment()
            TAG_MODELS -> ModelsFragment()
            TAG_RWKV -> RwkvFragment()
            TAG_SETTINGS -> InferenceSettingsFragment()
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
        AppLog.info(this, "NAVIGATION_COMMIT_REQUESTED", "destination=$destination")
        return true
    }

    private fun navigationDestination(itemId: Int): String = when (itemId) {
        R.id.nav_chat -> TAG_CHAT
        R.id.nav_models -> TAG_MODELS
        R.id.nav_rwkv -> TAG_RWKV
        R.id.nav_memory -> TAG_MEMORY
        R.id.nav_settings -> TAG_SETTINGS
        else -> "unknown"
    }

    private fun syncSelectedTab() {
        if (!::bottomNavigation.isInitialized) return
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val selectedId = when (current?.tag) {
            TAG_CHAT -> R.id.nav_chat
            TAG_MODELS -> R.id.nav_models
            TAG_RWKV -> R.id.nav_rwkv
            TAG_MEMORY -> R.id.nav_memory
            TAG_SETTINGS -> R.id.nav_settings
            else -> return
        }
        if (bottomNavigation.selectedItemId != selectedId) {
            suppressNavigationLogging = true
            try { bottomNavigation.selectedItemId = selectedId } finally { suppressNavigationLogging = false }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_about -> {
            AppLog.info(this, "UI_MENU_TAPPED", "action=about")
            AppLog.info(this, "ABOUT_DIALOG_OPENED")
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> AppLog.info(this, "ABOUT_DIALOG_DISMISSED", "outcome=confirmed") }
                .setOnCancelListener { AppLog.info(this, "ABOUT_DIALOG_DISMISSED", "outcome=cancelled") }
                .show()
            true
        }
        R.id.menu_export_logs -> {
            AppLog.info(this, "UI_MENU_TAPPED", "action=export_logs")
            exportLogs()
            true
        }
        R.id.menu_debug_logger -> {
            AppLog.info(this, "UI_MENU_TAPPED", "action=debug_logger")
            openDebugLogger()
            true
        }
        R.id.menu_clear_memory -> {
            AppLog.info(this, "UI_MENU_TAPPED", "action=clear_memory")
            MemoryFragment.confirmClearMemory(this, source = "toolbar") { clearMemoryFromToolbar() }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openDebugLogger() {
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current?.tag == TAG_DEBUG_LOGGER) {
            AppLog.info(this, "DEBUG_LOG_VIEW_REQUESTED", "outcome=already_open")
            return
        }
        AppLog.info(this, "DEBUG_LOG_VIEW_REQUESTED", "outcome=navigation_requested")
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, DebugLogFragment(), TAG_DEBUG_LOGGER)
            .addToBackStack(TAG_DEBUG_LOGGER)
            .commit()
        bottomNavigation.visibility = View.GONE
    }

    private fun exportLogs() {
        AppLog.info(this, "LOG_EXPORT_REQUEST")
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "pocketmind-diagnostics.log")
            }, exportLogRequest)
            AppLog.info(this, "LOG_EXPORT_PICKER_OPENED")
        } catch (e: Exception) {
            AppLog.error(this, "LOG_EXPORT_PICKER_FAILED", e)
            throw e
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportLogRequest) return
        if (resultCode != Activity.RESULT_OK) {
            AppLog.info(this, "LOG_EXPORT_PICKER_RESULT", "outcome=cancelled resultCode=$resultCode")
            return
        }
        val uri = data?.data
        if (uri == null) {
            AppLog.warn(this, "LOG_EXPORT_PICKER_RESULT", "outcome=failed reason=missing_uri")
            return
        }
        AppLog.info(this, "LOG_EXPORT_STARTED")
        uiScope.launch(Dispatchers.IO) {
            try {
                val text = AppLog.exportText(this@MainActivity)
                contentResolver.openOutputStream(uri)?.use { output -> output.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("Could not open destination")
                AppLog.info(this@MainActivity, "LOG_EXPORT_SUCCESS", "bytes=${text.toByteArray(Charsets.UTF_8).size}")
            } catch (e: Exception) {
                AppLog.error(this@MainActivity, "LOG_EXPORT_FAILED", e)
            }
        }
    }

    private fun clearMemoryFromToolbar() {
        val appContext = applicationContext
        AppLog.info(appContext, "MEMORY_CLEAR_STARTED", "source=toolbar")
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val db = AppDatabase(appContext)
                    try { clearMemory(db) } finally { db.close() }
                }
                AppLog.info(appContext, "MEMORY_CLEAR_FINISHED", "source=toolbar outcome=success")
                (supportFragmentManager.findFragmentById(R.id.fragment_container) as? MemoryFragment)?.refresh("after_toolbar_clear")
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLog.info(appContext, "MEMORY_CLEAR_FINISHED", "source=toolbar outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(appContext, "MEMORY_CLEAR_FAILED", e, "source=toolbar")
                throw e
            }
        }
    }

    override fun onDestroy() {
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
        private const val TAG_SETTINGS = "settings"
        private const val TAG_DEBUG_LOGGER = "debug_logger"
    }
}
