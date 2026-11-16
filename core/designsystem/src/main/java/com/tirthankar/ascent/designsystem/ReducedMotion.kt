package com.tirthankar.ascent.designsystem

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/** True when the system "Remove animations" setting is on. Provided by [AscentTheme]. */
val LocalReducedMotion = compositionLocalOf { false }

/** "Remove animations" sets the animator duration scale to 0. */
internal fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

private fun ContentResolver.readReducedMotion(): Boolean =
    runCatching {
        isReducedMotion(Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }.getOrDefault(false)

/** Reads the setting and follows changes while the composition is alive. */
@Composable
internal fun rememberSystemReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false // Previews have no system settings.
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(resolver.readReducedMotion()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = resolver.readReducedMotion()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
