package snd.komelia.ui.reader.image.common

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.Orientation.Horizontal
import androidx.compose.foundation.gestures.Orientation.Vertical
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import snd.komelia.ui.LocalKeyEvents
import snd.komelia.ui.LocalPlatform
import snd.komelia.ui.platform.PlatformType.WEB_KOMF
import snd.komelia.ui.reader.image.ScreenScaleState
import kotlin.math.abs
import kotlin.math.sign

/**
 * @param onSwipeLeft called when the user swipes the page to the left (finger moves right to left).
 * @param onSwipeRight called when the user swipes the page to the right (finger moves left to right).
 * When either is set, horizontal drags that can't pan the page any further move the whole page with the finger
 * and change the page on release, similar to a view pager.
 * The callbacks suspend until the new page is ready to be shown and return false if the page didn't change.
 * @param swipeOffsetState how far (in screen pixels) the page is dragged sideways; hoisted so the caller can draw the
 * neighbor page next to it while swiping.
 */
@Composable
fun ScalableContainer(
    scaleState: ScreenScaleState,
    onSwipeLeft: (suspend () -> Boolean)? = null,
    onSwipeRight: (suspend () -> Boolean)? = null,
    swipeOffsetState: MutableFloatState = remember { mutableFloatStateOf(0f) },
    content: @Composable BoxScope.() -> Unit,
) {
    val platform = LocalPlatform.current
    var isCtrlPressed by remember { mutableStateOf(false) }
    val keyEvents: SharedFlow<KeyEvent> = LocalKeyEvents.current
    LaunchedEffect(Unit) {
        keyEvents.collect { event ->
            isCtrlPressed =
                if (platform == WEB_KOMF) event.isShiftPressed
                else event.isCtrlPressed
        }
    }

    val areaSize = scaleState.areaSize.collectAsState().value
    val currentTransforms = scaleState.transformation.collectAsState().value
    val areaCenter = remember(areaSize) { Offset(areaSize.width / 2f, areaSize.height / 2f) }

    val flingScope = rememberCoroutineScope()
    val flingSpec = rememberSplineBasedDecay<Offset>()
    var flingInProgress by remember { mutableStateOf(false) }
    val scrollOrientation = scaleState.scrollOrientation.collectAsState().value ?: Vertical
    val scrollConfig = remember { platformScrollConfig() }
    val density = LocalDensity.current

    // horizontal page swipe: how far (in screen pixels) the page is currently dragged sideways
    val swipeEnabled = onSwipeLeft != null || onSwipeRight != null
    var swipeOffset by swipeOffsetState
    val swipeScope = rememberCoroutineScope()
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val minFlingDistance = with(density) { 48.dp.toPx() }

    // outer layer: moves the page sideways while swiping, clipped to the screen so a zoomed page
    // doesn't cover the neighbor page drawn next to it
    Box(
        Modifier.graphicsLayer {
            translationX = swipeOffset
            clip = swipeOffset != 0f
        }
    ) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                scaleX = currentTransforms.scale
                scaleY = currentTransforms.scale
                translationX = currentTransforms.offset.x
                translationY = currentTransforms.offset.y
            }
            .pointerInput(swipeEnabled, areaSize) {
                if (!swipeEnabled) return@pointerInput
                val velocityTracker = VelocityTracker()
                var springBackJob: Job? = null
                // decides what happens with the swiped page once every finger is lifted
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // grab the page again if it was still going back to its place
                    springBackJob?.cancel()
                    velocityTracker.resetTracking()
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) multiTouch = true
                        event.changes.firstOrNull()?.let { velocityTracker.addPointerInputChange(it) }
                    } while (event.changes.any { it.pressed })

                    val offset = swipeOffset
                    if (offset == 0f) return@awaitEachGesture
                    val velocityX = velocityTracker.calculateVelocity().x * scaleState.transformation.value.scale
                    val isFling = abs(velocityX) > SWIPE_FLING_VELOCITY &&
                            sign(velocityX) == sign(offset) &&
                            abs(offset) > minFlingDistance
                    val changePage = !multiTouch && (abs(offset) > areaSize.width * SWIPE_DISTANCE_THRESHOLD || isFling)

                    if (changePage) {
                        swipeScope.launch {
                            // slide the current page out of the screen, then show the new one
                            animate(offset, sign(offset) * areaSize.width, animationSpec = tween(150)) { value, _ ->
                                swipeOffset = value
                            }
                            // keep the old page off screen until the new one is ready, so it doesn't flash back
                            val pageChanged =
                                if (offset < 0) currentOnSwipeLeft?.invoke() ?: false
                                else currentOnSwipeRight?.invoke() ?: false
                            if (pageChanged) {
                                withFrameNanos { } // let the new page be composed before bringing it back
                                swipeOffset = 0f
                            } else {
                                animate(swipeOffset, 0f, animationSpec = spring()) { value, _ -> swipeOffset = value }
                            }
                        }
                    } else {
                        springBackJob = swipeScope.launch {
                            animate(offset, 0f, animationSpec = spring()) { value, _ -> swipeOffset = value }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        flingScope.launch {
                            flingInProgress = true
                            scaleState.performFling(flingSpec)
                            flingInProgress = false
                        }
                    },
                    onDrag = { _, _ -> }
                )

            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (flingInProgress) {
                        flingScope.coroutineContext.cancelChildren()
                        flingInProgress = false
                        down.consume()
                    }
                }

            }
            .pointerInput(areaSize) {
                detectTransformGestures { event, centroid, pan, zoom, _ ->
                    if (zoom != 1.0f) {
                        scaleState.multiplyZoom(zoom, centroid - areaCenter)
                    } else if (swipeEnabled && event.count { it.pressed } == 1) {
                        // pan the (zoomed) page first; whatever horizontal movement is left drags the whole page
                        val scale = scaleState.transformation.value.scale
                        var dx = pan.x * scale
                        if (swipeOffset != 0f) {
                            val newSwipeOffset = swipeOffset + dx
                            if (newSwipeOffset * swipeOffset > 0f) {
                                swipeOffset = newSwipeOffset
                                dx = 0f
                            } else {
                                // the page went back past its resting position: keep panning with the rest
                                swipeOffset = 0f
                                dx = newSwipeOffset
                            }
                        }
                        val offsetBefore = scaleState.transformation.value.offset.x
                        scaleState.addPan(event, Offset(dx / scale, pan.y))
                        val leftover = dx - (scaleState.transformation.value.offset.x - offsetBefore)
                        if (abs(pan.x) >= abs(pan.y)) swipeOffset += leftover
                    } else {
                        scaleState.addPan(event, pan)
                    }
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val scrollDelta = with(density) { with(scrollConfig) { calculateMouseWheelScroll(event, size) } }
                if (isCtrlPressed) {
                    val centroid = event.changes[0].position
                    val delta = if (scrollDelta.y != 0f) scrollDelta.y else scrollDelta.x
                    val zoom = if (delta >= 0) 0.2f else -0.2f
                    scaleState.addZoom(zoom, centroid - areaCenter)
                } else {
                    val maxDelta = if (abs(scrollDelta.y) > abs(scrollDelta.x)) scrollDelta.y else scrollDelta.x
                    val pan = (if (scaleState.scrollReversed.value) -maxDelta else maxDelta)
                    when (scrollOrientation) {
                        Vertical -> scaleState.addPan(Offset(0f, pan))
                        Horizontal -> scaleState.addPan(Offset(pan, 0f))
                    }

                }
            }
    ) {
        content()
    }
    }
}

// fraction of the screen width the page has to be dragged to change page
private const val SWIPE_DISTANCE_THRESHOLD = 0.25f

// pixels per second; a faster horizontal flick changes page even if the page wasn't dragged far
private const val SWIPE_FLING_VELOCITY = 1000f

private fun Modifier.onPointerEvent(
    eventType: PointerEventType,
    pass: PointerEventPass = PointerEventPass.Main,
    onEvent: AwaitPointerEventScope.(event: PointerEvent) -> Unit
): Modifier = composed {
    val currentEventType by rememberUpdatedState(eventType)
    val currentOnEvent by rememberUpdatedState(onEvent)
    pointerInput(pass) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(pass)
                if (event.type == currentEventType) {
                    currentOnEvent(event)
                }
            }
        }
    }
}
