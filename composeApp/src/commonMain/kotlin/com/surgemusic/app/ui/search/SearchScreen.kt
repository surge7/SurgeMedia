package com.surgemusic.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surgemusic.app.ui.components.SurgeButton
import com.surgemusic.app.ui.components.SurgePanel
import com.surgemusic.app.ui.components.SurgeTextButton
import com.surgemusic.app.ui.components.SurgeTextField
import com.surgemusic.app.ui.components.MediaFormatChips
import com.surgemusic.app.ui.components.surgeGlow
import com.surgemusic.app.ui.components.surgePanel
import com.surgemusic.app.ui.strings.LocalStrings
import com.surgemusic.core.model.MediaInfo
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.model.SizeParser

/**
 * Search tab (plan Task 6): query field + results list with "Load more" pagination.
 *
 * - Tapping a row calls [onShowDetails] — the parent (`App`) lifts the row into a details
 *     overlay screen where the user can review the topic and tap "Download".
 * - Auth-expired errors (`needsLogin`) show a "Log in" button calling [onLogin]; `App` renders
 *     the platform `WebViewLoginScreen` over the tab while the login flow is active.
 * - When no session is saved, a "Log in to rutracker" hero offers login before searching.
 * - Pull-to-refresh is not required (plan).
 * - [selectedTopicId] highlights the row currently open in the details pane — the tablet
 *     list-detail layout keeps the list visible beside Details, so the selection must
 *     stay legible (cyan glow border); null (compact/medium overlay mode) highlights nothing.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    loggedIn: Boolean,
    modifier: Modifier = Modifier,
    selectedTopicId: Long? = null,
    onShowDetails: (SearchResultRow) -> Unit = {},
    onLogin: () -> Unit = {},
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val sorted by viewModel.sortedResults.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val strings = LocalStrings.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (!loggedIn) LoginHero(onLogin = onLogin)

        Row(verticalAlignment = Alignment.CenterVertically) {
            SurgeTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = strings.searchFieldLabel,
                modifier = Modifier.weight(1f),
                singleLine = true,
                imeAction = ImeAction.Search,
                keyboardActions = KeyboardActions(
                    onSearch = { if (query.isNotBlank()) viewModel.search() },
                ),
            )
            Spacer(Modifier.width(8.dp))
            SurgeButton(
                text = strings.searchButton,
                onClick = viewModel::search,
                enabled = query.isNotBlank() && uiState !is SearchUiState.Loading,
            )
        }

        if (uiState is SearchUiState.Ready && results.isNotEmpty()) {
            SortBar(
                current = sort,
                onSelect = viewModel::setSort,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        when (val state = uiState) {
            SearchUiState.Idle -> Hint(strings.searchIdleHint)
            SearchUiState.Loading -> CircularProgressIndicator(Modifier.padding(top = 16.dp))
            is SearchUiState.Error -> ErrorBanner(state, onRetry = viewModel::retry, onLogin = onLogin)
            is SearchUiState.Ready -> {
                if (results.isEmpty()) {
                    Hint(strings.noResults(query))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .weight(1f)
                            .imePadding(),
                    ) {
                        items(sorted, key = { it.topicId }) { row ->
                            ResultRowItem(
                                row = row,
                                selected = row.topicId == selectedTopicId,
                                onShowDetails = onShowDetails,
                            )
                        }
                        if (state.hasMore) {
                            item {
                                SurgeButton(
                                    text = strings.loadMore,
                                    onClick = viewModel::loadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginHero(onLogin: () -> Unit) {
    val strings = LocalStrings.current
    SurgePanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = strings.loginHeroTitle.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            strings.loginHeroBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        SurgeButton(
            text = strings.logIn,
            onClick = onLogin,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ResultRowItem(
    row: SearchResultRow,
    selected: Boolean,
    onShowDetails: (SearchResultRow) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .surgePanel()
            // Selected (details pane open): cyan neon edge replaces the resting grid border.
            .then(
                if (selected) {
                    Modifier.surgeGlow(color = MaterialTheme.colorScheme.primary, alpha = 0.8f)
                } else {
                    Modifier
                },
            )
            .clickable { onShowDetails(row) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.forumName} · ${SizeParser.formatBytes(row.sizeBytes)} · " +
                    "↑${row.seeds} ↓${row.leechs} · ${row.dateText}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val mediaInfo = remember(row.topicId) { MediaInfo.fromTitle(row.title, row.forumName) }
            MediaFormatChips(mediaInfo, Modifier.padding(top = 4.dp))
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ErrorBanner(state: SearchUiState.Error, onRetry: () -> Unit, onLogin: () -> Unit) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = strings.searchFailed(state.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        SurgeTextButton(text = strings.actionRetry, onClick = onRetry)
        if (state.needsLogin) {
            SurgeTextButton(
                text = strings.logIn,
                onClick = onLogin,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
