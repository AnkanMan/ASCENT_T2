package com.tirthankar.ascent.data

import android.content.Context
import com.tirthankar.ascent.engine.ContentBundle
import com.tirthankar.ascent.engine.ContentCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Game content shipped as JSON in assets/content (SPEC "Data model additions"):
 * loot, items, Gates, quest tasks, achievements, arcs and lore. Loaded once.
 * The engine's ContentValidator runs over the same files in unit tests.
 */
@Singleton
open class ContentRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    open val content: ContentBundle by lazy {
        ContentCodec.load { name -> context.assets.open("$DIR/$name").bufferedReader().use { it.readText() } }
    }

    private companion object {
        const val DIR = "content"
    }
}
