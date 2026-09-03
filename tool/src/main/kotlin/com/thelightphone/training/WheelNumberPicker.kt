package com.thelightphone.training

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlin.math.abs

// The wheel fills whatever space its caller gives it (see BoxWithConstraints below); this is
// just how many rows are visible at once within that space.
private const val VISIBLE_ROWS = 3

// How far (in dp) to nudge non-selected rows toward the centered row, purely visually -- this is
// a rendering-only offset applied after layout, so it doesn't affect scroll position, snapping,
// or the centered-index calculation above.
private const val NEIGHBOR_NUDGE_DP = 10

// When wrapping, the range is repeated this many times into one long list, centered on the
// current value, so spinning past either end of a cycle just continues into the next one
// instead of stopping. Not truly infinite, but 401 cycles (~200 in either direction) is far more
// than anyone will ever spin a minutes/seconds wheel in one motion.
private const val WRAP_CYCLE_COUNT = 401

/**
 * A vertical scroll wheel for picking a value out of [range]: drag or fling to spin it, and it
 * snaps to the nearest value, reporting the centered one once the scroll settles. Used as the
 * "incrementer" on [DualNudgeEntryContent]'s two fields instead of tap-only up/down arrows.
 *
 * When [wraps] is true (e.g. minutes/seconds on a duration), spinning past either end of
 * [range] continues into the other end instead of stopping -- going below the minimum lands on
 * the maximum, like a clock.
 */
@Composable
fun WheelNumberPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wraps: Boolean = false,
    formatValue: (Int) -> String = { it.toString() },
) = BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val itemHeight = maxHeight / VISIBLE_ROWS
    val cycleSize = range.count()
    val cycleCount = if (wraps) WRAP_CYCLE_COUNT else 1
    val totalItems = cycleSize * cycleCount

    fun valueAt(index: Int): Int = range.first + (index % cycleSize + cycleSize) % cycleSize
    fun indexOfValueNear(target: Int, anchorIndex: Int): Int {
        val anchorCycleStart = anchorIndex - (anchorIndex % cycleSize + cycleSize) % cycleSize
        val offset = ((target - range.first) % cycleSize + cycleSize) % cycleSize
        val candidate = anchorCycleStart + offset
        return listOf(candidate - cycleSize, candidate, candidate + cycleSize)
            .filter { it in 0 until totalItems }
            .minByOrNull { abs(it - anchorIndex) }
            ?: candidate.coerceIn(0, totalItems - 1)
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = indexOfValueNear(value, anchorIndex = (totalItems / 2)),
    )
    val flingBehavior = rememberSnapFlingBehavior(listState)

    // Deliberately NOT read via `by` in this composable's body -- that would resubscribe this
    // whole function to recomposition on every selection change, once per visible item, on every
    // fling frame across three wheels at once. Read only inside the graphicsLayer draw-phase
    // lambdas below instead, which update on scroll without triggering recomposition, measurement,
    // or layout at all.
    val centeredIndexState = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo
                .minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
                ?.index
                ?: listState.firstVisibleItemIndex
        }
    }

    // Keep the wheel positioned at `value` when it changes from outside (e.g. after typing a
    // value directly), but never fight the user's own in-progress drag/fling.
    LaunchedEffect(value) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        if (valueAt(listState.firstVisibleItemIndex) == value && listState.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
        listState.scrollToItem(indexOfValueNear(value, anchorIndex = listState.firstVisibleItemIndex))
    }

    // Report the settled value once scrolling stops.
    val centeredIndex by centeredIndexState
    LaunchedEffect(listState.isScrollInProgress, centeredIndex) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        val settledValue = valueAt(centeredIndex)
        if (settledValue != value) onValueChange(settledValue)
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = itemHeight),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(totalItems, key = { it }) { index ->
            val text = formatValue(valueAt(index))
            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val centered = centeredIndexState.value
                        translationY = when {
                            index == centered -> 0f
                            index < centered -> NEIGHBOR_NUDGE_DP.dp.toPx()
                            else -> -NEIGHBOR_NUDGE_DP.dp.toPx()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Both sizes are always composed here, and selection just toggles which one is
                // visible via a draw-only alpha flip read from state inside the graphicsLayer
                // lambda -- no recomposition, remeasure, or relayout happens as the selection
                // moves during a fling.
                LightText(
                    text = text,
                    variant = LightTextVariant.Title,
                    modifier = Modifier.graphicsLayer { alpha = if (index == centeredIndexState.value) 1f else 0f },
                )
                LightText(
                    text = text,
                    variant = LightTextVariant.Heading,
                    lighten = true,
                    modifier = Modifier.graphicsLayer { alpha = if (index == centeredIndexState.value) 0f else 1f },
                )
            }
        }
    }
}
