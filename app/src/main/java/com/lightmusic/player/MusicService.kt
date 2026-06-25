package com.lightmusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    inner class MusicBinder : Binder() {
        fun getService() = this@MusicService
    }

    private val binder = MusicBinder()
    var mediaPlayer: MediaPlayer? = null
    var songs = listOf<Song>()
    var currentIndex = 0
    var isShuffle = false
    var onSongChanged: ((Int) -> Unit)? = null
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    private val channelId = "music_channel"

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun playSong(index: Int) {
        if (songs.isEmpty()) return
        currentIndex = index
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(songs[currentIndex].path)
            prepare()
            start()
            setOnCompletionListener { playNext() }
        }
        onSongChanged?.invoke(currentIndex)
        onPlayStateChanged?.invoke(true)
        startForeground(1, buildNotification())
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            onPlayStateChanged?.invoke(false)
        } else {
            mp.start()
            onPlayStateChanged?.invoke(true)
        }
        startForeground(1, buildNotification())
    }

    fun playNext() {
        if (songs.isEmpty()) return
        val next = if (isShuffle) (0 until songs.size).random() else (currentIndex + 1) % songs.size
        playSong(next)
    }

    fun playPrevious() {
        if (songs.isEmpty()) return
        val position = mediaPlayer?.currentPosition ?: 0
        if (position > 3000) {
            mediaPlayer?.seekTo(0)
            return
        }
        val prev = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
        playSong(prev)
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
    }

    fun isPlaying() = mediaPlayer?.isPlaying == true

    fun currentPosition() = mediaPlayer?.currentPosition ?: 0

    fun duration() = mediaPlayer?.duration ?: 0

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Music Player", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val song = songs.getOrNull(currentIndex)
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(song?.title ?: "LightMusic")
            .setContentText(song?.artist ?: "")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
