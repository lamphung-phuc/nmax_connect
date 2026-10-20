package com.lampp.nmaxconnect.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * M3 motion: spring tokens from material3's StandardMotionTokens (MaterialTheme.motionScheme is still
 * internal in 1.4.0) and the M3 transition patterns built on them. Spatial = position/size, may settle
 * softly; effects = color/alpha, never overshoots.
 */
object AppMotion {
    private const val FADE_THROUGH_OUT_MS = 90
    private const val FADE_THROUGH_IN_MS = 210
    private const val PULSE_MS = 1400

    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> effectsFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

    /** Help "tap here" ring: grows and fades out, then starts over. The only looping motion in the app. */
    fun <T> pulse(): InfiniteRepeatableSpec<T> = infiniteRepeatable(tween(PULSE_MS, easing = LinearOutSlowInEasing), RepeatMode.Restart)

    private val offset = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntOffset(1, 1))
    private val offsetFast = spring(dampingRatio = 0.9f, stiffness = 1400f, visibilityThreshold = IntOffset(1, 1))
    private val size = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntSize(1, 1))

    /**
     * Fade through: unrelated content replaces content (top-level tabs, state swaps). The outgoing fades out first,
     * then the incoming fades in and grows from 92 % (M3 timings). Tweens, not springs: a spring can't wait, and both
     * screens half-visible at once looked like a double exposure.
     */
    val fadeThroughEnter: EnterTransition =
        fadeIn(tween(FADE_THROUGH_IN_MS, FADE_THROUGH_OUT_MS, LinearOutSlowInEasing)) +
            scaleIn(tween(FADE_THROUGH_IN_MS, FADE_THROUGH_OUT_MS, LinearOutSlowInEasing), initialScale = 0.92f)
    val fadeThroughExit: ExitTransition = fadeOut(tween(FADE_THROUGH_OUT_MS, easing = FastOutLinearInEasing))

    /** Shared axis X: forward/backward between levels or steps. Slides a tenth of the width with a fade. */
    val forwardEnter: EnterTransition = slideInHorizontally(offset) { it / 10 } + fadeIn(effects())
    val forwardExit: ExitTransition = slideOutHorizontally(offset) { -it / 10 } + fadeOut(effectsFast())
    val backwardEnter: EnterTransition = slideInHorizontally(offset) { -it / 10 } + fadeIn(effects())
    val backwardExit: ExitTransition = slideOutHorizontally(offset) { it / 10 } + fadeOut(effectsFast())

    /** Container size follows the content with the spatial spring; the old content isn't clipped while it fades. */
    val sizeTransform = SizeTransform(clip = false) { _, _ -> size }

    fun fadeThrough() = ContentTransform(fadeThroughEnter, fadeThroughExit, sizeTransform = sizeTransform)

    fun sharedAxisX(forward: Boolean) =
        if (forward) ContentTransform(forwardEnter, forwardExit, sizeTransform = sizeTransform)
        else ContentTransform(backwardEnter, backwardExit, sizeTransform = sizeTransform)

    /** Shared axis Y, fast: the next item in a sequence pushes the current one up (turn banner while riding). */
    fun sharedAxisYFast() = ContentTransform(
        slideInVertically(offsetFast) { it / 4 } + fadeIn(effectsFast()),
        slideOutVertically(offsetFast) { -it / 4 } + fadeOut(effectsFast()),
        sizeTransform = sizeTransform,
    )

    /** Enter/exit for something arriving from the bottom edge (bars, panels). */
    val enterFromBottom: EnterTransition = slideInVertically(offset) { it } + fadeIn(effects())
    val exitToBottom: ExitTransition = slideOutVertically(offset) { it } + fadeOut(effectsFast())
}
