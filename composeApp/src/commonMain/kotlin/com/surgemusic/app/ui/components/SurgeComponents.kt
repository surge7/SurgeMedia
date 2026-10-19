package com.surgemusic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Surge design system primitives. Every color is read from [MaterialTheme.colorScheme] —
 * the components render identically in the dark and light Surge Media schemes.
 */

/** Default chamfer for glow/panel primitives — mirrors `MaterialTheme.shapes.medium`. */
private val MediumCut = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)

/** Neon outline glow: four concentric [Stroke] passes fading outward from [shape]'s edge. */
fun Modifier.surgeGlow(
    color: Color,
    shape: Shape = MediumCut,
    alpha: Float = 1f,
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val widths = listOf(10.dp, 6.dp, 3.dp, 1.5.dp)
    val layerAlphas = listOf(0.05f, 0.12f, 0.28f, 1f)
    widths.zip(layerAlphas).forEach { (width, layerAlpha) ->
        drawOutline(
            outline = outline,
            color = color,
            alpha = layerAlpha * alpha,
            style = Stroke(width = width.toPx()),
        )
    }
}

/**
 * Fills [shape] with the themed surface and a 1dp outline stroke. Composable so it can
 * read the active color scheme — call it inside composition (like any other modifier
 * factory that depends on theme state).
 */
@Composable
fun Modifier.surgePanel(shape: Shape = MediumCut): Modifier {
    val fill = MaterialTheme.colorScheme.surface
    val stroke = MaterialTheme.colorScheme.outline
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline = outline, color = fill)
        drawOutline(
            outline = outline,
            color = stroke,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** Chamfered primary action; [emphasis] fills solid primary, otherwise a dim primary wash. */
@Composable
fun SurgeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = if (pressed) 0.98f else 1f
                scaleY = if (pressed) 0.98f else 1f
            }
            .alpha(if (enabled) 1f else 0.4f)
            .surgeGlow(
                color = primary,
                shape = MediumCut,
                alpha = if (pressed) 0.45f else if (emphasis) 0.6f else 0.25f,
            )
            .clip(MediumCut)
            .background(if (emphasis) primary else primary.copy(alpha = 0.12f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasis) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        )
    }
}

/** Uppercase label-style text button tinted [tint]; dims when disabled. */
@Composable
fun SurgeTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = tint.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        ),
    )
}

/** Outlined text field with primary focus border, dim outline rest state and transparent container. */
@Composable
fun SurgeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        label = { Text(label) },
        placeholder = if (placeholder == null) {
            null
        } else {
            {
                Text(placeholder)
            }
        },
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colorScheme.primary,
            unfocusedBorderColor = colorScheme.outline,
            focusedLabelColor = colorScheme.primary,
            unfocusedLabelColor = colorScheme.onSurfaceVariant,
            focusedPlaceholderColor = colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = colorScheme.onSurfaceVariant,
            cursorColor = colorScheme.primary,
            focusedTextColor = colorScheme.onSurface,
            unfocusedTextColor = colorScheme.onSurface,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = keyboardActions,
    )
}

/** Surface panel container with chamfered edges, outline border and [contentPadding]. */
@Composable
fun SurgePanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.surgePanel().padding(contentPadding),
        content = content,
    )
}

/** Uppercase primary section label with wide tracking. */
@Composable
fun SurgeSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.em),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** Transparent chip with a 1dp border in [color]. */
@Composable
fun SurgeChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(Color.Transparent)
            .border(1.dp, color, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * Single-select segmented control (used for Settings ▸ Appearance): one outlined segment
 * per [option], the selected segment filled with the primary wash + primary border, the
 * rest outlined in `onSurfaceVariant`. Same visual language as [SurgeChip], but wide
 * enough for a label and touch target.
 */
@Composable
fun <T> SurgeSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (index == 0) 0.dp else 6.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .border(1.dp, tint, MaterialTheme.shapes.small)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (!isSelected) onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
        }
    }
}

/** 1dp horizontal accent rule fading from primary to transparent. */
@Composable
fun ScanlineDivider(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                    ),
                )
            },
    )
}

/** 1dp vertical accent rule fading from primary to transparent — the two-pane list/detail seam. */
@Composable
fun ScanlineVerticalDivider(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.25f), Color.Transparent),
                    ),
                )
            },
    )
}

/**
 * Top bar with optional back action and a primary accent bar before the title. It carries no
 * inset padding of its own: it renders inside the Scaffold body, whose content keeps the
 * default system-bar insets, so the status-bar clearance arrives via the forwarded content
 * padding; only call [SurgeTopBar] inside an already-inset container.
 *
 * [closeIcon] swaps the back arrow for an X — for panes (tablet list-detail) where the
 * action dismisses a selection rather than navigating back.
 */
@Composable
fun SurgeTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    closeIcon: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = if (closeIcon) {
                        Icons.Rounded.Close
                    } else {
                        Icons.AutoMirrored.Rounded.ArrowBack
                    },
                    contentDescription = if (closeIcon) "Close" else "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** SnackbarHost rendering surge-panel snackbars (surface-variant fill, outline border, primary action). */
@Composable
fun SurgeSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val visuals = data.visuals
        val actionLabel = visuals.actionLabel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (actionLabel != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { data.performAction() },
                    )
                }
            }
        }
    }
}

/** One-line, tap-to-dismiss result banner for delete/cleanup outcomes. */
@Composable
fun SurgeResultBanner(text: String, onDismiss: () -> Unit) {
    SurgePanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Material3 confirmation for destructive file deletes; surge-styled buttons. */
@Composable
fun SurgeConfirmDialog(
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete files".uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            SurgeTextButton(text = "Delete", onClick = onConfirm, tint = MaterialTheme.colorScheme.error)
        },
        dismissButton = {
            SurgeTextButton(text = "Cancel", onClick = onDismiss)
        },
    )
}
