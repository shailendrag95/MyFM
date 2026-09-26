package com.skg.myfm

import android.content.Context
import android.content.SharedPreferences

class PlaybackProgressManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("audio_playback_prefs", Context.MODE_PRIVATE)

    fun saveProgress(audioId: Long, positionMs: Long, durationMs: Long) {
        val isCompleted = isTrackCompleted(audioId) || (durationMs > 0 && positionMs >= (durationMs - 3000))
        prefs.edit()
            .putLong("pos_$audioId", positionMs)
            .putBoolean("completed_$audioId", isCompleted)
            .apply()
    }

    fun markCompleted(audioId: Long) {
        prefs.edit()
            .putBoolean("completed_$audioId", true)
            .apply()
    }

    fun getSavedPosition(audioId: Long): Long {
        return prefs.getLong("pos_$audioId", 0L)
    }

    fun isTrackCompleted(audioId: Long): Boolean {
        return prefs.getBoolean("completed_$audioId", false)
    }

    fun clearTrackProgress(audioId: Long) {
        prefs.edit()
            .remove("pos_$audioId")
            .remove("completed_$audioId")
            .apply()
    }

    fun savePlaybackSpeed(speed: Float) {
        prefs.edit()
            .putFloat("saved_playback_speed", speed)
            .apply()
    }

    fun getSavedPlaybackSpeed(): Float {
        return prefs.getFloat("saved_playback_speed", 1.0f)
    }
}
