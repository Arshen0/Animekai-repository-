package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Animekai : MainAPI() {
    override var mainUrl = "https://animekai.com"
    override var name = "Animekai"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // Featured Episodes
        val featuredEpisodes = document.select("div.episodes-card").mapNotNull {
            it.toSearchResult()
        }
        if (featuredEpisodes.isNotEmpty()) {
            homePageList.add(HomePageList("Featured Episodes", featuredEpisodes))
        }

        // Popular Anime
        val popularAnime = document.select("div.card:has(h3:contains(Popular)) + div.row a.card").mapNotNull {
            it.toSearchResult()
        }
        if (popularAnime.isNotEmpty()) {
            homePageList.add(HomePageList("Popular Anime", popularAnime))
        }

        return HomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h3, h5, h6")?.text()?.trim() ?: return null
        val href = fixUrl(this.attr("href"))
        val poster = this.selectFirst("img")?.attr("src")
        val epNum = this.selectFirst("div.episode")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/search", params = mapOf("q" to query))
        return response.document.select("div.row a.card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.anime-poster img")?.attr("src")
        val description = document.selectFirst("div.anime-description")?.text()?.trim()
        val type = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        val episodes = document.select("div.episode-list a").mapNotNull {
            val href = fixUrl(it.attr("href"))
            val name = it.selectFirst("div.episode-name")?.text()?.trim()
            val number = it.selectFirst("div.episode-number")?.text()?.filter { char -> char.isDigit() }?.toIntOrNull()
            val thumbnail = it.selectFirst("img")?.attr("src")

            Episode(
                data = href,
                name = name,
                season = 1,
                episode = number,
                posterUrl = thumbnail
            )
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        
        // Extract video iframe
        val iframe = document.selectFirst("div.video-player iframe")?.attr("src") ?: return false
        val iframeUrl = fixUrl(iframe)

        // Handle video hosts
        when {
            iframeUrl.contains("streamtape") -> {
                extractStreamtape(iframeUrl, callback)
                return true
            }
            iframeUrl.contains("dokicloud") -> {
                loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                return true
            }
            else -> {
                // Add more hosts as needed
                return false
            }
        }
    }

    private suspend fun extractStreamtape(url: String, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url)
        val script = response.document.select("script:contains(document.getElementById('robotlink'))").html()
        val encoded = script.substringAfter("('robotlink').innerHTML = '").substringBefore("'")
        val decoded = encoded.replace(Regex("\\\\(.)")) { it.groupValues[1] }
        val videoUrl = "https:$decoded".replace("streamtape.com/get_video", "streamtape.com/e")

        callback.invoke(
            ExtractorLink(
                name,
                name,
                videoUrl,
                mainUrl,
                Qualities.Unknown.value,
                false
            )
        )
    }

    private suspend fun loadExtractor(url: String, refer
