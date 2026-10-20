package com.lampp.nmaxconnect.feature.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.lampp.nmaxconnect.core.protocol.tft.DisplayModel
import java.io.ByteArrayOutputStream

/**
 * Frame for the first real-bike test: frame number, a moving bar (shows dropped/stuck frames)
 * and a 1-px border (shows whether the dash crops the edges at this resolution).
 * Colors are image content sent to the bike, not app theme.
 */
class TestPattern(private val display: DisplayModel) {
    private val bitmap = createBitmap(display.width, display.height)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val out = ByteArrayOutputStream(64 * 1024)

    fun render(frame: Int, quality: Int = 80): ByteArray {
        val w = display.width.toFloat()
        val h = display.height.toFloat()
        canvas.drawColor(Color.rgb(16, 32, 48))

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 160, 255)
        val barX = (frame * 8 % display.width).toFloat()
        canvas.drawRect(barX, h - 24f, barX + 48f, h - 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.WHITE
        canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = 40f
        canvas.drawText("NmaxConnect #$frame", 24f, 64f, paint)
        paint.textSize = 24f
        canvas.drawText("${display.width}x${display.height} ${display.name}", 24f, 104f, paint)

        out.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
