package com.tirthankar.ascent.system

import android.content.Context
import com.tirthankar.ascent.designsystem.SystemSounds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** One SoundPool for the app's lifetime (Task 36). */
    @Provides
    @Singleton
    fun systemSounds(@ApplicationContext context: Context): SystemSounds = SystemSounds(context)
}
