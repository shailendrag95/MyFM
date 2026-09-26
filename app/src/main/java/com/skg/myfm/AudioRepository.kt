package com.skg.myfm

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioRepository(private val context: Context) {

    private val progressManager = PlaybackProgressManager(context)

    suspend fun getAudioFiles(): List<AudioItem> = withContext(Dispatchers.IO) {
        val audioList = mutableListOf<AudioItem>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Audio.Media.DATA
        )

        // Query only music/audio files
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val bucketColumn = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown Title"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val duration = cursor.getLong(durationColumn)

                val dataPath = if (dataColumn != -1) cursor.getString(dataColumn) else ""
                val folderPath = if (!dataPath.isNullOrEmpty()) File(dataPath).parent ?: "" else ""

                val bucketName = if (bucketColumn != -1) cursor.getString(bucketColumn) else null
                val folderName = when {
                    !bucketName.isNullOrEmpty() -> bucketName
                    folderPath.isNotEmpty() -> File(folderPath).name
                    else -> "Internal Storage"
                }

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                val isCompleted = progressManager.isTrackCompleted(id)
                val lastPos = progressManager.getSavedPosition(id)

                audioList.add(
                    AudioItem(
                        id = id,
                        title = title,
                        contentUri = contentUri,
                        artist = artist,
                        duration = duration,
                        folderName = folderName,
                        folderPath = folderPath,
                        isCompleted = isCompleted,
                        lastPositionMs = lastPos
                    )
                )
            }
        }

        audioList
    }
}
