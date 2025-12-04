package org.koitharu.kotatsu.parsers.site.all
val url = "https://$domain/browse?page=$page"
return parseList(url)
}


override suspend fun getDetails(manga: Manga): Manga {
val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
val title = doc.selectFirst("h1, h2, h3")?.text() ?: manga.title
val cover = doc.selectFirst("img[src]")?.absUrl("src")
val desc = doc.select(".summary, .description, #description").firstOrNull()?.html()


val chapterElements = doc.select("a[href*=/chapter/]")
val chapters = chapterElements.mapIndexed { i, el ->
val href = el.attrAsRelativeUrl("href")
MangaChapter(
id = generateUid(href),
title = el.text(),
number = (i + 1).toFloat(),
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
description = desc,
tags = emptySet(),
authors = emptySet(),
chapters = chapters,
)
}


override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
val images = doc.select("img[src], img[data-src], img[data-lazy-src]")


return images.mapNotNull { img ->
val link = img.absUrl("src").ifEmpty {
img.absUrl("data-src").ifEmpty { img.absUrl("data-lazy-src") }
}
if (link.isEmpty()) null else MangaPage(
id = generateUid(link),
url = link,
preview = null,
source = source,
)
}
}


private suspend fun search(page: Int, query: String): List<Manga> {
val url = "https://$domain/search?word=${query.replace(' ', '+')}&page=$page"
return parseList(url)
}


private suspend fun parseList(url: String): List<Manga> {
val doc = webClient.httpGet(url).parseHtml().body()
val entries = doc.select("a[href*=/manga/], a[href*=/series/]")


return entries.map { a ->
val href = a.attrAsRelativeUrl("href")
Manga(
id = generateUid(href),
title = a.text().ifBlank { href.substringAfterLast('/') },
altTitles = emptySet(),
url = href,
publicUrl = a.absUrl("href"),
rating = RATING_UNKNOWN,
contentRating = null,
coverUrl = a.selectFirst("img[src], img[data-src]")?.absUrl("src")
?.ifEmpty { a.selectFirst("img[data-src]")?.absUrl("data-src") },
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
