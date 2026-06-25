package com.lightmusic.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.lightmusic.player.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var musicService: MusicService? = null
    private var isBound = false
    private lateinit var adapter: SongAdapter
    private val songs = mutableListOf<Song>()
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            musicService = (binder as MusicService.MusicBinder).getService()
            isBound = true
            musicService?.songs = songs
            musicService?.onSongChanged = { index ->
                runOnUiThread { updateSongInfo(index) }
            }
            musicService?.onPlayStateChanged = { playing ->
                runOnUiThread { updatePlayButton(playing) }
            }
            // sync UI with any existing service state
            musicService?.let { service ->
                if (service.currentIndex < songs.size) updateSongInfo(service.currentIndex)
                updatePlayButton(service.isPlaying())
                updateRepeatButton(service.repeatMode)
                binding.btnShuffle.alpha = if (service.isShuffle) 1f else 0.4f
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
        }
    }

    private val seekRunnable = object : Runnable {
        override fun run() {
            val service = musicService ?: return
            val pos = service.currentPosition()
            val dur = service.duration()
            if (dur > 0) {
                binding.seekBar.max = dur
                binding.seekBar.progress = pos
                binding.tvCurrentTime.text = formatTime(pos)
                binding.tvTotalTime.text = formatTime(dur)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupControls()
        binding.tvNowPlaying.isSelected = true
        checkPermissionAndLoad()

        Intent(this, MusicService::class.java).also {
            startService(it)
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun setupRecyclerView() {
        adapter = SongAdapter(songs) { index ->
            musicService?.playSong(index)
        }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            if (songs.isEmpty()) return@setOnClickListener
            val service = musicService ?: return@setOnClickListener
            if (service.mediaPlayer == null) {
                service.playSong(0)
            } else {
                service.togglePlayPause()
            }
        }

        binding.btnNext.setOnClickListener { musicService?.playNext() }
        binding.btnPrev.setOnClickListener { musicService?.playPrevious() }

        binding.btnShuffle.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            service.isShuffle = !service.isShuffle
            binding.btnShuffle.alpha = if (service.isShuffle) 1f else 0.4f
        }

        binding.btnRepeat.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            service.repeatMode = when (service.repeatMode) {
                MusicService.RepeatMode.NONE -> MusicService.RepeatMode.ALL
                MusicService.RepeatMode.ALL -> MusicService.RepeatMode.ONE
                MusicService.RepeatMode.ONE -> MusicService.RepeatMode.NONE
            }
            updateRepeatButton(service.repeatMode)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) musicService?.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun updateRepeatButton(mode: MusicService.RepeatMode) {
        when (mode) {
            MusicService.RepeatMode.NONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.alpha = 0.4f
            }
            MusicService.RepeatMode.ALL -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.alpha = 1f
            }
            MusicService.RepeatMode.ONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.alpha = 1f
            }
        }
    }

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        } else {
            Toast.makeText(this, "Storage permission needed to read music files", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSongs() {
        songs.clear()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                songs.add(
                    Song(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown Artist",
                        duration = cursor.getLong(durCol),
                        path = cursor.getString(pathCol) ?: continue
                    )
                )
            }
        }

        adapter.notifyDataSetChanged()
        musicService?.songs = songs

        if (songs.isEmpty()) {
            binding.tvNowPlaying.text = "No music found on device"
        } else {
            binding.tvNowPlaying.text = "${songs.size} songs found"
        }
    }

    private fun updateSongInfo(index: Int) {
        val song = songs.getOrNull(index) ?: return
        binding.tvNowPlaying.text = song.title
        binding.tvArtistName.text = song.artist
        adapter.setCurrentPosition(index)
        binding.recyclerView.smoothScrollToPosition(index)
    }

    private fun updatePlayButton(playing: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun formatTime(ms: Int): String {
        val sec = ms / 1000
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    override fun onResume() {
        super.onResume()
        handler.post(seekRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(seekRunnable)
    }

    override fun onDestroy() {
        if (isBound) unbindService(connection)
        super.onDestroy()
    }
}
