package com.lightmusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    companion object {
        const val ACTION_PREV = "com.lightmusic.player.ACTION_PREV"
        const val ACTION_PLAY_PAUSE = "com.lightmusic.player.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.lightmusic.player.ACTION_NEXT"
    }

    enum class RepeatMode { NONE, ALL, ONE }

    inner class MusicBinder : Binder() {
        fun getService() = this@MusicService
    }

    private val binder = MusicBinder()
    var mediaPlayer: MediaPlayer? = null
    var songs = listOf<Song>()
    var currentIndex = 0
    var isShuffle = false
    var repeatMode = RepeatMode.NONE
    var onSongChanged: ((Int) -> Unit)? = null
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    private val channelId = "music_channel"
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
                if (wasPlayingBeforeFocusLoss && !isPlaying()) {
                    mediaPlayer?.start()
                    onPlayStateChanged?.invoke(true)
                    updateNotification()
                    updatePlaybackState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = false
                if (isPlaying()) pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss = isPlaying()
                if (isPlaying()) pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.3f, 0.3f)
            }
        }
    }

    private fun pauseForFocusLoss() {
        mediaPlayer?.pause()
        onPlayStateChanged?.invoke(false)
        updateNotification()
        updatePlaybackState()
    }

    // Pauses music when headphones are unplugged
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isPlaying()) {
                mediaPlayer?.pause()
                onPlayStateChanged?.invoke(false)
                updateNotification()
                updatePlaybackState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
    }

    // Handles notification action buttons (prev / play-pause / next)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> playPrevious()
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "LightMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (!isPlaying()) togglePlayPause() }
                override fun onPause() { if (isPlaying()) togglePlayPause() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
            })
            isActive = true
        }
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    fun playSong(index: Int) {
        if (songs.isEmpty()) return
        currentIndex = index
        if (!requestAudioFocus()) return
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
            setOnCompletionListener { onSongCompleted() }
        }
        onSongChanged?.invoke(currentIndex)
        onPlayStateChanged?.invoke(true)
        updateMetadata()
        updatePlaybackState()
        startForeground(1, buildNotification())
    }

    private fun onSongCompleted() {
        when (repeatMode) {
            RepeatMode.ONE -> {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
                updatePlaybackState()
            }
            RepeatMode.ALL -> playNext()
            RepeatMode.NONE -> {
                if (isShuffle || currentIndex < songs.size - 1) {
                    playNext()
                } else {
                    onPlayStateChanged?.invoke(false)
                    updateNotification()
                    updatePlaybackState()
                }
            }
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            onPlayStateChanged?.invoke(false)
        } else {
            if (!requestAudioFocus()) return
            mp.start()
            onPlayStateChanged?.invoke(true)
        }
        updateNotification()
        updatePlaybackState()
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
            updatePlaybackState()
            return
        }
        val prev = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
        playSong(prev)
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
        updatePlaybackState()
    }

    fun isPlaying() = mediaPlayer?.isPlaying == true

    fun currentPosition() = mediaPlayer?.currentPosition ?: 0

    fun duration() = mediaPlayer?.duration ?: 0

    private fun updateMetadata() {
        val song = songs.getOrNull(currentIndex) ?: return
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                .build()
        )
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, currentPosition().toLong(), 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification())
    }

    override fun onDestroy() {
        mediaSession.release()
        abandonAudioFocus()
        unregisterReceiver(noisyReceiver)
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

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(): Notification {
        val song = songs.getOrNull(currentIndex)
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val playing = isPlaying()
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(song?.title ?: "LightMusic")
            .setContentText(song?.artist ?: "")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(openAppIntent)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, "Previous", actionPendingIntent(ACTION_PREV))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                actionPendingIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(R.drawable.ic_skip_next, "Next", actionPendingIntent(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
