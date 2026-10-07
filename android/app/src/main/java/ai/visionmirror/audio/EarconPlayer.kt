package ai.visionmirror.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class Earcon { Ready, Closer, Captured, ListeningOn, ListeningOff, Error, CountdownTick }

interface Earcons {
    fun play(earcon: Earcon)
}

/**
 * Original earcons, synthesised at start-up (soft bell-like sines with exponential decay),
 * so there are no audio assets to license or ship. Played with the accessibility usage so they
 * sit alongside TalkBack rather than fighting media volume.
 */
@Singleton
class EarconPlayer @Inject constructor() : Earcons {

    private val tracks = HashMap<Earcon, AudioTrack>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var enabled: Boolean = true

    init {
        scope.launch {
            Earcon.entries.forEach { e ->
                val pcm = synth(e)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                synchronized(tracks) { tracks[e] = track }
            }
        }
    }

    override fun play(earcon: Earcon) {
        if (!enabled) return
        val track = synchronized(tracks) { tracks[earcon] } ?: return
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() = synchronized(tracks) {
        tracks.values.forEach { it.release() }
        tracks.clear()
    }

    // ---- synthesis -------------------------------------------------------------------------

    private class Note(val freq: Float, val ms: Int, val gain: Float = 1f, val glideTo: Float = freq)

    private fun synth(e: Earcon): ShortArray = when (e) {
        // Two rising bell notes: "settled".
        Earcon.Ready -> sequence(0.55f, Note(659f, 170), Note(988f, 320))
        // One soft, short blip; the caller repeats it faster as framing improves.
        Earcon.Closer -> sequence(0.4f, Note(880f, 70))
        // Dry click plus a low thump, like a shutter.
        Earcon.Captured -> mix(noiseClick(28, 0.5f), sequence(0.6f, Note(196f, 140)))
        Earcon.ListeningOn -> sequence(0.5f, Note(440f, 150, glideTo = 660f))
        Earcon.ListeningOff -> sequence(0.5f, Note(660f, 150, glideTo = 440f))
        // Two falling low notes: unlike any of the happy ones.
        Earcon.Error -> sequence(0.55f, Note(330f, 150), Note(247f, 260))
        Earcon.CountdownTick -> sequence(0.45f, Note(740f, 60))
    }

    private fun sequence(master: Float, vararg notes: Note): ShortArray {
        val total = notes.sumOf { it.ms } * SAMPLE_RATE / 1000
        val out = FloatArray(total)
        var offset = 0
        for (n in notes) {
            val len = n.ms * SAMPLE_RATE / 1000
            var phase = 0.0
            for (i in 0 until len) {
                val t = i / len.toFloat()
                val f = n.freq + (n.glideTo - n.freq) * t
                phase += 2 * PI * f / SAMPLE_RATE
                val attack = (i / (0.008f * SAMPLE_RATE)).coerceAtMost(1f) // 8 ms, avoids clicks
                val decay = exp(-4.5f * t)
                // Fundamental plus a quiet 2nd partial gives a glassy, bell-like body.
                val s = (sin(phase) + 0.25 * sin(2 * phase)).toFloat()
                out[offset + i] += s * attack * decay * n.gain * master
            }
            offset += len
        }
        return out.toPcm()
    }

    private fun noiseClick(ms: Int, gain: Float): ShortArray {
        val len = ms * SAMPLE_RATE / 1000
        val rnd = Random(7)
        return FloatArray(len) { i ->
            (rnd.nextFloat() * 2 - 1) * exp(-9f * i / len) * gain
        }.toPcm()
    }

    private fun mix(a: ShortArray, b: ShortArray): ShortArray {
        val out = ShortArray(maxOf(a.size, b.size))
        for (i in out.indices) {
            val s = (a.getOrElse(i) { 0 } + b.getOrElse(i) { 0 }).coerceIn(-32767, 32767)
            out[i] = s.toShort()
        }
        return out
    }

    private fun FloatArray.toPcm() = ShortArray(size) { (this[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }

    private companion object {
        const val SAMPLE_RATE = 22050
    }
}
