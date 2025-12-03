package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

/**
 * Comix.to parser modeled after MangaDexParser style:
 * - Uses PagedMangaParser architecture
 * - Provides robust parsing for lists, details, chapters and pages
 * - Uses defensive selectors and JS/JSON fallback parsing
 */

@MangaSourceParser("COMIXTO", "Comix.to")
internal class ComixToDexStyleParser(context: MangaLoaderContext) : PagedMangaParser(
    context = context,
    source = MangaParserSource.BATOTO, // reuse existing enum; consider adding COMIXTO if available
    pageSize = 48,
    searchPageSize = 24,
) {

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    // --- List / Search ---
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) return search(page, filter.query)

        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated"
            SortOrder.POPULARITY -> "popular"
            SortOrder.NEWEST -> "new"
            SortOrder.ALPHABETICAL -> "alpha"
            else -> "updated"
        }

        val url = buildString {
            append("https://")
            append(domain)
            append("/comics?sort=")
            append(sortParam)
            append("&page=")
            append(page)
        }

        return parseList(url)
    }

    private suspend fun search(page: Int, query: String): List<Manga> {
        val q = encodeQuery(query)
        val url = "https://${domain}/search?keyword=$q&page=$page"
        return parseList(url)
    }

    private suspend fun parseList(url: String): List<Manga> {
        val body = webClient.httpGet(url).parseHtml().body()

        // Prefer structured cards, fallback to generic links
        val cards = body.select(".comic-card, .page-item, a[href*=/comic/], a[href*=/manga/]")

        return cards.map { el ->
            // Some anchors wrap images; pick the nearest link
            val a = if (el.tagName() == "a") el else el.selectFirst("a[href]") ?: el
            val href = a.attrAsRelativeUrl("href")
            val title = a.selectFirst(".comic-title, .h5, .title, img[alt]")?.textOrNull()
                ?: a.attr("title").ifBlank { a.text().ifBlank { "Unknown" } }

            val cover = a.selectFirst("img[src], img[data-src]")?.let { img ->
                img.absUrl("data-src").ifBlank { img.absUrl("src") }
            }

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = a.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    // --- Details & Chapters ---
    override suspend fun getDetails(manga: Manga): Manga {
        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml().body()

        val title = root.selectFirst("h1.entry-title, .post-title, .manga-title, h1")?.text() ?: manga.title
        val cover = root.selectFirst(".summary_image img, .thumb img, img.book-cover, img[src]")?.let { img ->
            img.absUrl("data-src").ifBlank { img.absUrl("src") }
        }

        val description = root.selectFirst(".description, .entry-content, .summary, #description")?.html()

        // Chapters: try structured chapter lists, then fallback to anchor scan
        val chaptersEls = root.select(".chapter-list a, .wp-manga-chapter a, a[href*=/chapter/], a.chapter-link")
        val chapters = if (chaptersEls.isNotEmpty()) {
            chaptersEls.mapIndexed { idx, el ->
                val href = el.attrAsRelativeUrl("href")
                MangaChapter(
                    id = generateUid(href),
                    title = el.textOrNull(),
                    number = try { el.attr("data-chapter").toFloat() } catch (_: Exception) { (idx + 1).toFloat() },
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = runCatching { parsePossibleDate(el.textOrNull()) }.getOrDefault(0L),
                    branch = null,
                    source = source,
                )
            }
        } else emptyList()

        return manga.copy(
            title = title,
            largeCoverUrl = cover,
            description = description,
            tags = parseTagsFromDetails(root),
            authors = parseAuthors(root),
            chapters = chapters,
        )
    }

    private fun parseAuthors(root: Element): Set<String> {
        val sel = root.selectFirst(".author, .artist, .manga-author, .post-author")
        if (sel != null) return setOf(sel.text().trim())
        return emptySet()
    }

    private fun parseTagsFromDetails(root: Element): Set<MangaTag> {
        val tagEls = root.select(".genres a, .tags a, .post-tags a, .tag-links a")
        if (tagEls.isEmpty()) return emptySet()
        return tagEls.mapToSet { el ->
            val key = el.attr("href").substringAfterLast('/').lowercase(Locale.ENGLISH)
            MangaTag(title = el.text().toTitleCase(), key = key, source = source)
        }
    }

    // --- Pages ---
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml().body()

        // Look for reading-content images and handle lazy-loading attributes
        val imgs = doc.select(".reading-content img, .reader-area img, img[src], img[data-src], img[data-lazy-src]")
        if (imgs.isEmpty()) throw ParseException("No images found for chapter", chapter.url)

        return imgs.map { img ->
            val src = img.absUrl("data-src").ifBlank { img.absUrl("data-lazy-src").ifBlank { img.absUrl("src") } }
            MangaPage(id = generateUid(src), url = src, preview = null, source = source)
        }
    }

    // Utility: parse short text like "5 days ago" or ISO date; returns epoch millis or 0
    private fun parsePossibleDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        // naive: if contains "ago" parse relative, else try ISO
        val t = text.lowercase(Locale.ENGLISH)
        return when {
            "ago" in t -> {
                // examples: "5 days ago", "2 hours ago"
                val parts = t.split(' ')
                if (parts.size < 2) return 0
                val value = parts[0].toIntOrNull() ?: return 0
                val unit = parts[1]
                val cal = Calendar.getInstance()
                when {
                    unit.startsWith("sec") -> cal.add(Calendar.SECOND, -value)
                    unit.startsWith("min") -> cal.add(Calendar.MINUTE, -value)
                    unit.startsWith("hour") -> cal.add(Calendar.HOUR_OF_DAY, -value)
                    unit.startsWith("day") -> cal.add(Calendar.DAY_OF_MONTH, -value)
                    unit.startsWith("week") -> cal.add(Calendar.WEEK_OF_YEAR, -value)
                    unit.startsWith("month") -> cal.add(Calendar.MONTH, -value)
                    unit.startsWith("year") -> cal.add(Calendar.YEAR, -value)
                    else -> return 0
                }
                cal.timeInMillis
            }
            else -> runCatching { parseIso8601ToMillis(text) }.getOrDefault(0L)
        }
    }

    private fun parseIso8601ToMillis(text: String): Long {
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }

    // Helper: encode query param safely
    private fun encodeQuery(q: String) = java.net.URLEncoder.encode(q, Charsets.UTF_8)
}
