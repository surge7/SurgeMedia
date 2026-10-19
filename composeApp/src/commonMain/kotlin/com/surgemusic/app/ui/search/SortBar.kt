package com.surgemusic.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.surgemusic.app.ui.strings.AppStrings
import com.surgemusic.app.ui.strings.LocalStrings

/** Chamfered chip corner — a smaller sibling of the panels' `CutCornerShape(10.dp)`. */
private val SortChipShape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)

/** Sort display labels; the enum itself stays data-only so labels localize with the UI. */
private fun sortLabel(sort: SearchSort, strings: AppStrings): String = when (sort) {
    SearchSort.RELEVANCE -> strings.sortByRelevance
    SearchSort.SEEDS -> strings.sortBySeeds
    SearchSort.SIZE -> strings.sortBySize
    SearchSort.DATE -> strings.sortByDate
    SearchSort.TITLE -> strings.sortByTitle
}

/**
 * Compact single-select sort switcher (plan: "sorting options for search results").
 *
 * A tiny "SORT" label followed by one selectable chip per [SearchSort], in a horizontally
 * scrollable row so it fits narrow phones; the whole bar stays ≤ 40dp tall. Chips are kin to
 * [com.surgemusic.app.ui.components.SurgeChip] (transparent, 1dp outlined, uppercase
 * `labelSmall`) but selectable: the selected chip gets the primary border/text plus a faint
 * primary wash, unselected ones tone down to `onSurfaceVariant`.
 */
@Composable
fun SortBar(
    current: SearchSort,
    onSelect: (SearchSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Row(
        modifier = modifier
            .heightIn(max = 40.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.sortByLabel,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.em),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        SearchSort.entries.forEach { option ->
            SortChip(
                text = sortLabel(option, strings),
                selected = option == current,
                onClick = { if (option != current) onSelect(option) },
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun SortChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(SortChipShape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, tint, SortChipShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
