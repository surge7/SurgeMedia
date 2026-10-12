package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import org.jsoup.Jsoup

object SearchPageParser {

    fun parse(html: String, start: Int): SearchResultPage {
        if (html.contains("id=\"frm-login\"") || html.contains("action=\"login.php\"")) {
            throw TrackerError("session expired (login form returned)", TrackerErrorKind.AUTH)
        }
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("#tor-tbl")
            ?: throw TrackerError("search table not found — site layout changed?", TrackerErrorKind.PARSE, selector = "#tor-tbl")
        val rows = table.select("tbody tr").mapNotNull { tr ->
            val link = tr.selectFirst("a.tt-link") ?: return@mapNotNull null
            val href = link.attr("href")
            val topicId = Regex("t=(\\d+)").find(href)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val forumLink = tr.selectFirst("td.f-name a")
            val forumId = forumLink?.attr("href")?.let { Regex("f=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            val sizeText = tr.selectFirst("a[title=Размер]")?.text() ?: "0 Б"
            val seeds = tr.selectFirst("td.seedmed b")?.text()?.trim()?.toIntOrNull() ?: 0
            val leechs = tr.selectFirst("td.leechmed b")?.text()?.trim()?.toIntOrNull() ?: 0
            val dateText = tr.select("td").lastOrNull()?.text()?.replace("\u00A0", " ")?.trim() ?: ""
            SearchResultRow(
                topicId = topicId,
                title = link.text().trim(),
                forumId = forumId,
                forumName = forumLink?.text()?.trim() ?: "",
                sizeBytes = SizeParser.parseBytes(sizeText),
                seeds = seeds,
                leechs = leechs,
                dateText = dateText,
            )
        }
        return SearchResultPage(rows = rows, start = start)
    }
}
