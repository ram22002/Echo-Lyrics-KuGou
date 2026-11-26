package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * KuGou. Song search gives a file hash, the lyrics search gives an id/accesskey pair, and the
 * download route returns either an encrypted KRC container or plain LRC. KRC decoding here is
 * interoperability with a payload the service returns unauthenticated, not DRM circumvention.
 */
internal class KuGouSource : LyricsSource {

    private val throttle = Throttle(400)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        throttle.await()
        val keyword = query.artist + " " + track.title
        val text = Http.get(SONG_SEARCH_URL + "&keyword=" + Http.encode(keyword))
        val songs = Http.json(text).obj()?.obj("data")?.arr("info").objects()
        val best = songs
            .mapNotNull { song ->
                val hash = song.str("hash") ?: return@mapNotNull null
                val title = song.str("songname") ?: return@mapNotNull null
                val artist = song.str("singername").orEmpty()
                val duration = song.long("duration") ?: 0L
                val album = song.str("album_name").orEmpty()
                Triple(hash, Candidate(title, artist, album, duration), song)
            }
            .filter { Match.accept(query, it.second) }
            .sortedByDescending { Match.score(query, it.second) }
            .take(MAX_SONGS)

        val candidates = mutableListOf<Lyrics>()
        for ((hash, candidate, _) in best) {
            runCatching {
                throttle.await()
                Http.get(LYRIC_SEARCH_URL + Http.encode(hash))
            }.onSuccess { body ->
                val list = Http.json(body).obj()?.arr("candidates").objects()
                list.take(MAX_CANDIDATES).forEach { item ->
                    val id = item.str("id") ?: return@forEach
                    val accessKey = item.str("accesskey") ?: return@forEach
                    candidates += Lyrics(
                        id = "kugou:$id",
                        title = item.str("song") ?: candidate.title,
                        subtitle = (item.str("singer") ?: candidate.artist) + " • KuGou",
                        lyrics = null,
                        extras = mapOf("id" to id, "accesskey" to accessKey),
                    )
                }
            }
        }
        return candidates.distinctBy { it.id }.take(MAX_CANDIDATES)
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val id = lyrics.extras["id"] ?: throw LyricsException("KuGou candidate is missing its lyric id")
        val accessKey = lyrics.extras["accesskey"] ?: throw LyricsException("KuGou candidate is missing its access key")
        throttle.await()
        val url = DOWNLOAD_URL + "&id=" + Http.encode(id) + "&accesskey=" + Http.encode(accessKey)
        val root = Http.json(Http.get(url)).obj() ?: throw LyricsException("Empty KuGou response")
        val encoded = root.str("content") ?: throw LyricsException("KuGou returned no lyric content")
        val lines = decode(encoded)
        if (lines.isEmpty()) throw LyricsException("KuGou lyric payload could not be decoded")
        val wordLevel = lines.any { it.words.size > 1 }
        return lyrics.copy(lyrics = timed(lines, wordLevel))
    }

    private fun decode(encoded: String): List<Line> {
        val raw = Codec.base64(encoded)
        if (raw.size > 4 && raw[0] == 'k'.code.toByte() && raw[1] == 'r'.code.toByte() &&
            raw[2] == 'c'.code.toByte() && raw[3] == '1'.code.toByte()
        ) {
            val body = ByteArray(raw.size - 4)
            for (index in body.indices) {
                body[index] = (raw[index + 4].toInt() xor KRC_KEY[index % KRC_KEY.size].toInt()).toByte()
            }
            // The XORed body is a zlib stream; some older payloads are already plain text.
            for (text in listOfNotNull(inflate(body)?.let { String(it, Charsets.UTF_8) }, String(body, Charsets.UTF_8))) {
                val krc = parseKrc(text)
                if (krc.isNotEmpty()) return krc
                val lrc = Lrc.parse(text)
                if (lrc.isNotEmpty()) return lrc
            }
        }
        return Lrc.parse(String(raw, Charsets.UTF_8))
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(8 * 1024)
        while (!inflater.finished()) {
            val read = inflater.inflate(buffer)
            if (read == 0) break
            out.write(buffer, 0, read)
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        const val KEY_PREFER_WORD = "prefer_word"
        private const val SONG_SEARCH_URL =
            "https://mobiles.kugou.com/api/v3/search/song?version=10000&plat=0&correct=1&pagesize=10"
        private const val LYRIC_SEARCH_URL = "https://lyrics.kugou.com/search?ver=1&man=yes&client=mobi&hash="
        private const val DOWNLOAD_URL =
            "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8"
        private const val MAX_SONGS = 3
        private const val MAX_CANDIDATES = 8
        private val KRC_KEY = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
            206.toByte(), 210.toByte(), 110, 105,
        )

        fun settings(): List<Setting> = listOf(
            SettingSwitch(
                title = "Prefer word timing",
                key = KEY_PREFER_WORD,
                summary = "Use KRC syllable timings when the payload has them.",
                defaultValue = true,
            ),
        )
    }
}

private val KRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
private val KRC_WORD = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

/** Parses a decrypted KRC document: [lineStart,lineDur]<wordOffset,wordDur,0>text. */
internal fun parseKrc(text: String, fallbackEndMs: Long = 0L): List<Line> {
    val lines = mutableListOf<Line>()
    for (raw in text.lines()) {
        val match = KRC_LINE.find(raw.trim()) ?: continue
        val start = match.groupValues[1].toLongOrNull() ?: continue
        val duration = match.groupValues[2].toLongOrNull() ?: 0L
        val body = match.groupValues[3]
        val words = KRC_WORD.findAll(body).mapNotNull { word ->
            val offset = word.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val wordDuration = word.groupValues[2].toLongOrNull() ?: 0L
            val content = word.groupValues[3]
            if (content.isEmpty()) null
            else Word(start + offset, start + offset + maxOf(wordDuration, 1L), content)
        }.toList()
        val plainText = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim()
        else KRC_WORD.replace(body, "").trim()
        if (plainText.isEmpty()) continue
        lines += Line(start, start + maxOf(duration, 1L), plainText, words)
    }
    return Lrc.withEnds(lines.sortedBy { it.startMs }, fallbackEndMs)
}
