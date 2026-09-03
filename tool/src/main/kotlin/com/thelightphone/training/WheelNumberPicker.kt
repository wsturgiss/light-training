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

// Rows visible at once within whatever space the caller gives the wheel (see BoxWithConstraints).
private const val VISIBLE_ROWS = 3

// Visual-only nudge (dp) of non-selected rows toward center; doesn't affect scroll/snap.
private const val NEIGHBOR_NUDGE_DP = 10

// Repeats a wrapping range into one long list, centered on the current value, so spinning past
// either end continues into the next cycle instead of stopping. 401 cycles (~200 either way) is
// far more than anyone will ever spin in one motion.
private const val WRAP_CYCLE_COUNT = 401

// Heading's font size as a fraction of Title's (see LightTheme.kt), for shrinking non-centered
// rows via graphicsLayer scale instead of composing a second Text per row.
private const val NEIGHBOR_SCALE = 38f / 115f

// Fade for non-centered rows, standing in for LightText's `lighten` color swap.
private const val NEIGHBOR_ALPHA = 0.55f

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
    enabled: Boolean = true,
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

    // Not read via `by` here -- that would recompose this whole function on every selection
    // change. Read only inside graphicsLayer draw-phase lambdas below, which update on scroll
    // with no recomposition/measure/layout.
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

    // Follow external `value` changes, but never fight an in-progress drag/fling.
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
        userScrollEnabled = enabled,
        contentPadding = PaddingValues(vertical = itemHeight),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(totalItems, key = { it }) { index ->
            val text = formatValue(valueAt(index))
            // One Text per row, not two (Title + Heading), to halve text layout cost during a
            // fling. Selection is a scale/fade/translate in one draw-only graphicsLayer lambda.
            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val isCentered = index == centeredIndexState.value
                        translationY = when {
                            isCentered -> 0f
                            index < centeredIndexState.value -> NEIGHBOR_NUDGE_DP.dp.toPx()
                            else -> -NEIGHBOR_NUDGE_DP.dp.toPx()
                        }
                        alpha = if (isCentered) 1f else NEIGHBOR_ALPHA
                        scaleX = if (isCentered) 1f else NEIGHBOR_SCALE
                        scaleY = if (isCentered) 1f else NEIGHBOR_SCALE
                    },
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = text, variant = LightTextVariant.Title)
            }
        }
    }
}
