package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import org.jsoup.Jsoup

/**
 * Parses rutracker.org /forum/tracker.php search-result pages.
 *
 * Selectors verified against the live markup on 2026-10-12 (see
 * core/liveFixtures/ and docs/superpowers/specs — "Cloudflare reality" section):
 *  - title link:  a.tt-text (data-topic_id attribute, href viewtopic.php?t=…)
 *  - forum:       td.f-name-col a (absolute href with f=…)
 *  - size:        td.tor-size[data-ts_text] carries EXACT bytes; visible text is a fallback
 *  - seeds:       b.seedmed      leechers: td.leechmed      date: last td
 */
object SearchPageParser {

    fun parse(html: String, start: Int): SearchResultPage {
        if (html.contains("id=\"frm-login\"") || html.contains("action=\"login.php\"")) {
            throw TrackerError("session expired (login form returned)", TrackerErrorKind.AUTH)
        }
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("#tor-tbl")
            ?: throw TrackerError("search table not found — site layout changed?", TrackerErrorKind.PARSE, selector = "#tor-tbl")
        val rows = table.select("tbody tr").mapNotNull { tr ->
            val link = tr.selectFirst("a.tt-text") ?: tr.selectFirst("a.tt-link") ?: return@mapNotNull null
            val href = link.attr("href")
            val topicId = link.attr("data-topic_id").toLongOrNull()
                // anchored to ? or & — the unanchored `t=(\d+)` matched the tail of
                // `start=100` (paginated hrefs) and parsed a bogus topic id
                ?: Regex("[?&]t=(\\d+)").find(href)?.groupValues?.get(1)?.toLongOrNull()
                ?: return@mapNotNull null
            val forumLink = tr.selectFirst("td.f-name-col a")
                ?: tr.selectFirst("td.f-name a")
                ?: tr.selectFirst("div.f-name a")
            val forumId = forumLink?.attr("href")?.let { Regex("f=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            val sizeCell = tr.selectFirst("td.tor-size")
            val sizeBytes = sizeCell?.attr("data-ts_text")?.toLongOrNull()
                ?: SizeParser.parseBytes(sizeCell?.selectFirst("a.tr-dl")?.text() ?: sizeCell?.text() ?: "0 Б")
            val seeds = tr.selectFirst("b.seedmed")?.text()?.trim()?.toIntOrNull()
                ?: tr.selectFirst("td.seedmed b")?.text()?.trim()?.toIntOrNull()
                ?: 0
            val leechs = tr.selectFirst("td.leechmed")?.text()?.trim()?.toIntOrNull() ?: 0
            val dateText = tr.select("td").lastOrNull()?.text()?.replace("\u00A0", " ")?.trim() ?: ""
            SearchResultRow(
                topicId = topicId,
                title = link.text().trim(),
                forumId = forumId,
                forumName = forumLink?.text()?.trim() ?: "",
                sizeBytes = sizeBytes,
                seeds = seeds,
                leechs = leechs,
                dateText = dateText,
            )
        }
        // Silent-empty guard: rutracker keeps #tor-tbl (headers only, zero data rows)
        // on legitimately empty searches and marks them with "Ничего не найдено" — so an
        // empty row set WITH that marker is a valid empty page. Zero rows WITHOUT the
        // marker means the table rendered but our row selectors matched nothing: the
        // markup changed and pretending "no results" would silently hide a real listing.
        if (rows.isEmpty() && !html.contains("Ничего не найдено", ignoreCase = true)) {
            throw TrackerError(
                "search table present but 0 rows parsed (and no \"Ничего не найдено\" marker) — markup changed?",
                TrackerErrorKind.PARSE,
                selector = "#tor-tbl tr",
            )
        }
        return SearchResultPage(rows = rows, start = start)
    }
}
