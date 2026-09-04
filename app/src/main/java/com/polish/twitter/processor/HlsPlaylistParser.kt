package com.polish.twitter.processor

/**
 * Twitter 视频走 HLS/CMAF：master 列出各分辨率 + 独立音轨，
 * media playlist 用 EXT-X-MAP init + .m4s 分片。没有 progressive MP4 时靠这个合成。
 */
object HlsPlaylistParser {

    data class Variant(
        val bandwidth: Int,
        val width: Int,
        val height: Int,
        val videoUrl: String,
        val audioUrl: String?
    )

    data class MediaPlaylist(
        val mapUrl: String?,
        val segments: List<String>
    )

    fun isMasterPlaylist(text: String): Boolean {
        return text.contains("#EXT-X-STREAM-INF")
    }

    /** master 是 /pl/{token}.m3u8，变体才是 /pl/avc1/WxH/。下载必须走 master 才能拿到片源最高档。 */
    fun isMasterPlaylistUrl(url: String): Boolean {
        if (!url.contains(".m3u8", ignoreCase = true)) return false
        if (url.contains("/avc1/") || url.contains("/mp4a/")) return false
        return Regex("""/pl/[^/?]+\.m3u8""", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    fun variantPixels(url: String): Long {
        val m = Regex("""/avc1/(\d+)x(\d+)/""").find(url) ?: return 0L
        val w = m.groupValues[1].toLongOrNull() ?: 0L
        val h = m.groupValues[2].toLongOrNull() ?: 0L
        return w * h
    }

    fun qualityRank(url: String): Long {
        if (isMasterPlaylistUrl(url)) return Long.MAX_VALUE
        return variantPixels(url)
    }

    fun parseMaster(text: String, playlistUrl: String): List<Variant> {
        val audioByGroup = mutableMapOf<String, String>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                val group = attr(line, "GROUP-ID")
                val uri = attr(line, "URI")
                if (group != null && uri != null) {
                    audioByGroup[group] = resolveUrl(playlistUrl, uri)
                }
            }
            i++
        }

        val variants = mutableListOf<Variant>()
        i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val bandwidth = attr(line, "BANDWIDTH")?.toIntOrNull() ?: 0
                val res = attr(line, "RESOLUTION")
                val (w, h) = parseRes(res)
                val audioGroup = attr(line, "AUDIO")
                val uri = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") }
                if (uri != null) {
                    variants.add(
                        Variant(
                            bandwidth = bandwidth,
                            width = w,
                            height = h,
                            videoUrl = resolveUrl(playlistUrl, uri),
                            audioUrl = audioGroup?.let { audioByGroup[it] }
                        )
                    )
                }
                i++
            }
            i++
        }
        return variants
    }

    fun pickBest(variants: List<Variant>): Variant? {
        return variants.maxWithOrNull(
            compareBy<Variant> { it.width * it.height }.thenBy { it.bandwidth }
        )
    }

    fun parseMedia(text: String, playlistUrl: String): MediaPlaylist {
        var mapUrl: String? = null
        val segments = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP:")) {
                attr(line, "URI")?.let { mapUrl = resolveUrl(playlistUrl, it) }
                continue
            }
            if (line.startsWith("#")) continue
            segments.add(resolveUrl(playlistUrl, line))
        }
        return MediaPlaylist(mapUrl, segments)
    }

    fun resolveUrl(baseUrl: String, ref: String): String {
        val cleaned = ref.trim().trim('"')
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        val origin = Regex("^https?://[^/]+").find(baseUrl)?.value ?: "https://video.twimg.com"
        if (cleaned.startsWith("/")) return origin + cleaned
        val slash = baseUrl.lastIndexOf('/')
        val dir = if (slash > 7) baseUrl.substring(0, slash + 1) else "$origin/"
        return dir + cleaned
    }

    private fun attr(line: String, name: String): String? {
        val regex = Regex("""$name=("[^"]*"|[^,]+)""")
        val raw = regex.find(line)?.groupValues?.getOrNull(1) ?: return null
        return raw.trim().trim('"')
    }

    private fun parseRes(res: String?): Pair<Int, Int> {
        if (res.isNullOrBlank()) return 0 to 0
        val parts = res.split('x')
        val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return w to h
    }
}
