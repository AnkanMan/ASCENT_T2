package com.tirthankar.ascent.engine

import java.io.File

/** The real content files that ship in the app (core/data assets), loaded for tests. */
object TestContent {
    private val dir = File("../data/src/main/assets/content")

    val bundle: ContentBundle by lazy { ContentCodec.load { name -> File(dir, name).readText() } }
}
