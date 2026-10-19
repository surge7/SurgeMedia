package com.surgemusic.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surgemusic.app.ui.strings.AppStrings
import com.surgemusic.app.ui.strings.LocalStrings
import com.surgemusic.core.model.MediaInfo
import com.surgemusic.core.model.MediaKind

/**
 * Compact format chips rendered from [MediaInfo] (Task B): one colored kind chip
 * (AUDIO → primary, VIDEO → tertiary, BOOK → secondary) followed by up to two neutral
 * [MediaInfo.chips] entries — at most three single-line chips per row. Nothing renders
 * for [MediaKind.UNKNOWN], so unclassified rows keep their previous layout.
 */
@Composable
fun MediaFormatChips(mediaInfo: MediaInfo, modifier: Modifier = Modifier) {
    if (mediaInfo.kind == MediaKind.UNKNOWN) return
    val strings = LocalStrings.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaChip(text = mediaInfo.kind.labelText(strings), color = mediaInfo.kind.chipColor())
        mediaInfo.chips()
            .take(MAX_EXTRA_CHIPS)
            .forEach { chip -> MediaChip(text = chip, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Max chips per row = 1 kind chip + [MAX_EXTRA_CHIPS] format chips, keeping rows compact. */
private const val MAX_EXTRA_CHIPS = 2

@Composable
private fun MediaKind.chipColor(): Color = when (this) {
    MediaKind.AUDIO -> MaterialTheme.colorScheme.primary
    MediaKind.VIDEO -> MaterialTheme.colorScheme.tertiary
    MediaKind.BOOK -> MaterialTheme.colorScheme.secondary
    MediaKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun MediaKind.labelText(strings: AppStrings): String = when (this) {
    MediaKind.AUDIO -> strings.kindAudio
    MediaKind.VIDEO -> strings.kindVideo
    MediaKind.BOOK -> strings.kindBook
    MediaKind.UNKNOWN -> "?"
}

/** Border-only single-line chip matching SurgeChip's look, with ellipsized text. */
@Composable
private fun MediaChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, color, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
