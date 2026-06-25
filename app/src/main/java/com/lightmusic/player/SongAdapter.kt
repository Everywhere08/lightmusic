package com.lightmusic.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private val songs: List<Song>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    private var currentPosition = -1

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvTitle)
        val artist: TextView = view.findViewById(R.id.tvArtist)
        val duration: TextView = view.findViewById(R.id.tvDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.artist.text = song.artist
        holder.duration.text = song.formattedDuration()
        holder.itemView.isSelected = position == currentPosition
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount() = songs.size

    fun setCurrentPosition(pos: Int) {
        val old = currentPosition
        currentPosition = pos
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(pos)
    }
}
