package com.example.cookierunbot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.cookierunbot.statistics.RoundResultStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatisticsActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onStart() { super.onStart(); refresh.run() }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }

    private fun render() {
        val stats = RoundResultStore.snapshot()
        content.removeAllViews()
        add("Session Statistics", 24f)
        add("Rounds: ${stats.rounds}")
        add("Total Coins: ${stats.totalCoins}")
        add("Total EXP: ${stats.totalXp}")
        add("Boxes: ${stats.totalBoxes}")
        add("Session Runtime: ${stats.sessionRuntimeMs / 60_000}:${(stats.sessionRuntimeMs / 1000 % 60).toString().padStart(2, '0')}")
        add("\nLast Round", 20f)
        val last = stats.lastRound
        add(if (last == null) "No completed rounds" else describe(last))
        add("\nRound History", 20f)
        stats.history.forEach { add(describe(it)) }
    }

    private fun describe(result: com.example.cookierunbot.statistics.RoundResult): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(result.finishedAt))
        val duration = result.durationMs?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) } ?: "—"
        return "Round #${result.round}  $time  $duration\nCoins: ${result.coins ?: "—"}  EXP: ${result.xp ?: "—"}\n" +
            "Boxes: ${result.boxes.ifEmpty { listOf("—") }.joinToString()}  Screenshot: ${if (result.hasScreenshot) "Yes" else "No"}"
    }

    private fun add(text: String, size: Float = 17f) = content.addView(TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, 8, 0, 8)
    })
}
