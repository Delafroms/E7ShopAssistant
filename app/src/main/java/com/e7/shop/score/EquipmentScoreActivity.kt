package com.e7.shop.score

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.e7.shop.R
import java.util.concurrent.Executors

/**
 * Equipment scoring (learned from the Rem assistant's 背包算分 feature).
 *
 * Non-root flow: the player takes a screenshot of a gear's detail page in
 * the game, opens this screen, picks the image from the gallery, and the
 * app OCRs the stat lines and scores them with the standard E7 weights.
 */
class EquipmentScoreActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    /** PP-OCRv5 是否已就绪（进入页面时后台预加载）。 */
    private var ppOcrReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equipment_score)

        // 后台预加载 OCR 模型，用户选图时即可直接识别
        executor.execute { ppOcrReady = com.e7.shop.bot.PpOcr.load(assets) }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQ_PICK)
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val status = findViewById<TextView>(R.id.scoreStatus)
        status.text = getString(R.string.score_analyzing)
        findViewById<TextView>(R.id.scoreTotal).text = "--"
        clearResults()

        executor.execute {
            try {
                val bmp = loadDownscaled(uri, 1400)
                runOnUiThread {
                    findViewById<ImageView>(R.id.scorePreview).setImageBitmap(bmp)
                }
                recognize(bmp)
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = getString(R.string.score_ocr_failed)
                    Toast.makeText(this, getString(R.string.score_ocr_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadDownscaled(uri: Uri, maxDim: Int): Bitmap {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        var sample = 1
        while (opt.outWidth / sample > maxDim || opt.outHeight / sample > maxDim) sample *= 2
        val opt2 = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { return BitmapFactory.decodeStream(it, null, opt2)!! }
        error("cannot open image")
    }

    private fun recognize(bmp: Bitmap) {
        // C2：统一到 PP-OCRv5（与主识别层同一引擎），去掉 ML Kit。
        // 旧版这里用 ML Kit 中文识别 —— 同一 App 内两套 OCR 并存，
        // 既冗余体积（ML Kit 中文模型 10MB+）又让识别口径不一致。
        val lines = try {
            if (!ppOcrReady) ppOcrReady = com.e7.shop.bot.PpOcr.load(assets)
            if (ppOcrReady) com.e7.shop.bot.PpOcr.recognize(bmp).map { it.text } else emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
        if (lines.isEmpty()) {
            runOnUiThread {
                findViewById<TextView>(R.id.scoreStatus).text = getString(R.string.score_ocr_failed)
            }
            return
        }
        val score = ScoreEngine.score(lines)
        runOnUiThread { showResult(score) }
    }

    private fun clearResults() {
        val container = findViewById<LinearLayout>(R.id.scoreList)
        container.removeAllViews()
    }

    private fun showResult(result: ScoreEngine.ScoreResult) {
        findViewById<TextView>(R.id.scoreStatus).text =
            getString(R.string.score_stats) + " (${result.lines.size})"
        findViewById<TextView>(R.id.scoreTotal).text = String.format("%.1f", result.total)
        val container = findViewById<LinearLayout>(R.id.scoreList)
        container.removeAllViews()
        if (result.lines.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.score_no_stats)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            }
            container.addView(empty)
            return
        }
        for (line in result.lines) {
            val row = TextView(this).apply {
                val valText = if (line.isPercent) String.format("%.0f%%", line.value)
                else String.format("%.0f", line.value)
                text = "${line.name}  +$valText   =  ${line.points} pts"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(0, 10, 0, 10)
            }
            container.addView(row)
        }
        // scroll results into view
        findViewById<ScrollView>(R.id.scoreScroll).post {
            findViewById<ScrollView>(R.id.scoreScroll).fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    companion object {
        private const val REQ_PICK = 1001
    }
}
