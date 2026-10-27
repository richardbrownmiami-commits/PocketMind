package com.pocketmind.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
        bottomNavigation.setOnItemSelectedListener { item -> showTab(item.itemId) }
        supportFragmentManager.addOnBackStackChangedListener { syncSelectedTab() }
    }

    override fun onStart() {
        super.onStart()
        AppLog.info(this, "ACTIVITY_START")
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
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
    }
}
