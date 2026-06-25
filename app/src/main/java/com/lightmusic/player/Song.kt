package com.lightmusic.player

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val path: String
) {
    fun formattedDuration(): String {
        val minutes = (duration / 1000) / 60
        val seconds = (duration / 1000) % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
