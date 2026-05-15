package com.example.ubereatsoverlay

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot
import androidx.media.MediaBrowserServiceCompat.Result

class RangerHudMediaService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        activeService = this
        mediaSession = MediaSessionCompat(this, "RangerHudMedia").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        updateMediaState()
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, browserRootExtras())
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(
            when (parentId) {
                ROOT_ID -> mutableListOf(buildHudItem())
                else -> mutableListOf()
            }
        )
    }

    override fun onLoadItem(
        itemId: String,
        result: Result<MediaBrowserCompat.MediaItem>
    ) {
        result.sendResult(if (itemId == HUD_MEDIA_ID) buildHudItem() else null)
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = updateMediaState()

        override fun onPause() = updateMediaState()

        override fun onSkipToNext() = updateMediaState()

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = updateMediaState()

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) = updateMediaState()
    }

    private fun updateMediaState() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, HUD_MEDIA_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, HudState.mediaTitleLine())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, HudState.mediaArtistLine())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, HudState.mediaAlbumLine())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, HudState.mediaTitleLine())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, HudState.mediaArtistLine())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, HudState.mediaAlbumLine())
                .build()
        )
        mediaSession.setQueueTitle("Ranger Profit HUD")
        mediaSession.setQueue(listOf(MediaSessionCompat.QueueItem(buildHudDescription(), 1L)))
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_ACTIONS)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                .build()
        )
    }

    private fun buildHudItem(): MediaBrowserCompat.MediaItem {
        return MediaBrowserCompat.MediaItem(
            buildHudDescription(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun buildHudDescription(): MediaDescriptionCompat {
        return MediaDescriptionCompat.Builder()
            .setMediaId(HUD_MEDIA_ID)
            .setTitle(HudState.mediaTitleLine())
            .setSubtitle(HudState.mediaArtistLine())
            .setDescription(HudState.mediaAlbumLine())
            .build()
    }

    private fun browserRootExtras(): Bundle {
        return Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID)
        }
    }

    companion object {
        private const val ROOT_ID = "ranger_hud_root"
        private const val HUD_MEDIA_ID = "ranger_hud_now"
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_PLAYABLE_HINT =
            "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_GRID = 2
        private const val MEDIA_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID

        private var activeService: RangerHudMediaService? = null

        fun refresh() {
            activeService?.let {
                it.updateMediaState()
                it.notifyChildrenChanged(ROOT_ID)
            }
        }
    }
}
