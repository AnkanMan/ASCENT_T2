package com.tirthankar.ascent.data.repo

import androidx.room.withTransaction
import com.tirthankar.ascent.data.ContentRepository
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.TemplateTitles
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.DerivedStats
import com.tirthankar.ascent.engine.EmergencyRules
import com.tirthankar.ascent.engine.Fatigue
import com.tirthankar.ascent.engine.GameClock
import com.tirthankar.ascent.engine.PenaltyRules
import com.tirthankar.ascent.engine.QuestTemplate
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Seeds
import com.tirthankar.ascent.engine.SideQuestRules
import com.tirthankar.ascent.engine.SplitMix64
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.Tier
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What an emergency issue produced, for the notification. */
data class EmergencyIssued(val title: String, val endsAt: Instant)

/**
 * Side, penalty and emergency quests (SPEC Phase 3, D2, D13).
 * - Side: one balance quest per reset while fewer than three are visible; your own
 *   bounties in one tap; Claude's with `expires_days`. 0.75× XP, never penalised.
 * - Penalty: after a missed Daily, one small task due at the next reset.
 * - Emergency: about two a week inside active hours, open for 2 hours, 1.5× XP.
 */
@Singleton
class SideQuestService @Inject constructor(
    private val db: AscentDatabase,
    private val quests: QuestRepository,
    private val contentRepository: ContentRepository,
    private val settings: SettingsRepository,
    private val gameDay: GameDay,
    private val time: TimeSource,
    private val titles: TemplateTitles,
    private val events: GameEvents,
) {
    private val questDao = db.questDao()

    /** Adds one of your own errands as a side quest: Self, Easy, open 3 days. Null if 3 are open. */
    suspend fun addBounty(title: String): Long? {
        val today = gameDay.today()
        return db.withTransaction {
            if (questDao.getOpenSide(today.toEpochDay()).size >= SideQuestRules.MAX_VISIBLE) return@withTransaction null
            if (com.tirthankar.ascent.engine.SafetyRules.isForbidden(title) || title.isBlank()) return@withTransaction null
            quests.insert(
                NewQuest(
                    title = title,
                    realm = Realm.SELF,
                    stat = Stat.WIS,
                    tier = Tier.EASY,
                    target = 1.0,
                    unitLabel = titles.unitLabel(com.tirthankar.ascent.engine.TargetUnit.OTHER),
                    scope = QuestScope.DAILY,
                    source = QuestSource.MANUAL,
                    kind = QuestKind.SIDE,
                    expiryDays = SideQuestRules.BOUNTY_EXPIRY_DAYS,
                ),
                today,
            )
        }
    }

    /** True while fewer than three side quests are open. */
    suspend fun hasRoom(): Boolean = questDao.getOpenSide(gameDay.today().toEpochDay()).size < SideQuestRules.MAX_VISIBLE

    /**
     * At the reset: today's balance side quest and, after a missed Daily, a penalty quest.
     * Only for the real today, not for days caught up. Call inside a transaction.
     */
    internal suspend fun issueForToday(
        today: LocalDate,
        s: AscentSettings,
        engineTarget: suspend (QuestTemplate) -> Double?,
        emitted: MutableList<GameEvent>,
    ) {
        val epoch = today.toEpochDay()
        val player = db.playerDao().getPlayer() ?: return
        questDao.expireSide(epoch)

        // Penalty: yesterday's Daily was issued and missed, and none is open yet.
        // A miss the Streak Shield covered brings no penalty quest (PHASE4.md section 1).
        val yesterday = db.snapshotDao().get(epoch - 1)
        val shielded = player.lastMissDay == epoch - 1 && player.lastMissShielded
        if (yesterday != null && yesterday.objectives > 0 && !yesterday.dailyComplete && !shielded && questDao.openPenalties() == 0 &&
            questDao.getKindOnDay(QuestKind.PENALTY, epoch).isEmpty()
        ) {
            PenaltyRules.pick(contentRepository.content.quests.penalty, epoch)?.let { task ->
                quests.insert(
                    NewQuest(task.title, task.realm, task.stat, task.tier, task.target, task.unit, QuestScope.DAILY, source = QuestSource.TEMPLATE, templateKey = task.id, kind = QuestKind.PENALTY),
                    today,
                )
                emitted += GameEvent.Notice(NoticeKind.PENALTY_ISSUED, task.title)
            }
        }

        // Balance: one side quest per reset, aimed at the weakest realm of the last 7 days.
        val open = questDao.getOpenSide(epoch)
        val issuedToday = open.any { it.day == epoch && it.source == QuestSource.TEMPLATE }
        if (!issuedToday && SideQuestRules.shouldIssueBalance(open.size)) {
            val week = db.snapshotDao().getRange(epoch - 7, epoch - 1)
            val realm = SideQuestRules.balanceRealm(
                done = mapOf(Realm.BODY to week.sumOf { it.bodyDone }, Realm.MIND to week.sumOf { it.mindDone }, Realm.SELF to week.sumOf { it.selfDone }),
                total = mapOf(Realm.BODY to week.sumOf { it.bodyTotal }, Realm.MIND to week.sumOf { it.mindTotal }, Realm.SELF to week.sumOf { it.selfTotal }),
            )
            val exclude = (questDao.getDay(epoch) + open).mapNotNull { it.templateKey }.toSet()
            val isRest = today.dayOfWeek in s.restDays || player.recoveryDay == epoch
            val seed = Seeds.forEvent(player.rollSeed, "balance:$epoch")
            SideQuestRules.balanceTemplate(realm, exclude, isRest, seed)?.let { template ->
                val target = engineTarget(template) ?: template.fixedTarget ?: 1.0
                quests.insert(
                    NewQuest(
                        title = titles.title(template, com.tirthankar.ascent.engine.SafetyRules.latestLightsOut(s.wakeTime)),
                        realm = template.realm,
                        stat = template.stat,
                        tier = template.tier,
                        target = target,
                        unitLabel = titles.unitLabel(template),
                        scope = QuestScope.DAILY,
                        source = QuestSource.TEMPLATE,
                        templateKey = template.key,
                        kind = QuestKind.SIDE,
                        expiryDays = SideQuestRules.BALANCE_EXPIRY_DAYS,
                    ),
                    today,
                )
            }
        }
    }

    /** The next emergency start after [now], for the scheduler. */
    suspend fun nextEmergency(now: Instant): Instant? {
        val s = settings.current()
        val player = db.playerDao().getPlayer() ?: return null
        val zone = time.zone()
        val week = GameClock.weekStart(gameDay.clock(s).gameDay(now))
        return (EmergencyRules.slots(week, player.rollSeed, s.restDays, s.wakeTime, s.quietStart) +
            EmergencyRules.slots(week.plusDays(7), player.rollSeed, s.restDays, s.wakeTime, s.quietStart))
            .map { it.atZone(zone).toInstant() }
            .firstOrNull { it.isAfter(now) }
    }

    /**
     * Issues the emergency quest if one is due now (inside a slot's 2-hour window), not
     * already issued today, and you're not fatigued. The window starts when it is shown.
     */
    suspend fun issueEmergencyIfDue(): EmergencyIssued? {
        val s = settings.current()
        val now = time.now()
        val today = gameDay.clock(s).gameDay(now)
        val emitted = mutableListOf<GameEvent>()
        val issued = db.withTransaction {
            val player = db.playerDao().getPlayer() ?: return@withTransaction null
            if (Fatigue.isFatigued(player.hp, DerivedStats.maxHp(db.playerDao().getAttributes().toStatBlock()))) return@withTransaction null
            if (questDao.getKindOnDay(QuestKind.EMERGENCY, today.toEpochDay()).isNotEmpty()) return@withTransaction null
            val week = GameClock.weekStart(today)
            val window = Duration.ofHours(EmergencyRules.WINDOW_HOURS)
            val due = EmergencyRules.slots(week, player.rollSeed, s.restDays, s.wakeTime, s.quietStart)
                .map { it.atZone(time.zone()).toInstant() }
                .any { !now.isBefore(it) && now.isBefore(it.plus(window)) }
            if (!due) return@withTransaction null
            val options = contentRepository.content.quests.emergency
            if (options.isEmpty()) return@withTransaction null
            val task = options[SplitMix64(Seeds.forEvent(player.rollSeed, "emergency:${today.toEpochDay()}")).nextInt(options.size)]
            val endsAt = now.plus(window)
            quests.insert(
                NewQuest(
                    task.title, task.realm, task.stat, task.tier, task.target, task.unit, QuestScope.DAILY,
                    source = QuestSource.TEMPLATE, templateKey = task.id, kind = QuestKind.EMERGENCY, expiresAtMillis = endsAt.toEpochMilli(),
                ),
                today,
            )
            emitted += GameEvent.Notice(NoticeKind.EMERGENCY_ISSUED, task.title)
            EmergencyIssued(task.title, endsAt)
        }
        emitted.forEach(events::emit)
        return issued
    }
}
