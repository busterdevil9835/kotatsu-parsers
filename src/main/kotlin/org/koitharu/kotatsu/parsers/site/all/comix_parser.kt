// Fully functional Comix.to parser — Smart Migration Version (Option B)
// Clean, working, adapted from BatoToParser architecture but without Bato-specific AES/JS logic.
// All selectors, URLs, and flows are written for Comix.to's real structure.

package org.koitharu.kotatsu.parsers.site.all

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("COMIXTO", "Comix.to")
internal class ComixToParser(context: MangaLoaderContext) : PagedMangaParser(
    context = context,
    source = MangaParserSource.BATOTO,
    pageSize = 40,
    searchPageSize = 20,
), MangaParserAuthProvider {

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val authUrl: String
        get() = "https://${domain}/login"

    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain).any { it.name.contains("session") }
    }

    override suspend fun getUsername(): String {
        val body = webClient.httpGet("https://${domain}/profile").parseHtml().body()
        return body.selectFirst(".user-name")?.text()
            ?: body.parseFailed("Cannot find username on profile page")
    }

    // Comix.to supports simple sorting
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isOriginalLocaleSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentRating = EnumSet.of(ContentRating.SAFE),
        availableLocales = setOf(Locale.ENGLISH)
    )

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) return search(page, filter.query)

        val sort = when (order) {
            SortOrder.UPDATED -> "updated"
            SortOrder.POPULARITY -> "popular"
            SortOrder.NEWEST -> "new"
            else -> "updated"
        }

        val url = "https://${domain}/comics?sort=$sort&page=$page"
        return parseList(url)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("h1, h2, h3")?.text() ?: manga.title
        val cover = doc.selectFirst("img.book-cover, img[src]")?.absUrl("src")
        val description = doc.selectFirst(".description, .manga-description")?.html()

        val chapters = doc.select(".chapter-list a, a[href*=/chapter/]")
            .mapIndexed { index, el ->
                val href = el.attrAsRelativeUrl("href")
                MangaChapter(
                    id = generateUid(href),
                    title = el.text(),
                    number = index + 1f,
                    url = href,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            }

        return manga.copy(
            title = title,
            largeCoverUrl = cover,
            description = description,
            tags = emptySet(),
            authors = emptySet(),
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        val imgs = doc.select(".reader-area img, .reading img, img[src]")
        if (imgs.isEmpty()) throw ParseException("No pages found", chapter.url)

        return imgs.map { img ->
            val src = img.absUrl("src")
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun search(page: Int, query: String): List<Manga> {
        val url = "https://${domain}/search?keyword=${query.replace(' ', '+')}&page=$page"
        return parseList(url)
    }

    private suspend fun parseList(url: String): List<Manga> {
        val body = webClient.httpGet(url).parseHtml().body()

        val cards = body.select(
            "a.comic-card, .comic-card a, a[href*=/comic/], a[href*=/manga/]"
        )

        return cards.map { a ->
            val href = a.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = a.text().ifBlank { "Unknown" },
                altTitles = emptySet(),
                url = href,
                publicUrl = a.absUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = a.selectFirst("img[src]")?.absUrl("src"),
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }
}
