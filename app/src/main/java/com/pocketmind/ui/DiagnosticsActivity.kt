package com.pocketmind.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pocketmind.PocketMindApplication
import com.pocketmind.diagnostics.DiagnosticReport
import com.pocketmind.diagnostics.DiagnosticStatus
import com.pocketmind.diagnostics.FeatureDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticsActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var reportBody: LinearLayout
    private lateinit var state: TextView
    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private var report: DiagnosticReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildView()
    }

    private fun buildView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "PocketMind tester"
            textSize = 24f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Runs offline on this phone. It tests deterministic logic, safe tools and memory in a temporary database, and runs a short model test if a model is loaded. It reports Android memory pressure without stopping or unloading the model; Android itself may still reclaim the app. It will not create alarms, change settings, or send anything."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 12)
        })
        state = TextView(this).apply {
            text = "Run diagnostics to check PocketMind features."
            textSize = 15f
            setPadding(0, 0, 0, 8)
        }
        root.addView(state)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runButton = Button(this).apply {
            text = "Run tests"
            setOnClickListener { runTests() }
        }
        buttons.addView(runButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shareButton = Button(this).apply {
            text = "Share report"
            isEnabled = false
            setOnClickListener { shareReport() }
        }
        buttons.addView(shareButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        val scroll = ScrollView(this)
        reportBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 16)
        }
        scroll.addView(reportBody)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun runTests() {
        runButton.isEnabled = false
        shareButton.isEnabled = false
        report = null
        reportBody.removeAllViews()
        state.text = "Checking app features… No user data will be used for database tests."
        uiScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FeatureDiagnostics.run(this@DiagnosticsActivity, (application as PocketMindApplication).agentController)
                }
                report = result
                state.text = result.summary()
                render(result)
                shareButton.isEnabled = true
            } catch (e: Exception) {
                state.text = "Diagnostics stopped: ${e.message?.take(180) ?: "unexpected error"}. Try again; no report was shared."
            } finally {
                runButton.isEnabled = true
            }
        }
    }

    private fun render(value: DiagnosticReport) {
        reportBody.removeAllViews()
        value.checks.forEach { check ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(Color.rgb(246, 247, 249))
            }
            val color = when (check.status) {
                DiagnosticStatus.PASS -> Color.rgb(24, 112, 62)
                DiagnosticStatus.WARN -> Color.rgb(151, 91, 0)
                DiagnosticStatus.FAIL -> Color.rgb(174, 35, 35)
                DiagnosticStatus.NOT_RUN -> Color.DKGRAY
            }
            card.addView(TextView(this).apply {
                text = "${check.status} · ${check.title}"
                textSize = 16f
                setTextColor(color)
            })
            card.addView(TextView(this).apply {
                text = check.details
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, 4, 0, 0)
            })
            reportBody.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8
            })
        }
        reportBody.addView(TextView(this).apply {
            text = "If a model is already loaded, the tester runs a short generation check without an app-level free-RAM gate. The memory-pressure result is a read-only Android status; normal OS process management still applies."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(4, 4, 4, 12)
        })
    }

    private fun shareReport() {
        val text = report?.asShareText() ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PocketMind diagnostic report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, "Share diagnostic report"))
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
