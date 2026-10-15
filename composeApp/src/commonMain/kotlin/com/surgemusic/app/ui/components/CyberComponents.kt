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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.surgemusic.app.ui.theme.CyberCyan
import com.surgemusic.app.ui.theme.CyberGrid
import com.surgemusic.app.ui.theme.CyberSurface
import com.surgemusic.app.ui.theme.CyberSurfaceAlt

/** Default chamfer for glow/panel primitives — mirrors `MaterialTheme.shapes.medium`. */
private val MediumCut = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)

/** Neon outline glow: four concentric [Stroke] passes fading outward from [shape]'s edge. */
fun Modifier.cyberGlow(
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

/** Fills [shape] with the dark panel surface and a 1dp grid-line stroke. */
fun Modifier.cyberPanel(shape: Shape = MediumCut): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline = outline, color = CyberSurface)
    drawOutline(
        outline = outline,
        color = CyberGrid,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** Chamfered primary action; [emphasis] fills solid cyan, otherwise a dim cyan wash. */
@Composable
fun CyberButton(
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
            .cyberGlow(
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
fun CyberTextButton(
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

/** Outlined text field with cyan focus border, dim grid rest border and transparent container. */
@Composable
fun CyberTextField(
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

/** Surface panel container with chamfered edges, grid border and [contentPadding]. */
@Composable
fun CyberPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.cyberPanel().padding(contentPadding),
        content = content,
    )
}

/** Uppercase cyan section label with wide tracking. */
@Composable
fun CyberSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.em),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** Transparent chip with a 1dp border in [color]. */
@Composable
fun CyberChip(text: String, color: Color, modifier: Modifier = Modifier) {
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

/** 1dp horizontal scanline fading from cyan to transparent. */
@Composable
fun ScanlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(CyberCyan.copy(alpha = 0.25f), Color.Transparent),
                    ),
                )
            },
    )
}

/**
 * Top bar with optional back action and a cyan accent bar before the title. It carries no
 * inset padding of its own: it renders inside the Scaffold body, whose content keeps the
 * default system-bar insets (plan Task 2), so the status-bar clearance arrives via the
 * forwarded content padding. The plan's Task-1 `statusBarsPadding()` prescription double-
 * padded under that forwarding once edge-to-edge landed (final-review F1) and was dropped
 * here; only call [CyberTopBar] inside an already-inset container.
 */
@Composable
fun CyberTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(CyberCyan),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** SnackbarHost rendering cyber-panel snackbars (surface-alt fill, grid border, cyan action). */
@Composable
fun CyberSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val visuals = data.visuals
        val actionLabel = visuals.actionLabel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(CyberSurfaceAlt)
                .border(1.dp, CyberGrid, MaterialTheme.shapes.medium)
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
                        color = CyberCyan,
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
