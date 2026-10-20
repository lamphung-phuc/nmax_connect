package com.lampp.nmaxconnect.core.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.PathParser
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.lampp.nmaxconnect.core.protocol.ccu.LocalRecord
import com.lampp.nmaxconnect.core.settings.DashStat

/** Next turn as drawn into the frame: TFT turn icon code (docs/bike-protocol.md §3.3), "120 m", next street. */
data class DashGuidance(val icon: Int, val distance: String, val street: String)

/**
 * Paints the rider's position, the next turn and the bike stats column onto each frame.
 * Colours are map paint, not app UI (no Material theme on the dash).
 */
internal class DashHud {
    private val columnPaint = Paint().apply { color = COLUMN_COLOR }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val distancePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val streetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 15f
        textAlign = Paint.Align.CENTER
    }
    private val turnDistancePaint = TextPaint(distancePaint).apply {
        textSize = 24f
        textAlign = Paint.Align.LEFT
    }
    private val turnStreetPaint = TextPaint(streetPaint).apply {
        textSize = 15f
        textAlign = Paint.Align.LEFT
    }
    private val statPaint = TextPaint(turnDistancePaint).apply { textSize = 16f }
    private val puckFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt() }
    private val puckStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        // up-pointing turn arrow in a 1×1 box centred on the origin
        moveTo(0f, -0.5f); lineTo(0.42f, -0.05f); lineTo(0.13f, -0.05f); lineTo(0.13f, 0.5f)
        lineTo(-0.13f, 0.5f); lineTo(-0.13f, -0.05f); lineTo(-0.42f, -0.05f); close()
    }
    private val puck = Path().apply {
        moveTo(0f, -13f); lineTo(10f, 11f); lineTo(0f, 5f); lineTo(-10f, 11f); close()
    }
    private val statIcons = STAT_ICONS.mapValues { (_, icon) ->
        PathParser.createPathFromPathData(icon.path) to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = icon.color }
    }

    /** Track-up camera: the GPS position is the centre of the map area right of [left] and the heading is up. */
    fun drawPuck(canvas: Canvas, left: Float = 0f) = canvas.withTranslation((left + canvas.width) / 2f, canvas.height / 2f) {
        drawPath(puck, puckFill)
        drawPath(puck, puckStroke)
    }

    /**
     * Left column wide enough to sit under the dash's zoom buttons. Without [stats]: the turn alone, stacked and
     * centred like a dash turn column. With [stats]: turn on top (icon over two rows, distance, street), the chosen
     * [items] at the bottom. Nothing to show draws nothing.
     */
    fun drawPanel(canvas: Canvas, g: DashGuidance?, stats: LocalRecord?, items: Set<DashStat>) {
        if (stats == null) {
            g?.let { drawTurnColumn(canvas, it) }
            return
        }
        canvas.drawRect(0f, 0f, COLUMN_W, canvas.height.toFloat(), columnPaint)
        var y = 4f
        if (g != null) {
            // arrow + distance on one row, street under the arrow; ends above the dash's + button so long names stay clear
            drawTurnIcon(canvas, g.icon, 8f + TURN_ICON / 2, y + TURN_ICON / 2, scale = TURN_ICON / 48f)
            canvas.drawText(g.distance, TURN_TEXT_X, y + 29f, turnDistancePaint)
            val street = TextUtils.ellipsize(g.street, turnStreetPaint, COLUMN_W - 16f, TextUtils.TruncateAt.END)
            canvas.drawText(street, 0, street.length, 8f, y + 60f, turnStreetPaint)
            y += TURN_BLOCK_H
        }
        val shown = DashStat.entries.filter { it in items }
        y = maxOf(y, canvas.height - ETA_BAR_H - 4f - shown.size * STAT_ROW_H)
        for (item in shown) {
            val (icon, paint) = statIcons.getValue(item)
            canvas.withTranslation(8f, y + 4f) { withScale(STAT_ICON / 24f, STAT_ICON / 24f) { drawPath(icon, paint) } }
            canvas.drawText(statValue(item, stats) ?: NO_VALUE, 34f, y + 19f, statPaint)
            y += STAT_ROW_H
        }
    }

    /** Big arrow, distance, street, centred in the part of the column left of the zoom buttons. */
    private fun drawTurnColumn(canvas: Canvas, g: DashGuidance) {
        val h = canvas.height.toFloat()
        val cx = BUTTONS_X / 2
        canvas.drawRect(0f, 0f, COLUMN_W, h, columnPaint)
        drawTurnIcon(canvas, g.icon, cx, h * 0.27f, scale = 1.3f)
        canvas.drawText(g.distance, cx, h * 0.57f, distancePaint)
        val street = TextUtils.ellipsize(g.street, streetPaint, BUTTONS_X - 8f, TextUtils.TruncateAt.END)
        canvas.drawText(street, 0, street.length, cx, h * 0.68f, streetPaint)
    }

    /** Turn arrow (or arrive dot / roundabout ring) centred on ([cx], [cy]); scale 1 is about 48 px. */
    fun drawTurnIcon(canvas: Canvas, icon: Int, cx: Float, cy: Float, scale: Float) = canvas.withTranslation(cx, cy) {
        withScale(scale, scale) {
            when (icon) {
                in ARRIVE_ICONS -> drawCircle(0f, 0f, 14f, arrowPaint)
                else -> {
                    if (icon == ROUNDABOUT) drawCircle(0f, 0f, 24f, ringPaint)
                    withRotation(angleOf(icon)) { withScale(ARROW_SIZE, ARROW_SIZE) { drawPath(arrow, arrowPaint) } }
                }
            }
        }
    }

    private class StatIcon(val path: String, val color: Int)

    companion object {
        /** Stats column width in frame pixels: covers the dash's zoom buttons (about x 138–170 while guiding, bike photo). */
        const val COLUMN_W = 184f
        const val COLUMN_COLOR = 0xFF1E2433.toInt()

        /** Left edge of the dash's zoom buttons while guiding (bike photo). */
        private const val BUTTONS_X = 128f

        /** Bottom of the frame hidden under the dash's ETA / road bar while guiding (bike photo: about 38 px). */
        private const val ETA_BAR_H = 40f
        private const val ARROW_SIZE = 44f
        private const val TURN_ICON = 40f
        private const val TURN_TEXT_X = 8f + TURN_ICON + 8f
        private const val TURN_BLOCK_H = 72f
        private const val STAT_ROW_H = 26f
        private const val STAT_ICON = 18f
        private const val NO_VALUE = "--"
        private const val ROUNDABOUT = 14
        private val ARRIVE_ICONS = 0..2

        /** ARGB of [item]'s icon on the dash, so app-side samples can match it. */
        fun statIconColor(item: DashStat): Int = STAT_ICONS.getValue(item).color

        fun statValue(item: DashStat, v: LocalRecord): String? = when (item) {
            DashStat.AIR -> v.airCelsius?.let { "$it °C" }
            DashStat.COOLANT -> v.coolantCelsius?.let { "$it °C" }
            DashStat.FUEL -> v.fuelKmPerL?.let { "%.1f km/L".format(it) }
            DashStat.BATTERY -> v.batteryVolts?.let { "%.1f V".format(it) }
        }

        /** Turn icon code → arrow rotation, degrees clockwise from straight ahead. */
        private fun angleOf(icon: Int) = when (icon) {
            6 -> -45f
            7 -> 45f
            34 -> -90f
            35 -> 90f
            32 -> -135f
            33 -> 135f
            36, 37 -> 180f
            else -> 0f
        }

        // Material icon path data (24×24), each in its own colour so they stand out on the dark column.
        private val STAT_ICONS = mapOf(
            DashStat.AIR to StatIcon(
                "M14.5,17c0,1.65 -1.35,3 -3,3s-3,-1.35 -3,-3h2c0,0.55 0.45,1 1,1s1,-0.45 1,-1s-0.45,-1 -1,-1H2v-2h9.5" +
                    "C13.15,14 14.5,15.35 14.5,17zM19,6.5C19,4.57 17.43,3 15.5,3S12,4.57 12,6.5h2C14,5.67 14.67,5 15.5,5S17,5.67 17,6.5" +
                    "S16.33,8 15.5,8H2v2h13.5C17.43,10 19,8.43 19,6.5zM18.5,11H2v2h16.5c0.83,0 1.5,0.67 1.5,1.5S19.33,16 18.5,16v2" +
                    "c1.93,0 3.5,-1.57 3.5,-3.5S20.43,11 18.5,11z",
                0xFF4FC3F7.toInt(), // sky blue
            ),
            DashStat.COOLANT to StatIcon(
                "M15,13V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v8c-1.21,0.91 -2,2.37 -2,4 0,2.76 2.24,5 5,5s5,-2.24 5,-5" +
                    "c0,-1.63 -0.79,-3.09 -2,-4zM11,5c0,-0.55 0.45,-1 1,-1s1,0.45 1,1h-1v1h1v2h-1v1h1v2h-2V5z",
                0xFFFF7043.toInt(), // orange-red
            ),
            DashStat.FUEL to StatIcon(
                "M19.77,7.23l0.01,-0.01 -3.72,-3.72L15,4.56l2.11,2.11c-0.94,0.36 -1.61,1.26 -1.61,2.33 0,1.38 1.12,2.5 2.5,2.5" +
                    " 0.36,0 0.69,-0.08 1,-0.21v7.21c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V14" +
                    "c0,-1.1 -0.9,-2 -2,-2h-1V5c0,-1.1 -0.9,-2 -2,-2H6" +
                    "c-1.1,0 -2,0.9 -2,2v16h10v-7.5h1.5v5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V9c0,-0.69 -0.28,-1.32 -0.73,-1.77z" +
                    "M12,10H6V5h6v5zM18,10c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z",
                0xFF66BB6A.toInt(), // green
            ),
            DashStat.BATTERY to StatIcon(
                "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33" +
                    "C17,4.6 16.4,4 15.67,4z",
                0xFFFFCA28.toInt(), // amber
            ),
        )
    }
}
