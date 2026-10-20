package com.lampp.nmaxconnect.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Helper for Android 15/16 & M3 Expressive Contained Grouped Lists (Pixel Settings style).
 * Wrap items in a Column with Arrangement.spacedBy(2.dp) and apply this shape per index.
 */
object GroupedListShapes {
    private val OuterCorner = 24.dp
    private val InnerCorner = 4.dp

    val SingleItemShape: Shape = RoundedCornerShape(OuterCorner)

    val TopItemShape: Shape = RoundedCornerShape(
        topStart = OuterCorner,
        topEnd = OuterCorner,
        bottomStart = InnerCorner,
        bottomEnd = InnerCorner
    )

    val MiddleItemShape: Shape = RoundedCornerShape(InnerCorner)

    val BottomItemShape: Shape = RoundedCornerShape(
        topStart = InnerCorner,
        topEnd = InnerCorner,
        bottomStart = OuterCorner,
        bottomEnd = OuterCorner
    )

    fun forIndex(index: Int, totalCount: Int): Shape = when {
        totalCount <= 1 -> SingleItemShape
        index == 0 -> TopItemShape
        index == totalCount - 1 -> BottomItemShape
        else -> MiddleItemShape
    }
}
