package com.pocketmind.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pocketmind.PocketMindApplication
import com.pocketmind.diagnostics.DiagnosticCheck
import com.pocketmind.diagnostics.DiagnosticProgress
import com.pocketmind.diagnostics.DiagnosticReport
import com.pocketmind.diagnostics.DiagnosticStatus
import com.pocketmind.diagnostics.FeatureDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class DiagnosticsActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var progress: TextView
    private lateinit var reportBody: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private val resultRows = mutableMapOf<Int, Pair<TextView, TextView>>()
    private var report: DiagnosticReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildView()
    }

    private fun buildView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
            setBackgroundColor(Color.WHITE)
        }
        title = TextView(this).apply {
            text = "PocketMind QA tester"
            textSize = 24f
            setTextColor(Color.BLACK)
            contentDescription = "PocketMind all-feature tester"
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Run every available feature check one by one. Progress and failure causes appear below and in Android logs. Tests use temporary data and stay offline. Real alarms, timers, device controls, and external screens are never triggered."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 10)
        })
        state = TextView(this).apply {
            text = "Ready to test PocketMind features."
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 4)
        }
        root.addView(state)
        progress = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 8)
        }
        root.addView(progress)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runButton = Button(this).apply {
            text = "Start all tests"
            contentDescription = "Start all PocketMind feature tests"
            setOnClickListener { runTests() }
        }
        buttons.addView(runButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shareButton = Button(this).apply {
            text = "Share report"
            contentDescription = "Share the completed PocketMind QA report"
            isEnabled = false
            setOnClickListener { shareReport() }
        }
        buttons.addView(shareButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        logScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            contentDescription = "Scrollable test log; swipe to review results"
        }
        reportBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 16)
        }
        logScroll.addView(reportBody)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun testerUiChecks(): List<DiagnosticCheck> {
        val wired = title.text == "PocketMind QA tester" &&
            runButton.text == "Start all tests" && runButton.hasOnClickListeners() &&
            shareButton.text == "Share report" && shareButton.hasOnClickListeners() && !shareButton.isEnabled &&
            logScroll.childCount == 1 && reportBody.orientation == LinearLayout.VERTICAL
        return listOf(
            DiagnosticCheck(
                "UI · dedicated Tester screen and buttons",
                if (wired) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                if (wired) "The dedicated QA screen is open; Start all tests initiated this run, Share report is correctly disabled until completion, and the test log is vertically scrollable."
                else "A Tester screen title, button callback/state, or scrollable log is missing. Check DiagnosticsActivity view construction."
            )
        )
    }

    private fun runTests() {
        runButton.isEnabled = false
        shareButton.isEnabled = false
        report = null
        progress.text = "Preparing the isolated test run…"
        resultRows.clear()
        reportBody.removeAllViews()
        state.text = "Testing all registered features…"
        val uiChecks = testerUiChecks()
        uiScope.launch {
            try {
                var completed = withContext(Dispatchers.IO) {
                    FeatureDiagnostics.run(
                        this@DiagnosticsActivity,
                        (application as PocketMindApplication).agentController,
                        initialUiChecks = uiChecks
                    ) { event ->
                        withContext(Dispatchers.Main.immediate) { showProgress(event) }
                    }
                }
                val swipeCheck = runSwipeSmokeTest()
                completed = completed.copy(checks = completed.checks + swipeCheck)
                report = completed
                addFinishedCard(swipeCheck)
                state.text = completed.summary()
                progress.text = "${completed.checks.size} checks completed · ${completed.failures} failed · ${completed.warnings} warnings · ${completed.notRun} intentionally not run"
                shareButton.isEnabled = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.text = "Test run stopped: ${error.message?.replace(Regex("[\\r\\n]+"), " ")?.take(140) ?: "unexpected error"}. Rerun to try again."
                progress.text = ""
            } finally {
                runButton.isEnabled = true
            }
        }
    }

    private fun showProgress(event: DiagnosticProgress) {
        progress.text = "Test ${event.index}/${event.total} · ${event.title}"
        if (event.status == null) {
            val card = createCard(event.title, "Running…", null)
            resultRows[event.index] = card
            reportBody.addView(card.first.tag as View)
        } else {
            val row = resultRows[event.index]
            if (row != null) {
                row.first.text = "${event.status} · ${event.title}"
                row.first.setTextColor(statusColor(event.status))
                row.second.text = event.details
            }
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createCard(titleText: String, detailText: String, status: DiagnosticStatus?): Pair<TextView, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.rgb(246, 247, 249))
        }
        val heading = TextView(this).apply {
            text = if (status == null) "RUNNING · $titleText" else "$status · $titleText"
            textSize = 15f
            setTextColor(status?.let(::statusColor) ?: Color.DKGRAY)
        }
        val details = TextView(this).apply {
            text = detailText
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 4, 0, 0)
        }
        card.addView(heading)
        card.addView(details)
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8
        }
        heading.tag = card
        return heading to details
    }

    private fun addFinishedCard(check: DiagnosticCheck) {
        val row = createCard(check.title, check.details, check.status)
        row.first.text = "${check.status} · ${check.title}"
        row.second.text = check.details
        reportBody.addView(row.first.tag as View)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun statusColor(status: DiagnosticStatus): Int = when (status) {
        DiagnosticStatus.PASS -> Color.rgb(24, 112, 62)
        DiagnosticStatus.WARN -> Color.rgb(151, 91, 0)
        DiagnosticStatus.FAIL -> Color.rgb(174, 35, 35)
        DiagnosticStatus.NOT_RUN -> Color.DKGRAY
    }

    private suspend fun runSwipeSmokeTest(): DiagnosticCheck = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            logScroll.post {
                if (!continuation.isActive) return@post
                val contentHeight = reportBody.height
                val viewport = logScroll.height
                if (viewport <= 0 || contentHeight <= viewport + 8) {
                    continuation.resume(DiagnosticCheck("UI · swipe scrolls the test log", DiagnosticStatus.WARN,
                        "The completed log did not exceed the viewport, so a scroll gesture was not needed. Review the visible results directly."))
                    return@post
                }
                logScroll.scrollTo(0, 0)
                dispatchSwipe(fromY = viewport * 0.82f, toY = viewport * 0.18f)
                val movedDown = logScroll.scrollY > 0
                val bottom = logScroll.scrollY
                dispatchSwipe(fromY = viewport * 0.18f, toY = viewport * 0.82f)
                val movedUp = logScroll.scrollY < bottom
                logScroll.scrollTo(0, 0)
                val passed = movedDown && movedUp
                val check = DiagnosticCheck("UI · swipe scrolls the test log", if (passed) DiagnosticStatus.PASS else DiagnosticStatus.FAIL,
                    if (passed) "Synthetic finger swipes moved down through the real test cards and back up; the complete log remains reviewable."
                    else "The test cards were scrollable by layout but a synthetic swipe did not move in both directions. Check ScrollView touch handling.")
                continuation.resume(check)
            }
        }
    }

    private fun dispatchSwipe(fromY: Float, toY: Float) {
        val x = (logScroll.width / 2f).coerceAtLeast(1f)
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, offset: Long) {
            MotionEvent.obtain(start, start + offset, action, x, y, 0).also { event ->
                logScroll.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, fromY, 0)
        val steps = 8
        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            send(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * fraction, step * 16L)
        }
        send(MotionEvent.ACTION_UP, toY, (steps + 1) * 16L)
    }

    private fun shareReport() {
        val text = report?.asShareText() ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PocketMind all-feature QA report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, "Share QA report"))
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
