package com.pocketmind.ui

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bottomNavigation: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun showTab(itemId: Int): Boolean {
        val destination = when (itemId) {
            R.id.nav_chat -> TAG_CHAT
            R.id.nav_models -> TAG_MODELS
            R.id.nav_memory -> TAG_MEMORY
            else -> return false
        }
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current?.tag == destination) return true
        val fragment = when (destination) {
            TAG_CHAT -> ChatFragment()
            TAG_MODELS -> ModelsFragment()
            else -> MemoryFragment()
        }
        current?.exitTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
        current?.returnTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
        fragment.enterTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
        fragment.reenterTransition = MaterialFadeThrough().apply { duration = TRANSITION_DURATION_MS }
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
        R.id.menu_clear_memory -> {
            MemoryFragment.confirmClearMemory(this) { clearMemoryFromToolbar() }
            true
        }
        else -> super.onOptionsItemSelected(item)
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
        uiScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TRANSITION_DURATION_MS = 250L
        private const val TAG_CHAT = "chat"
        private const val TAG_MODELS = "models"
        private const val TAG_MEMORY = "memory"
    }
}
