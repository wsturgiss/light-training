package com.thelightphone.sdk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

private const val ROW_VERTICAL_PADDING_UNITS = 0.5f
private const val CONTENT_VERTICAL_PADDING_UNITS = 0.25f
private const val CONTENT_SPACING_UNITS = 0.25f
private const val EDIT_ICON_SIZE_UNITS = 2f

/**
 * Standard "editable row" shape used throughout LightOS list/detail screens:
 *
 * - the whole row is clickable (invokes [onClick])
 * - a larger label, with an optional smaller [superscript] shown above it
 *   and/or an optional smaller [subscript] shown below it
 * - a trailing pencil icon shown when [editable] is true, which invokes [onEdit]
 *   (or [onClick] if [onEdit] is not provided)
 *
 * Use this instead of hand-rolling row composables so that spacing, click
 * targets, and the edit affordance stay consistent across screens.
 */
@Composable
fun LightEditableRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    superscript: String? = null,
    subscript: String? = null,
    editable: Boolean = true,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = ROW_VERTICAL_PADDING_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = CONTENT_VERTICAL_PADDING_UNITS.gridUnitsAsDp()),
        ) {
            if (superscript != null) {
                LightText(
                    text = superscript,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = CONTENT_SPACING_UNITS.gridUnitsAsDp()),
                )
            }
            LightText(text = label, variant = LightTextVariant.Copy)
            if (subscript != null) {
                LightText(
                    text = subscript,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = CONTENT_SPACING_UNITS.gridUnitsAsDp()),
                )
            }
        }
        if (editable) {
            LightIcon(
                icon = LightIcons.PENCIL,
                size = EDIT_ICON_SIZE_UNITS,
                contentDescription = "Edit $label",
                modifier = Modifier.lightClickable(onClick = onEdit ?: onClick),
            )
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 400 / 3, showBackground = true)
@Composable
private fun PreviewLightEditableRowDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        Column {
            LightEditableRow(label = "Biceps", onClick = {})
            LightEditableRow(
                label = "Bench Press",
                subscript = "Chest + Triceps",
                onClick = {},
            )
            LightEditableRow(
                superscript = "Primary muscle group",
                label = "Chest",
                onClick = {},
            )
            LightEditableRow(label = "Read only", editable = false, onClick = {})
        }
    }
}
