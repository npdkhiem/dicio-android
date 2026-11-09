package org.stypox.dicio.skills.local_music

/**
 * What may refresh or clear the 10-minute routing window
 * ([LocalMusicRouting.RECENT_ACTIVITY_WINDOW_MS]).
 *
 * Refreshed by a user command that the local player actually handles, and by
 * a track change while that player is playing (including the start of
 * playback). A permanent audio-focus loss — ExoPlayer sets play-when-ready
 * false with [androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS]
 * — clears the window so the next generic command goes back to MediaSkill.
 *
 * Not refreshed: a passive
 * [androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED] (the pause that
 * follows focus loss, ducking, or a transient suppression), and any command
 * that [SharedPlayback] sends as a media key. Those paths must not call
 * [Effect.Refresh].
 */
object LocalMusicActivity {
    enum class Signal {
        /** Play, pause, next, previous, stop, repeat, or play-by-name on the local player. */
        UserCommand,

        /** The current item changed while [androidx.media3.common.Player.isPlaying] is true. */
        TrackTransitionWhilePlaying,

        /** Another app took audio focus for good. The local player was paused. */
        PermanentAudioFocusLoss,

        /** isPlaying changed for a reason other than a user command. */
        PassivePlaybackChange,

        /** The command was dispatched to another app with a media key. */
        MediaKeyCommand,
    }

    enum class Effect {
        Refresh,
        Clear,
        Keep,
    }

    fun effect(signal: Signal): Effect = when (signal) {
        Signal.UserCommand,
        Signal.TrackTransitionWhilePlaying,
        -> Effect.Refresh
        Signal.PermanentAudioFocusLoss -> Effect.Clear
        Signal.PassivePlaybackChange,
        Signal.MediaKeyCommand,
        -> Effect.Keep
    }
}
