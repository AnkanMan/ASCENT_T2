package com.tirthankar.ascent.data

import android.content.Context
import androidx.annotation.StringRes
import com.tirthankar.ascent.engine.QuestTemplate
import com.tirthankar.ascent.engine.TargetUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Resolves template keys and units to user-facing text from strings.xml. */
open class TemplateTitles @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    open fun title(template: QuestTemplate, lightsOut: LocalTime): String {
        val res = titleRes(template.key) ?: return template.key
        return if (template.key == LIGHTS_OUT_KEY) {
            context.getString(res, lightsOut.format(TIME))
        } else {
            context.getString(res)
        }
    }

    open fun unitLabel(template: QuestTemplate): String =
        if (template.key == WATER_KEY) context.getString(R.string.unit_glasses) else unitLabel(template.unit)

    open fun unitLabel(unit: TargetUnit): String = context.getString(
        when (unit) {
            TargetUnit.REPS -> R.string.unit_reps
            TargetUnit.MINUTES -> R.string.unit_minutes
            TargetUnit.STEPS -> R.string.unit_steps
            TargetUnit.PAGES -> R.string.unit_pages
            TargetUnit.KM -> R.string.unit_km
            TargetUnit.SECONDS -> R.string.unit_seconds
            TargetUnit.HOURS -> R.string.unit_hours
            TargetUnit.OTHER -> R.string.unit_other
        },
    )

    open fun startingTitle(): String = context.getString(R.string.player_starting_title)

    /** "Phase 2" for a boss phase you didn't name. */
    open fun defaultPhaseName(number: Int): String = context.getString(R.string.boss_default_phase, number)

    /** "Trial: Push-ups". */
    open fun trialTitle(template: QuestTemplate): String =
        context.getString(R.string.boss_trial_name, titleRes(template.key)?.let(context::getString) ?: template.key)

    /** First Principles' quest: "Derivation: …" (Phase 4). */
    open fun derivation(name: String): String = context.getString(R.string.quest_derivation, name.trim())

    /** The title a defeated boss grants, e.g. "Resolver of The Draft". */
    open fun bossTitle(bossName: String): String = context.getString(R.string.title_boss_resolver, bossName)

    companion object {
        const val LIGHTS_OUT_KEY = "self_lights_out"
        private const val WATER_KEY = "self_water"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        @StringRes
        fun titleRes(key: String): Int? = when (key) {
            "body_push_ups" -> R.string.template_body_push_ups
            "body_plank" -> R.string.template_body_plank
            "body_incline_push_ups" -> R.string.template_body_incline_push_ups
            "body_squats" -> R.string.template_body_squats
            "body_lunges" -> R.string.template_body_lunges
            "body_glute_bridges" -> R.string.template_body_glute_bridges
            "body_steps" -> R.string.template_body_steps
            "body_brisk_walk" -> R.string.template_body_brisk_walk
            "body_mobility" -> R.string.template_body_mobility
            "body_easy_walk" -> R.string.template_body_easy_walk
            "body_stretch" -> R.string.template_body_stretch
            "mind_deep_work" -> R.string.template_mind_deep_work
            "mind_phone_free_block" -> R.string.template_mind_phone_free_block
            "mind_derivation_page" -> R.string.template_mind_derivation_page
            "mind_paper_summary" -> R.string.template_mind_paper_summary
            "mind_simulation_run" -> R.string.template_mind_simulation_run
            "mind_research_log" -> R.string.template_mind_research_log
            "mind_close_todo" -> R.string.template_mind_close_todo
            "mind_plan_tomorrow" -> R.string.template_mind_plan_tomorrow
            LIGHTS_OUT_KEY -> R.string.template_self_lights_out
            "self_reading" -> R.string.template_self_reading
            "self_journal" -> R.string.template_self_journal
            "self_message_someone" -> R.string.template_self_message_someone
            "self_new_skill" -> R.string.template_self_new_skill
            WATER_KEY -> R.string.template_self_water
            "self_protein" -> R.string.template_self_protein
            "self_cook_meal" -> R.string.template_self_cook_meal
            "self_daylight" -> R.string.template_self_daylight
            "self_breathing" -> R.string.template_self_breathing
            "self_screens_off" -> R.string.template_self_screens_off
            "self_phone_free_morning" -> R.string.template_self_phone_free_morning
            else -> null
        }
    }
}
