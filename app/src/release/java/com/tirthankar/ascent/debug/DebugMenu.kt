package com.tirthankar.ascent.debug

import androidx.compose.runtime.Composable
import com.tirthankar.ascent.data.SystemTimeSource
import com.tirthankar.ascent.data.TimeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Release build: no debug panel, and the real clock.

/** The Settings entry for the debug panel: nothing in release. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DebugMenuEntry(onOpen: () -> Unit) = Unit

/** The debug panel: nothing in release. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DebugPanelRoute(onBack: () -> Unit) = Unit

@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    abstract fun timeSource(impl: SystemTimeSource): TimeSource
}
