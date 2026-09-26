package com.skg.myfm

import android.net.Uri

data class AudioItem(
    val id: Long,
    val title: String,
    val contentUri: Uri,
    val artist: String,
    val duration: Long,
    val folderName: String = "Internal Storage",
    val folderPath: String = "",
    val isCompleted: Boolean = false,
    val lastPositionMs: Long = 0L
) {
    val progressPercentage: Float
        get() = if (duration > 0) (lastPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
}
