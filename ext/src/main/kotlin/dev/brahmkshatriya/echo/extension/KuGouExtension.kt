package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Echo entry point for KuGou Music. Echo creates one instance per process, calls
 * [setSettings] before use, and calls [searchTrackLyrics] whenever the lyrics panel opens.
 */
class KuGouExtension : LyricsClient {

    private var settings: Settings? = null
    private val source: LyricsSource = KuGouSource()

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> = KuGouSource.settings()

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> =
        PagedData.Single { source.search(track, settings) }.toFeed(Feed.Buttons.EMPTY)

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        val loaded = source.load(lyrics, settings)
        if (loaded.lyrics == null) throw LyricsException("No lyrics available for this candidate")
        return loaded
    }
}
