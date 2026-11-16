package com.tirthankar.ascent.designsystem

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The System's sounds (SPEC "Sound"), each paired with a haptic pulse. Files: docs/sounds/SOUNDS.md. */
enum class Sfx(internal val res: Int, internal val pulseMillis: Long, internal val amplitude: Int) {
    SYSTEM_CHIME(R.raw.sfx_system_chime, 20, 80),
    QUEST_COMPLETE(R.raw.sfx_quest_complete, 15, 120),
    LEVEL_UP(R.raw.sfx_level_up, 60, 200),
    PENALTY(R.raw.sfx_penalty, 80, 160),
    BOSS_HIT(R.raw.sfx_boss_hit, 45, 255),
    CHEST_OPEN(R.raw.sfx_chest_open, 40, 180),
    UNLOCK(R.raw.sfx_unlock, 30, 140),

    // Phase 4
    CAST(R.raw.sfx_cast, 35, 170),
    SKILL_UNLOCK(R.raw.sfx_skill_unlock, 25, 140),
    CLASS(R.raw.sfx_class, 50, 160),
    RANK_UP(R.raw.sfx_rank_up, 80, 230),
}

/**
 * Plays [Sfx] through SoundPool. Silent when the phone is on silent or vibrate, or when
 * sound is off in Settings; haptics follow their own setting and the ringer.
 */
class SystemSounds(private val context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids: Map<Sfx, Int> = Sfx.entries.associateWith { pool.load(context, it.res, 1) }
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** VIBRATE is a normal (install-time) permission declared in the app manifest. */
    @SuppressLint("MissingPermission")
    fun play(sfx: Sfx, soundOn: Boolean, hapticsOn: Boolean) {
        val ringer = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (soundOn && ringer == AudioManager.RINGER_MODE_NORMAL) {
            ids[sfx]?.let { pool.play(it, VOLUME, VOLUME, 1, 0, 1f) }
        }
        if (hapticsOn && ringer != AudioManager.RINGER_MODE_SILENT) {
            vibrator?.takeIf { it.hasVibrator() }?.vibrate(VibrationEffect.createOneShot(sfx.pulseMillis, sfx.amplitude))
        }
    }

    fun release() = pool.release()

    private companion object {
        const val VOLUME = 0.7f
    }
}
