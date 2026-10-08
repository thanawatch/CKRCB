package com.example.cookierunbot

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.cookierunbot.statistics.RoundResultStore
import com.example.cookierunbot.statistics.formatSessionRuntime
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

class StatisticsActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private val summaryValues = mutableListOf<TextView>()
    private val boxValues = linkedMapOf<String, TextView>()
    private lateinit var lastRoundContent: TextView
    private lateinit var historyContent: LinearLayout
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.rgb(245, 247, 251))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        buildDashboard()
    }

    override fun onStart() { super.onStart(); refresh.run() }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }

    private fun render() {
        val stats = RoundResultStore.snapshot()
        summaryValues[0].text = "${stats.rounds} รอบ"
        summaryValues[1].text = number(stats.totalCoins)
        summaryValues[2].text = number(stats.totalXp)
        summaryValues[3].text = formatSessionRuntime(stats.sessionRuntimeMs)
        boxValues["wood"]?.text = stats.woodBoxes.toString()
        boxValues["silver"]?.text = stats.silverBoxes.toString()
        boxValues["gold"]?.text = stats.goldBoxes.toString()
        boxValues["rainbow"]?.text = stats.rainbowBoxes.toString()
        val last = stats.lastRound
        lastRoundContent.text = if (last == null) "ยังไม่มีรอบที่จบ" else describe(last)
        historyContent.removeAllViews()
        if (stats.history.isEmpty()) {
            historyContent.addView(bodyText("ยังไม่มีประวัติการวิ่ง"))
        } else {
            stats.history.forEach { result ->
                historyContent.addView(bodyText(describe(result)).apply {
                    background = cardBackground(Color.WHITE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }, matchWidth(dp(8)))
            }
        }
    }

    private fun buildDashboard() {
        content.addView(title("Statistics", 25f), matchWidth(dp(12)))
        val summary = GridLayout(this).apply { columnCount = 2; rowCount = 2 }
        listOf("วิ่งไปแล้ว", "เงินรวมทุกรอบ", "XP รวม", "เวลาที่ทำงาน").forEach { label ->
            val value = TextView(this).apply {
                textSize = 20f
                setTextColor(Color.rgb(28, 37, 54))
                setTypeface(typeface, Typeface.BOLD)
            }
            summaryValues += value
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = cardBackground(Color.WHITE)
                addView(bodyText(label))
                addView(value)
            }
            summary.addView(card, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        content.addView(summary, matchWidth(dp(12)))

        val boxesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBackground(Color.WHITE)
            addView(title("กล่องรวมทุกรอบ", 19f))
            addView(LinearLayout(this@StatisticsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("wood", "silver", "gold", "rainbow").forEach { grade ->
                    addView(boxEntry(grade), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }, matchWidth())
        }
        content.addView(boxesCard, matchWidth(dp(16)))
        content.addView(title("Last Round", 20f), matchWidth(dp(6)))
        lastRoundContent = bodyText("").apply {
            background = cardBackground(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(lastRoundContent, matchWidth(dp(16)))
        content.addView(title("Round History", 20f), matchWidth(dp(6)))
        historyContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyContent, matchWidth())
    }

    private fun boxEntry(grade: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val bitmap = assets.open("templates/BOX_${grade.uppercase()}.png").use(android.graphics.BitmapFactory::decodeStream)
        addView(ImageView(this@StatisticsActivity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = grade
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(bodyText(grade.replaceFirstChar { it.uppercase() }).apply { gravity = Gravity.CENTER })
        val count = title("0", 18f).apply { gravity = Gravity.CENTER }
        boxValues[grade] = count
        addView(count)
    }

    private fun describe(result: com.example.cookierunbot.statistics.RoundResult): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(result.finishedAt))
        val elapsed = result.durationMs?.let(::duration) ?: "—"
        val boxSummary = result.boxes.groupingBy { it }.eachCount().entries.joinToString { "${it.key} ×${it.value}" }.ifEmpty { "—" }
        return "Round #${result.round}  •  $time  •  $elapsed\n" +
            "Coins: ${result.coins?.let(::number) ?: "—"}    EXP: ${result.xp?.let(::number) ?: "—"}\n" +
            "Boxes: $boxSummary\nScreenshot: ${if (result.hasScreenshot) "Available" else "None"}"
    }

    private fun title(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(28, 37, 54)); setTypeface(typeface, Typeface.BOLD)
    }
    private fun bodyText(value: String) = TextView(this).apply {
        text = value; textSize = 15f; setTextColor(Color.rgb(75, 84, 101)); setLineSpacing(0f, 1.12f)
    }
    private fun cardBackground(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun matchWidth(bottomMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.bottomMargin = bottomMargin }
    private fun number(value: Int) = NumberFormat.getIntegerInstance(Locale.US).format(value)
    private fun duration(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val hours = seconds / 3600
        return if (hours > 0) "%02d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
