package com.tirthankar.ascent.feature.skills

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.LimitPeriod
import com.tirthankar.ascent.engine.PowerRules
import com.tirthankar.ascent.engine.SkillDef

/** "40 MP · Once a week", or null for powers that trigger by themselves. */
@Composable
internal fun costLine(def: SkillDef, mp: Int = def.mp): String? {
    if (def.handler in PowerRules.AUTOMATIC) return null
    val cost = if (mp > 0) stringResource(R.string.skills_cost_mp, mp) else stringResource(R.string.skills_cost_free)
    val limit = def.limit ?: return cost
    val per = when {
        limit.wisPerCharge != null -> stringResource(R.string.skills_limit_insight, limit.wisPerCharge ?: 0)
        limit.period == LimitPeriod.DAY -> stringResource(R.string.skills_limit_day)
        limit.period == LimitPeriod.WEEK -> stringResource(R.string.skills_limit_week)
        limit.period == LimitPeriod.MONTH -> stringResource(R.string.skills_limit_month)
        else -> stringResource(R.string.skills_limit_hours, limit.hours ?: 0)
    }
    return "$cost · $per"
}

/** The one line saying why a power can't be cast now (or that it's ready). */
@Composable
internal fun castText(check: CastCheck): String = when (check) {
    CastCheck.Ok -> stringResource(R.string.skills_cast_ready)
    CastCheck.NotUnlocked -> stringResource(R.string.skills_cast_not_unlocked)
    CastCheck.NotCastable -> stringResource(R.string.skills_cast_not_ready)
    is CastCheck.NotEnoughMp -> stringResource(R.string.skills_cast_no_mp, check.needed, check.have)
    is CastCheck.LimitReached -> stringResource(
        when (check.period) {
            LimitPeriod.WEEK -> R.string.skills_cast_used_week
            LimitPeriod.MONTH -> R.string.skills_cast_used_month
            else -> R.string.skills_cast_used_day
        },
    )
    is CastCheck.OnCooldown -> if (check.minutesLeft >= 60) {
        stringResource(R.string.skills_cast_cooldown_hours, (check.minutesLeft / 60).toInt(), (check.minutesLeft % 60).toInt())
    } else {
        stringResource(R.string.skills_cast_cooldown_minutes, check.minutesLeft.toInt())
    }
    CastCheck.NeedsTarget -> stringResource(R.string.skills_cast_needs_target)
    CastCheck.RestDay -> stringResource(R.string.skills_cast_rest_day)
    CastCheck.NoHeadroom -> stringResource(R.string.skills_cast_no_headroom)
    CastCheck.NoMissToRestore -> stringResource(R.string.skills_cast_no_miss)
}
