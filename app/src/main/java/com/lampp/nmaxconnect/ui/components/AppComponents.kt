package com.lampp.nmaxconnect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lampp.nmaxconnect.R
import com.lampp.nmaxconnect.core.bike.BikeLinkState
import com.lampp.nmaxconnect.core.bike.BondedBike
import com.lampp.nmaxconnect.core.bike.ccu.CcuLinkState
import com.lampp.nmaxconnect.core.bike.ccu.CcuStatus
import com.lampp.nmaxconnect.core.protocol.ccu.CcuUuids
import com.lampp.nmaxconnect.ui.theme.AppMotion
import com.lampp.nmaxconnect.ui.theme.GroupedListShapes

/** The frame every screen uses: collapsing large title (none when [title] is null), edge-to-edge list, 16 dp gutter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String?,
    modifier: Modifier = Modifier,
    navigateUp: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    onHelp: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    // A list that fits the screen mustn't collapse the title: that just leaves a blank band above the tab bar.
    // Once collapsed it may always expand back, even if the taller viewport now fits the list.
    val barState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = barState,
        canScroll = { listState.canScrollForward || listState.canScrollBackward || barState.collapsedFraction > 0f },
    )
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        topBar = {
            // Measured from Google's Quick Share: expanded title 36 sp Medium (displaySmall size), collapsed titleLarge.
            // The large bar reads headlineMedium for the expanded title, so map it here only.
            if (title != null) {
                val expandedTitle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Medium)
                MaterialTheme(typography = MaterialTheme.typography.copy(headlineMedium = expandedTitle)) {
                    LargeTopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            if (navigateUp != null) {
                                FilledTonalIconButton(
                                    onClick = navigateUp,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                                }
                            }
                        },
                        actions = {
                            if (onHelp != null) {
                                IconButton(onClick = onHelp) {
                                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.help))
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            content = content,
        )
    }
}

fun LazyListScope.sectionHeader(text: Int) = item(key = "header-$text") { SectionHeader(stringResource(text), Modifier.animateItem()) }

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium, // 14 sp, as Google's section labels
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** Pixel-style grouped list: tonal tiles 2 dp apart, rounded outer corners; grows/shrinks smoothly as rows come and go. */
@Composable
fun GroupedItems(count: Int, modifier: Modifier = Modifier, item: @Composable (index: Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.fillMaxWidth().animateContentSize(AppMotion.spatial())) {
        repeat(count) { index ->
            Surface(
                shape = GroupedListShapes.forIndex(index, count),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) { item(index) }
        }
    }
}

@Composable
fun GroupedRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, AppMotion.effects(), label = "enabled")
    ListItem(
        headlineContent = { Text(headline, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) },
        supportingContent = supporting?.let {
            { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
        },
        leadingContent = leading?.let { { Box(Modifier.alpha(alpha)) { it() } } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
            .heightIn(min = 56.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
fun LinkStatusChip(state: BikeLinkState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, label) = when (state) {
        BikeLinkState.Idle -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.string.link_idle)
        is BikeLinkState.Connecting -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.string.link_connecting)
        is BikeLinkState.Retrying -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.string.link_retrying)
        BikeLinkState.Handshaking -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, R.string.link_authenticating)
        is BikeLinkState.Ready ->
            if (state.streaming) Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, R.string.link_streaming)
            else Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.string.link_connected)
        BikeLinkState.MissingPermission -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.string.link_missing_permission)
    }
    StatusChip(container, content, label, modifier)
}

/** Same palette as the TFT chip; the CCU has no "streaming" step but can refuse our login. */
@Composable
fun LinkStatusChip(status: CcuStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, label) = when (status) {
        CcuStatus.Idle -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.string.link_idle)
        is CcuStatus.Connecting -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.string.link_connecting)
        is CcuStatus.Retrying -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.string.link_retrying)
        is CcuStatus.Linked -> when (status.state) {
            is CcuLinkState.Ready -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.string.link_connected)
            is CcuLinkState.Authenticating -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, R.string.link_authenticating)
            else -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, R.string.link_connecting)
        }
        CcuStatus.MissingPermission -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.string.link_missing_permission)
        CcuStatus.Rejected -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.string.link_rejected)
    }
    StatusChip(container, content, label, modifier)
}

/** Color and label change with the link: colors cross-fade, the label fades through, the chip resizes to it. */
@Composable
private fun StatusChip(container: Color, content: Color, label: Int, modifier: Modifier) {
    val containerColor by animateColorAsState(container, AppMotion.effects(), label = "chipContainer")
    val contentColor by animateColorAsState(content, AppMotion.effects(), label = "chipContent")
    Surface(shape = MaterialTheme.shapes.small, color = containerColor, contentColor = contentColor, modifier = modifier) {
        AnimatedContent(targetState = label, transitionSpec = { AppMotion.fadeThrough() }, label = "chipLabel") {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center, // matters only when the caller gives it a fixed height
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** The CCU ID from its Bluetooth name (`YCCU_00060301011415` -> `00060301011415`); other names as they are. */
fun bikeId(bike: BondedBike): String = CcuUuids.ccuIdFromBleName(bike.name) ?: bike.name

/** What the app calls the bike: the rider's nickname, else its ID. */
fun bikeLabel(bike: BondedBike): String = bike.nickname ?: bikeId(bike)

/** Persistent tip or warning; tonal, no shadow. */
@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (actionLabel != null) 8.dp else 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(actionLabel) }
            }
        }
    }
}
