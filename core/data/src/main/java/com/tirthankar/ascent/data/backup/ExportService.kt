package com.tirthankar.ascent.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.db.XpSource
import com.tirthankar.ascent.data.repo.HabitRepository
import com.tirthankar.ascent.engine.BackupNames
import com.tirthankar.ascent.engine.Csv
import com.tirthankar.ascent.engine.ExportColumns
import com.tirthankar.ascent.engine.HabitStrength
import com.tirthankar.ascent.engine.StrengthObservation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Export data** (PHASE5.md section 3): `ascent-export-YYYYMMDD.zip` with quests,
 * days, habit periods and the XP log as CSV, plus a README. Read-only, for analysis;
 * never used for restore.
 */
@Singleton
class ExportService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AscentDatabase,
    private val habits: HabitRepository,
    private val gameDay: GameDay,
    private val time: TimeSource,
) {
    /** The suggested file name for today. */
    suspend fun fileName(): String = BackupNames.exportName(gameDay.today())

    /** Writes the export to a document the player created. False if it couldn't be written. */
    suspend fun exportTo(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(Uri.parse(uri), "w") ?: return@withContext false
            out.use { write(it) }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun write(out: OutputStream) {
        val files = db.withTransaction { linkedMapOf(
            ExportColumns.QUESTS to quests(),
            ExportColumns.DAILY to daily(),
            ExportColumns.HABITS to habitRows(),
            ExportColumns.XP_LOG to xpLog(),
            ExportColumns.PLANS to db.plannerDao().plans().map { listOf(it.id.toString(), LocalDate.ofEpochDay(it.targetDay).toString(), it.state, moment(it.writtenAt), it.completedAt?.let(::moment)) },
            ExportColumns.PLAN_ITEMS to db.plannerDao().items().map { i ->
                val q = i.questId?.let { db.questDao().getById(it) }
                listOf(i.id.toString(), i.planId.toString(), i.position.toString(), i.title, i.questId?.toString(), q?.measurementMode ?: i.mode, number(q?.target ?: i.target), q?.unitLabel ?: i.unit, number(q?.actualProgress ?: i.progress), (q?.let { it.status == QuestStatus.DONE } ?: i.done).toString())
            },
            ExportColumns.SESSIONS to db.plannerDao().sessions().map { listOf(it.id, it.questId?.toString(), it.itemId?.toString(), it.state, moment(it.startedAt), moment(it.endsAt), it.activeSeconds.toString(), it.endedAt?.let(::moment), number(it.quantity), it.origin, it.note) },
        ) }
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, rows) ->
                zip.putNextEntry(ZipEntry(name))
                val text = buildString {
                    append(Csv.row(ExportColumns.files.getValue(name)))
                    rows.forEach { append(Csv.row(it)) }
                }
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(ExportColumns.README))
            context.assets.open(README_ASSET).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private suspend fun quests(): List<List<String?>> = db.questDao().all().map { q ->
        val done = q.status == QuestStatus.DONE
        listOf(
            q.publicId,
            q.seriesId,
            q.scope.name,
            q.realm.name,
            q.title,
            q.tier.name,
            number(q.target),
            q.unitLabel,
            number(if (done) maxOf(q.target, q.actualProgress) else q.actualProgress),
            q.status.name,
            q.source.name,
            moment(q.createdAtMillis),
            q.completedAtMillis?.let(::moment) ?: q.completedDay?.let { LocalDate.ofEpochDay(it).toString() },
            q.xpAwarded.toString(),
            number(q.guardMultiplier), q.measurementMode, q.legacyProgress.toString(),
        )
    }

    private suspend fun daily(): List<List<String?>> = db.snapshotDao().getRange(Long.MIN_VALUE, Long.MAX_VALUE).map { s ->
        listOf(
            LocalDate.ofEpochDay(s.day).toString(),
            s.level.toString(), s.totalXp.toString(), s.hp.toString(), s.mp.toString(), s.streakDays.toString(),
            s.bodyDone.toString(), s.bodyTotal.toString(), s.mindDone.toString(), s.mindTotal.toString(),
            s.selfDone.toString(), s.selfTotal.toString(),
            s.neutral.toString(),
        )
    }

    /** Every observed period with the strength after it, computed from the full history. */
    private suspend fun habitRows(): List<List<String?>> {
        val bySeries = db.habitDao().allObservations().groupBy { it.seriesId }
        return bySeries.toSortedMap().flatMap { (seriesId, rows) ->
            val sorted = rows.sortedBy { it.periodKey }
            val history = sorted.map { StrengthObservation(it.periodKey, it.x) }
            val strength = HabitStrength.trajectory(history, habits.period(seriesId), last = Int.MAX_VALUE)
            val title = db.questDao().getSeries(seriesId).lastOrNull()?.title
            sorted.zip(strength).map { (o, s) ->
                listOf(seriesId, title, LocalDate.ofEpochDay(o.periodKey).toString(), number(o.x), number(s))
            }
        }
    }

    /**
     * The XP log from v4 on, preceded by what quest rows can rebuild from before it
     * (completions earlier than the first logged event).
     */
    private suspend fun xpLog(): List<List<String?>> {
        val events = db.habitDao().xpEvents()
        val cutoff = events.minOfOrNull { it.timestamp } ?: Long.MAX_VALUE
        val cutoffDay = events.minOfOrNull { it.day } ?: Long.MAX_VALUE
        val history = db.questDao().all()
            .filter { it.status == QuestStatus.DONE && it.xpAwarded > 0 }
            .filter { q -> q.completedAtMillis?.let { it < cutoff } ?: ((q.completedDay ?: Long.MAX_VALUE) < cutoffDay) }
            .map { q -> (q.completedAtMillis ?: dayStart(q.completedDay ?: q.day)) to q.xpAwarded }
            .sortedBy { it.first }
            .map { (at, xp) -> listOf(moment(at), XpSource.QUEST.name, xp.toString(), FROM_HISTORY) }
        return history + events.map { e -> listOf(moment(e.timestamp), e.source.name, e.amount.toString(), e.multipliers) }
    }

    private fun moment(millis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), time.zone()).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun dayStart(day: Long): Long = LocalDate.ofEpochDay(day).atStartOfDay(time.zone()).toInstant().toEpochMilli()

    /** 40 rather than 40.0; other values as they are. */
    private fun number(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private companion object {
        const val README_ASSET = "export/README.txt"
        const val FROM_HISTORY = "from quest history"
    }
}
