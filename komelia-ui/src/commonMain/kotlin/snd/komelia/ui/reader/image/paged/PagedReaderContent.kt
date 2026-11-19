package snd.komelia.ui.reader.image.paged

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyUp
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import snd.komelia.settings.model.PageDisplayLayout
import snd.komelia.settings.model.PageDisplayLayout.DOUBLE_PAGES
import snd.komelia.settings.model.PageDisplayLayout.DOUBLE_PAGES_NO_COVER
import snd.komelia.settings.model.PageDisplayLayout.SINGLE_PAGE
import snd.komelia.settings.model.PagedReadingDirection
import snd.komelia.settings.model.PagedReadingDirection.LEFT_TO_RIGHT
import snd.komelia.settings.model.PagedReadingDirection.RIGHT_TO_LEFT
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komelia.ui.reader.image.common.PagedReaderHelpDialog
import snd.komelia.ui.reader.image.common.ReaderControlsOverlay
import snd.komelia.ui.reader.image.common.ReaderImageContent
import snd.komelia.ui.reader.image.common.ScalableContainer
import snd.komelia.ui.reader.image.paged.PagedReaderState.Page
import snd.komelia.ui.reader.image.paged.PagedReaderState.PagesLoadJob
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage.BookEnd
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage.BookStart
import kotlin.math.abs

@Composable
fun BoxScope.PagedReaderContent(
    showHelpDialog: Boolean,
    onShowHelpDialogChange: (Boolean) -> Unit,
    showSettingsMenu: Boolean,
    onShowSettingsMenuChange: (Boolean) -> Unit,
    screenScaleState: ScreenScaleState,
    pagedReaderState: PagedReaderState,
    volumeKeysNavigation: Boolean
) {
    if (showHelpDialog) {
        PagedReaderHelpDialog(onDismissRequest = { onShowHelpDialogChange(false) })
    }

    val readingDirection = pagedReaderState.readingDirection.collectAsState().value
    val layoutDirection = when (readingDirection) {
        LEFT_TO_RIGHT -> LayoutDirection.Ltr
        RIGHT_TO_LEFT -> LayoutDirection.Rtl
    }
    val pages = pagedReaderState.currentSpread.collectAsState().value.pages
    val layout = pagedReaderState.layout.collectAsState().value
    val layoutOffset = pagedReaderState.layoutOffset.collectAsState().value

    val currentContainerSize = screenScaleState.areaSize.collectAsState().value
    val transitionPage = pagedReaderState.transitionPage.collectAsState().value

    // neighbor pages shown next to the current one while swiping
    val swipeOffset = remember { mutableFloatStateOf(0f) }
    var neighbors by remember { mutableStateOf(NeighborSpreads()) }
    LaunchedEffect(pages, transitionPage) {
        val newNeighbors =
            if (transitionPage != null) NeighborSpreads(currentPages = pages)
            else coroutineScope {
                val previous = async { pagedReaderState.awaitAdjacentSpread(-1) }
                val next = async { pagedReaderState.awaitAdjacentSpread(1) }
                NeighborSpreads(currentPages = pages, previous = previous.await(), next = next.await())
            }
        // don't replace the neighbor while it's the one sliding into the center at the end of a swipe
        snapshotFlow { isSwipeLanding(swipeOffset.floatValue, currentContainerSize.width) }.first { !it }
        neighbors = newNeighbors
    }

    val coroutineScope = rememberCoroutineScope()
    ReaderControlsOverlay(
        readingDirection = layoutDirection,
        onNexPageClick = pagedReaderState::nextPage,
        onPrevPageClick = pagedReaderState::previousPage,
        contentAreaSize = currentContainerSize,
        isSettingsMenuOpen = showSettingsMenu,
        onSettingsMenuToggle = { onShowSettingsMenuChange(!showSettingsMenu) },
        modifier = Modifier.onKeyEvent { event ->
            pagedReaderOnKeyEvents(
                event = event,
                readingDirection = readingDirection,
                layoutOffset = layoutOffset,
                onReadingDirectionChange = pagedReaderState::onReadingDirectionChange,
                onScaleTypeCycle = pagedReaderState::onScaleTypeCycle,
                onLayoutCycle = pagedReaderState::onLayoutCycle,
                onChangeLayoutOffset = pagedReaderState::onLayoutOffsetChange,
                onPageChange = pagedReaderState::onPageChange,
                onMoveToLastPage = pagedReaderState::moveToLastPage,
                onMoveToNextPage = { coroutineScope.launch { pagedReaderState.nextPage() } },
                onMoveToPrevPage = { coroutineScope.launch { pagedReaderState.previousPage() } },
                volumeKeysNavigation = volumeKeysNavigation
            )
        }
    ) {
        val offset = swipeOffset.floatValue
        val areaWidth = currentContainerSize.width
        // only use neighbors computed for the current page, except while the swiped-to neighbor lands in the center
        val validNeighbors = neighbors.currentPages == pages || isSwipeLanding(offset, areaWidth)
        if (offset != 0f && validNeighbors) {
            // dragging to the left reveals the page on the right side of the current one and vice versa
            val revealsRightSide = offset < 0
            val neighbor = when (readingDirection) {
                LEFT_TO_RIGHT -> if (revealsRightSide) neighbors.next else neighbors.previous
                RIGHT_TO_LEFT -> if (revealsRightSide) neighbors.previous else neighbors.next
            }
            if (neighbor != null) {
                NeighborSpread(
                    loaded = neighbor,
                    layout = layout,
                    readingDirection = readingDirection,
                    translationX = if (revealsRightSide) offset + areaWidth else offset - areaWidth
                )
            }
        }

        // swipe to change page: the finger moves towards the side the previous page comes from
        ScalableContainer(
            scaleState = screenScaleState,
            swipeOffsetState = swipeOffset,
            onSwipeLeft = when (readingDirection) {
                LEFT_TO_RIGHT -> pagedReaderState::nextPageAndAwaitDisplay
                RIGHT_TO_LEFT -> pagedReaderState::previousPageAndAwaitDisplay
            },
            onSwipeRight = when (readingDirection) {
                LEFT_TO_RIGHT -> pagedReaderState::previousPageAndAwaitDisplay
                RIGHT_TO_LEFT -> pagedReaderState::nextPageAndAwaitDisplay
            },
        ) {
            if (transitionPage != null) {
                TransitionPage(transitionPage)
            } else {
                SpreadContent(pages, layout, readingDirection)
            }
        }
    }
}

private data class NeighborSpreads(
    // pages of the current spread these neighbors belong to
    val currentPages: List<Page>? = null,
    val previous: PagesLoadJob? = null,
    val next: PagesLoadJob? = null,
)

// the page has been slid fully out of the screen and the neighbor is in the center, waiting for the page change
private fun isSwipeLanding(swipeOffset: Float, areaWidth: Int) =
    areaWidth > 0 && abs(swipeOffset) >= areaWidth * 0.99f

@Composable
private fun SpreadContent(
    pages: List<Page>,
    layout: PageDisplayLayout,
    readingDirection: PagedReadingDirection,
) {
    when (layout) {
        SINGLE_PAGE -> pages.firstOrNull()?.let { SinglePageLayout(it) }
        DOUBLE_PAGES, DOUBLE_PAGES_NO_COVER -> DoublePageLayout(pages, readingDirection)
    }
}

/**
 * Draws a preloaded neighbor spread with its own scale, shifted sideways by [translationX].
 * Mirrors the layers of ScalableContainer so it looks exactly like the page will once it becomes the current one.
 */
@Composable
private fun NeighborSpread(
    loaded: PagesLoadJob,
    layout: PageDisplayLayout,
    readingDirection: PagedReadingDirection,
    translationX: Float,
) {
    val transforms = loaded.scale.transformation.value
    Box(
        Modifier.graphicsLayer {
            this.translationX = translationX
            clip = true
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = transforms.scale
                scaleY = transforms.scale
                this.translationX = transforms.offset.x
                translationY = transforms.offset.y
            }
        ) {
            SpreadContent(loaded.spread.pages, layout, readingDirection)
        }
    }
}


@Composable
private fun TransitionPage(page: TransitionPage) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (page) {
            is BookEnd -> {
                Column {
                    Text("Finished:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        page.currentBook.metadata.title,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(Modifier.size(50.dp))

                if (page.nextBook != null) {
                    Column {
                        Text("Next:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            page.nextBook.metadata.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                } else {
                    Text("There's no next book")
                }

            }

            is BookStart -> {
                if (page.previousBook != null) {
                    Column {
                        Text("Previous:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            page.previousBook.metadata.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                } else {
                    Text("There's no previous book")

                }
                Spacer(Modifier.size(50.dp))
                Column {
                    Text("Current:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        page.currentBook.metadata.title,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

            }
        }
    }
}

@Composable
private fun SinglePageLayout(page: Page) {
    Layout(content = { ReaderImageContent(page.imageResult) }) { measurable, constraints ->
        val placeable = measurable.first().measure(constraints)
        val startPadding = (constraints.maxWidth - placeable.width) / 2
        val topPadding = ((constraints.maxHeight - placeable.height) / 2).coerceAtLeast(0)
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeRelative(startPadding, topPadding)
        }
    }
}

@Composable
private fun DoublePageLayout(
    pages: List<Page>,
    readingDirection: PagedReadingDirection,
) {
    Layout(content = {
        when (pages.size) {
            0 -> {}
            1 -> ReaderImageContent(pages.first().imageResult)
            2 -> {
                ReaderImageContent(pages[0].imageResult)
                ReaderImageContent(pages[1].imageResult)
            }

            else -> error("can't display more than 2 images")
        }
    }) { measurables, constraints ->
        val measured = measurables
            .map { it.measure(constraints.copy(maxWidth = constraints.maxWidth / measurables.size)) }
            .let {
                when (readingDirection) {
                    LEFT_TO_RIGHT -> it
                    RIGHT_TO_LEFT -> it.reversed()
                }
            }
        val startPadding: Int
        if (measured.size == 1 && !pages.first().metadata.isLandscape()) {
            startPadding = when (readingDirection) {
                LEFT_TO_RIGHT -> (constraints.maxWidth - (measured.first().width * 2)) / 2
                RIGHT_TO_LEFT -> ((constraints.maxWidth - (measured.first().width * 2)) / 2) + measured.first().width
            }
        } else {
            val totalWidth = measured.fold(0) { acc, placeable -> acc + placeable.width }
            startPadding = (constraints.maxWidth - totalWidth) / 2
        }

        var widthTaken = startPadding
        layout(constraints.maxWidth, constraints.maxHeight) {
            measured.forEach {
                val topPadding = ((constraints.maxHeight - it.height) / 2).coerceAtLeast(0)
                it.placeRelative(widthTaken, topPadding)
                widthTaken += it.width
            }
        }
    }
}

private fun pagedReaderOnKeyEvents(
    event: KeyEvent,
    readingDirection: PagedReadingDirection,
    layoutOffset: Boolean,
    onReadingDirectionChange: (PagedReadingDirection) -> Unit,
    onScaleTypeCycle: () -> Unit,
    onLayoutCycle: () -> Unit,
    onChangeLayoutOffset: (Boolean) -> Unit,
    onPageChange: (Int) -> Unit,
    onMoveToLastPage: () -> Unit,
    onMoveToNextPage: () -> Unit,
    onMoveToPrevPage: () -> Unit,
    volumeKeysNavigation: Boolean,
): Boolean {
    if (event.type != KeyUp) {
        return volumeKeysNavigation && (event.key == Key.VolumeUp || event.key == Key.VolumeDown)
    }

    val previousPage = {
        if (readingDirection == LEFT_TO_RIGHT) onMoveToPrevPage()
        else onMoveToNextPage()
    }
    val nextPage = {
        if (readingDirection == LEFT_TO_RIGHT) onMoveToNextPage()
        else onMoveToPrevPage()
    }

    var consumed = true
    when (event.key) {
        Key.DirectionLeft -> {
            previousPage()
            if (event.isAltPressed) consumed = false
        }

        Key.DirectionRight -> nextPage()
        Key.MoveHome -> onPageChange(0)
        Key.MoveEnd -> onMoveToLastPage()
        Key.L -> onReadingDirectionChange(LEFT_TO_RIGHT)
        Key.R -> onReadingDirectionChange(RIGHT_TO_LEFT)
        Key.C -> if (event.isAltPressed) consumed = false else onScaleTypeCycle()
        Key.D -> onLayoutCycle()
        Key.O -> onChangeLayoutOffset(!layoutOffset)
        Key.VolumeUp -> if (volumeKeysNavigation) previousPage() else consumed = false
        Key.VolumeDown -> if (volumeKeysNavigation) nextPage() else consumed = false
        else -> consumed = false
    }
    return consumed
}
