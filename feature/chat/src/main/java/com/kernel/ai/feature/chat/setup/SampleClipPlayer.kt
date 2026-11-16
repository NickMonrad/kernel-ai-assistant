package com.kernel.ai.feature.chat.setup

import android.content.Context
import android.media.MediaPlayer
import androidx.annotation.RawRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Plays the bundled voice samples in step 2 (res/raw, no download). One clip at a time: [toggle] on the clip that is
 * playing stops it; on another clip it switches. [playing] is the clip now playing, or null.
 */
internal class SampleClipPlayer(private val context: Context) {
    var playing: Int? by mutableStateOf(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(@RawRes clip: Int) {
        val wasPlaying = playing
        release()
        if (wasPlaying == clip) return
        player = MediaPlayer.create(context, clip)?.apply {
            setOnCompletionListener { release() }
            start()
        }
        playing = clip.takeIf { player != null }
    }

    /** Stops and frees the player. Called on a second tap, on completion and when the step leaves composition. */
    fun release() {
        player?.release()
        player = null
        playing = null
    }
}
