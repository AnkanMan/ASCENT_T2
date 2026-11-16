package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.AttributeEntity
import com.tirthankar.ascent.data.db.BaselineEntity
import com.tirthankar.ascent.data.db.PlayerEntity
import com.tirthankar.ascent.data.db.RealmDifficultyEntity
import com.tirthankar.ascent.data.model.PlayerProfile
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.AwakeningMeasurements
import com.tirthankar.ascent.engine.BaselineInputs
import com.tirthankar.ascent.engine.Baselines
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.DifficultyController
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.SafetyRules
import com.tirthankar.ascent.engine.StartingStats
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.StatBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Everything the Awakening collects. */
data class AwakeningInput(
    val name: String,
    val measurements: AwakeningMeasurements,
    val baselines: BaselineInputs,
    val settings: AscentSettings,
)

@Singleton
class PlayerRepository @Inject constructor(
    private val db: AscentDatabase,
    private val settings: SettingsRepository,
    private val titles: TemplateTitles,
    private val time: TimeSource,
    private val gameDay: GameDay,
    private val random: Random,
) {
    private val dao = db.playerDao()

    val hasPlayer: Flow<Boolean> = dao.observePlayer().map { it != null }

    val profile: Flow<PlayerProfile?> = combine(
        dao.observePlayer(),
        dao.observeAttributes(),
        dao.observeDifficulties(),
    ) { player, attributes, difficulties ->
        player?.let { it.toProfile(attributes, difficulties) }
    }

    suspend fun profileOnce(): PlayerProfile? = profile.first()

    /** Creates the player at the end of the Awakening. Does nothing if one exists. */
    suspend fun createPlayer(input: AwakeningInput) {
        require(SafetyRules.hasEnoughRestDays(input.settings.restDays)) { "at least one rest day per week" }
        settings.update { input.settings }
        val stats = StartingStats.compute(input.measurements)
        val today = gameDay.clock(input.settings).gameDay(time.now()).toEpochDay()
        db.withTransaction {
            if (dao.getPlayer() != null) return@withTransaction
            dao.upsertPlayer(
                PlayerEntity(
                    name = input.name.trim(),
                    title = titles.startingTitle(),
                    rank = Rank.E,
                    level = 1,
                    totalXp = 0,
                    hp = DerivedStats.maxHp(stats),
                    mp = DerivedStats.maxMp(stats),
                    freePoints = 0,
                    skillPoints = 0,
                    streakDays = 0,
                    bestStreak = 0,
                    consecutiveMisses = 0,
                    currentDay = today,
                    lastIssuedDay = null,
                    recoveryDay = null,
                    trainingDayIndex = 0,
                    createdAtMillis = time.now().toEpochMilli(),
                    rollSeed = random.nextLong(),
                ),
            )
            dao.upsertAttributes(Stat.entries.map { AttributeEntity(it, stats[it], mastery = 0) })
            dao.upsertDifficulties(
                Realm.entries.map {
                    RealmDifficultyEntity(
                        realm = it,
                        multiplier = DifficultyController.INITIAL_MULTIPLIER,
                        completionEma = DifficultyController.TARGET_COMPLETION,
                        lastWeekMultiplier = DifficultyController.INITIAL_MULTIPLIER,
                        risingWeeks = 0,
                        deload = false,
                    )
                },
            )
            dao.upsertBaselines(Baselines.fromInputs(input.baselines).map { (key, value) -> BaselineEntity(key, value) })
        }
    }

    /** Spends free points; [points] maps each stat to how many to add. Returns false if not enough. */
    suspend fun allocatePoints(points: Map<Stat, Int>): Boolean = db.withTransaction {
        val player = dao.getPlayer() ?: return@withTransaction false
        val spend = points.values.filter { it > 0 }.sum()
        if (spend == 0 || spend > player.freePoints) return@withTransaction false
        val attributes = dao.getAttributes().associateBy { it.stat }
        val updated = points.filterValues { it > 0 }.mapNotNull { (stat, add) ->
            attributes[stat]?.let { it.copy(value = it.value + add) }
        }
        dao.upsertAttributes(updated)
        dao.upsertPlayer(player.copy(freePoints = player.freePoints - spend))
        true
    }
}

internal fun List<AttributeEntity>.toStatBlock(): StatBlock {
    val byStat = associate { it.stat to it.value }
    fun v(stat: Stat) = byStat[stat] ?: StatBlock.STARTING_VALUE
    return StatBlock(v(Stat.STR), v(Stat.AGI), v(Stat.INT), v(Stat.FOC), v(Stat.WIS), v(Stat.VIT))
}

private fun PlayerEntity.toProfile(
    attributes: List<AttributeEntity>,
    difficulties: List<RealmDifficultyEntity>,
) = PlayerProfile(
    name = name,
    title = title,
    rank = rank,
    level = level,
    totalXp = totalXp,
    hp = hp,
    mp = mp,
    freePoints = freePoints,
    skillPoints = skillPoints,
    streakDays = streakDays,
    bestStreak = bestStreak,
    stats = attributes.toStatBlock(),
    mastery = attributes.associate { it.stat to it.mastery },
    difficulty = Realm.entries.associateWith { realm ->
        difficulties.firstOrNull { it.realm == realm }?.multiplier ?: DifficultyController.INITIAL_MULTIPLIER
    },
    classId = classId,
)
