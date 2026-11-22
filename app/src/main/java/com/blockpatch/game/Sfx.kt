package com.blockpatch.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/** Low-latency effects via SoundPool + looping background music via MediaPlayer. */
class Sfx(private val ctx: Context, private val prof: Profile) {
    private val pool: SoundPool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = HashMap<String, Int>()
    private val lastPlay = HashMap<String, Long>()
    private var mp: MediaPlayer? = null
    private var musicWanted = false

    init {
        val names = listOf("punch", "break", "place", "jump", "djump", "step", "land", "pickup", "gem", "lock", "unlock",
            "door", "deny", "notify", "chat", "levelup", "equip", "death", "plant", "flap", "ripe", "harvest", "buy", "dig")
        for (n in names) try {
            ctx.assets.openFd("snd/$n.wav").use { ids[n] = pool.load(it, 1) }
        } catch (_: Exception) { }
    }

    @Synchronized fun play(name: String, vol: Float = 1f, rate: Float = 1f) {
        if (!prof.sound) return
        val id = ids[name] ?: return
        val now = System.nanoTime()
        val last = lastPlay[name] ?: 0L
        if (now - last < 35_000_000L) return      // avoid stacking identical sounds
        lastPlay[name] = now
        pool.play(id, vol, vol, 1, 0, rate)
    }

    fun startMusic() {
        musicWanted = true
        if (!prof.music) return
        try {
            if (mp == null) {
                val m = MediaPlayer()
                ctx.assets.openFd("snd/music.wav").use { m.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                m.isLooping = true; m.setVolume(0.35f, 0.35f); m.prepare(); mp = m
            }
            mp?.start()
        } catch (_: Exception) { mp?.release(); mp = null }
    }

    fun pauseMusic() { musicWanted = false; try { mp?.pause() } catch (_: Exception) { } }

    fun applyMusicSetting() {
        if (!prof.music) { try { mp?.pause() } catch (_: Exception) { } } else if (musicWanted) startMusic()
    }

    fun release() {
        try { mp?.release() } catch (_: Exception) { }
        mp = null
        pool.release()
    }
}
