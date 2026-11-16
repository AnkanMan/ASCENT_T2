package com.tirthankar.ascent.data.repo

import kotlin.random.Random

/** Short ids Claude can quote: "q7f2a" for quests, "g12" for goals. */
internal object PublicIds {

    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789" // no l, o, 0, 1

    suspend fun newQuestId(random: Random, exists: suspend (String) -> Boolean): String {
        repeat(1_000) {
            val id = "q" + (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            if (!exists(id)) return id
        }
        error("could not find a free quest id")
    }

    fun goalId(sequence: Long): String = "g$sequence"
}
