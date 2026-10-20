package com.lampp.nmaxconnect.core.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.lampp.nmaxconnect.core.protocol.ccu.LocalRecord
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import com.lampp.nmaxconnect.core.settings.DashStat

/**
 * Preview of the whole dash while guiding: the TFT's own chrome (status bar, zoom buttons, ETA + road bar) around
 * the frame [DashHud] paints, over a stand-in day map. Measured from a head-on bike photo: units are frame pixels
 * vertically; the dash shows the frame about 7 % wider than its pixels, so it is drawn stretched by [STRETCH_X].
 * Shortcut: one photo of one dash; remeasure if another model looks different.
 */
internal object DashMockup {
    private const val STRETCH_X = 1.07f
    private const val TOP_H = 37f // status bar incl. its white rule
    private const val BAR_Y = 196f // the ETA / road bar covers the frame from here down
    private const val BAR_X = 146f // the black road bar starts here; the ETA sits over our column
    private const val SCALE = 2f // render at 2x so the preview stays sharp

    fun render(
        display: DisplayModel,
        turns: Boolean,
        stats: LocalRecord?,
        items: Set<DashStat>,
        turn: DashGuidance,
        eta: String,
        road: String,
    ): Bitmap {
        val hud = DashHud()
        val column = if (stats != null || turns) DashHud.COLUMN_W else 0f
        val frame = createBitmap(display.width, display.height).also { bitmap ->
            val c = Canvas(bitmap)
            drawMap(c, column)
            hud.drawPuck(c, column)
            hud.drawPanel(c, turn.takeIf { turns }, stats, items)
        }
        val w = display.width * STRETCH_X
        val h = TOP_H + display.height
        val out = createBitmap((w * SCALE).toInt(), (h * SCALE).toInt())
        Canvas(out).withScale(SCALE, SCALE) {
            drawColor(BLACK)
            drawStatusBar(this, w)
            drawBitmap(frame, null, RectF(0f, TOP_H, w, h), bitmapPaint)
            withTranslation(0f, TOP_H) {
                drawZoomButtons(this)
                drawBottomBar(this, w, display.height.toFloat(), eta, road)
            }
        }
        return out
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.SQUARE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val mapIcon: Path = PathParser.createPathFromPathData(
        "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 0.5,0.5" +
            "l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5c0,-0.28 -0.22,-0.5 -0.5,-0.5z" +
            "M15,19l-6,-2.11V5l6,2.11V19z",
    )

    /** Light day map stand-in: a park, blocks, a few streets and the route ahead from the puck right of [left]. */
    private fun drawMap(c: Canvas, left: Float) {
        c.drawColor(0xFFECE8E0.toInt())
        fill.color = 0xFFC5E3A5.toInt()
        c.drawRect(0f, 0f, 480f, 50f, fill)
        fill.color = 0xFFD5D1CA.toInt()
        c.drawRect(250f, 150f, 330f, 185f, fill)
        c.drawRect(380f, 70f, 450f, 105f, fill)
        road.color = WHITE
        road.strokeWidth = 12f
        c.drawLine(150f, 125f, 480f, 135f, road)
        c.drawLine(420f, 0f, 360f, 240f, road)
        c.drawLine(200f, 60f, 480f, 50f, road)
        val cx = (left + c.width) / 2f
        road.color = 0xFF1A73E8.toInt()
        road.strokeWidth = 6f
        c.drawLine(cx, c.height / 2f, 480f, c.height / 2f + 14f, road)
    }

    private fun drawStatusBar(c: Canvas, w: Float) {
        text.textSize = 32f
        c.drawText("7:49", 20f, 31f, text)
        c.withTranslation(98f, 5f) { withScale(26f / 24f, 26f / 24f) { drawPath(mapIcon, white) } }
        for (i in 0..4) c.drawRect(207f + i * 6.5f, 24f - i * 4.5f, 211.5f + i * 6.5f, 30f, white)
        c.drawRect(252f, 3f, 258f, 6f, white)
        c.drawRoundRect(248f, 6f, 262f, 31f, 2f, 2f, outline)
        c.drawRect(251f, 21f, 259f, 28f, white)
        c.drawRoundRect(278f, 8f, 306f, 30f, 3f, 3f, white)
        text.textSize = 13f
        text.color = DARK
        text.textAlign = Paint.Align.CENTER
        c.drawText("App", 292f, 24f, text)
        fill.color = 0xFF1AA7C4.toInt()
        c.drawRoundRect(440f, 6f, 468f, 32f, 4f, 4f, fill)
        text.textSize = 24f
        c.drawText("T", 454f, 28f, text)
        text.color = WHITE
        text.textAlign = Paint.Align.LEFT
        c.drawRect(0f, TOP_H - 2.5f, w, TOP_H, white)
    }

    /** The dash's zoom buttons while guiding: grey triangles, + pointing up, − pointing down (bike photo). */
    private fun drawZoomButtons(c: Canvas) {
        fill.color = 0xFFC9CBCC.toInt()
        c.drawPath(Path().apply { moveTo(160f, 78f); lineTo(179f, 110f); lineTo(141f, 110f); close() }, fill)
        c.drawPath(Path().apply { moveTo(142f, 124f); lineTo(180f, 124f); lineTo(161f, 156f); close() }, fill)
        fill.color = DARK
        c.drawRect(154f, 98.5f, 166f, 101.5f, fill)
        c.drawRect(158.5f, 94f, 161.5f, 106f, fill)
        c.drawRect(155f, 132.5f, 167f, 135.5f, fill)
    }

    /** ETA over our column (flag, time, small "pm") and the black current-road bar to its right. */
    private fun drawBottomBar(c: Canvas, w: Float, h: Float, eta: String, roadName: String) {
        fill.color = BLACK
        c.drawRect(BAR_X, BAR_Y, w, h, fill)
        c.drawRect(28f, 205f, 30f, 224f, white) // finish flag
        for (i in 0..3) c.drawRect(30f + i * 3f, 205f + (i % 2) * 3f, 33f + i * 3f, 208f + (i % 2) * 3f, white)
        for (i in 0..3) c.drawRect(30f + i * 3f, 211f - (i % 2) * 3f, 33f + i * 3f, 214f - (i % 2) * 3f, white)
        text.textSize = 25f
        text.typeface = Typeface.DEFAULT
        c.drawText(eta, 48f, 223f, text)
        val etaEnd = 48f + text.measureText(eta) + 2f
        text.textSize = 16f
        c.drawText("pm", etaEnd, 223f, text)
        text.textSize = 26f
        text.typeface = Typeface.DEFAULT_BOLD
        val label = "▲ $roadName"
        c.drawText(label, BAR_X + (w - BAR_X - text.measureText(label)) / 2, 221f, text)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val DARK = 0xFF2A2A2A.toInt()
}
