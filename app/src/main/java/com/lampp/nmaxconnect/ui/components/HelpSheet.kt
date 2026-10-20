package com.lampp.nmaxconnect.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.NmaxConnectTheme
import kotlinx.coroutines.launch

/**
 * One walkthrough page. Caption n is numbered n and the illustration puts the same [HelpMarker] on the part it
 * talks about. Captions may use `&lt;b>` (escaped in XML) to bold the UI terms the user must find.
 */
class HelpPage(@StringRes val title: Int, val bullets: List<Int>, val illustration: @Composable () -> Unit)

/** Numbered dot tying a spot in an illustration to its caption. */
@Composable
fun HelpMarker(number: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        modifier = modifier.size(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelLarge) }
    }
}

/** [content] with [HelpMarker] [number] beside it (never on top: a corner badge hid short labels). */
@Composable
fun Marked(number: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f, fill = false)) { content() }
        HelpMarker(number)
    }
}

/** "Tap here": a ring that grows out of [content]'s outline and fades, over and over while the page shows. */
@Composable
fun TapHint(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress by rememberInfiniteTransition(label = "tapHint").animateFloat(0f, 1f, AppMotion.pulse(), label = "ring")
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier.drawBehind {
            val grow = 12.dp.toPx() * progress
            drawRoundRect(
                color = color.copy(alpha = 0.5f * (1 - progress)),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius(size.height / 2 + grow),
                style = Stroke(width = 3.dp.toPx()),
            )
        },
    ) { content() }
}

class HelpTopic(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector, val pages: List<HelpPage>)

/** A screen's own `?` passes one section without a title; Settings → Help passes one per screen. */
class HelpSection(@StringRes val title: Int?, val topics: List<HelpTopic>)

/** Quick Share-style help: a tips list; a tip opens a paged walkthrough in the same sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(sections: List<HelpSection>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val topics = sections.flatMap { it.topics }
    var openTopic by rememberSaveable { mutableStateOf<Int?>(null) }
    // Outside AnimatedContent so the list keeps its place after a walkthrough (Settings lists every screen).
    val tipsScroll = rememberScrollState()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AnimatedContent(
            targetState = openTopic,
            transitionSpec = { AppMotion.sharedAxisX(forward = targetState != null) },
            label = "help",
        ) { index ->
            if (index == null) {
                HelpTips(sections, tipsScroll, onOpen = { openTopic = topics.indexOf(it) }, onDone = close)
            } else {
                BackHandler { openTopic = null }
                HelpWalkthrough(topics[index], onClose = { openTopic = null }) // Done also returns to the list
            }
        }
    }
}

@Composable
private fun HelpTips(sections: List<HelpSection>, scroll: ScrollState, onOpen: (HelpTopic) -> Unit, onDone: () -> Unit) {
    Column(Modifier.verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(
            stringResource(R.string.help_tips),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
        )
        sections.forEach { section ->
            section.title?.let { SectionHeader(stringResource(it)) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                section.topics.forEach { TipCard(it, onClick = { onOpen(it) }) }
            }
        }
        Button(onClick = onDone, modifier = Modifier.align(Alignment.End).padding(top = 24.dp)) {
            Text(stringResource(R.string.action_done))
        }
    }
}

@Composable
private fun TipCard(topic: HelpTopic, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(topic.icon, contentDescription = null) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(topic.title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(topic.summary), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun HelpWalkthrough(topic: HelpTopic, onClose: () -> Unit) {
    val pager = rememberPagerState { topic.pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == topic.pages.lastIndex
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        // Pages differ in height: animate only the pager so the indicator and buttons ride its edge instead of jumping.
        HorizontalPager(
            state = pager,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.animateContentSize(AppMotion.spatial()),
        ) { HelpPageContent(topic.pages[it]) }
        if (topic.pages.size > 1) {
            PageIndicator(topic.pages.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(visible = !last, enter = AppMotion.fadeThroughEnter, exit = AppMotion.fadeThroughExit) {
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                if (last) {
                    onClose()
                } else {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = AppMotion.spatial()) }
                }
            }) {
                AnimatedContent(targetState = last, transitionSpec = { AppMotion.fadeThrough() }, label = "nextDone") {
                    Text(stringResource(if (it) R.string.action_done else R.string.action_next))
                }
            }
        }
    }
}

@Composable
private fun HelpPageContent(page: HelpPage) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            // Roomy, since the picture now carries most of the page; markers sit half outside their target.
            Box(Modifier.heightIn(min = 200.dp).padding(24.dp), contentAlignment = Alignment.Center) { page.illustration() }
        }
        Column(Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            page.bullets.forEachIndexed { index, bullet ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HelpMarker(index + 1)
                    Spacer(Modifier.width(12.dp))
                    Text(AnnotatedString.fromHtml(stringResource(bullet)), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.help_page_of, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, AppMotion.spatial(), label = "dotWidth")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                AppMotion.effects(),
                label = "dotColor",
            )
            Box(Modifier.size(width, 8.dp).background(color, CircleShape))
        }
    }
}

private val previewTopic = HelpTopic(
    title = R.string.help_home_connect,
    summary = R.string.help_home_connect_summary,
    icon = Icons.Filled.BluetoothConnected,
    pages = listOf(
        HelpPage(R.string.help_home_connect, listOf(R.string.help_home_connect_1, R.string.help_home_connect_2)) {
            Text(stringResource(R.string.action_connect))
        },
        HelpPage(R.string.help_home_background, listOf(R.string.help_home_background_1)) {},
    ),
)

@PreviewLightDark
@Composable
private fun HelpTipsPreview() {
    NmaxConnectTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            HelpTips(listOf(HelpSection(null, listOf(previewTopic, previewTopic))), rememberScrollState(), onOpen = {}, onDone = {})
        }
    }
}

@PreviewLightDark
@Composable
private fun HelpWalkthroughPreview() {
    NmaxConnectTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            HelpWalkthrough(previewTopic, onClose = {})
        }
    }
}
