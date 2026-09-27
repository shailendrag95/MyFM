package com.skg.myfm

import android.content.Context
import android.content.SharedPreferences

class PlaybackProgressManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("audio_playback_prefs", Context.MODE_PRIVATE)
    private var lastSaveTimeMs: Long = 0L

    fun saveProgress(audioId: Long, positionMs: Long, durationMs: Long, forceSave: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        // Throttle disk writes to every 5 seconds unless forceSave is true
        if (!forceSave && (currentTime - lastSaveTimeMs < 5000L)) {
            return
        }
        lastSaveTimeMs = currentTime

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
