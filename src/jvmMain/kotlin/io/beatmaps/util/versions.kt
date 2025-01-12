package io.beatmaps.util

import io.beatmaps.api.MapVersion
import io.beatmaps.api.from
import io.beatmaps.common.api.EAlertType
import io.beatmaps.common.api.ECharacteristic
import io.beatmaps.common.api.EMapState
import io.beatmaps.common.db.NowExpression
import io.beatmaps.common.db.updateReturning
import io.beatmaps.common.dbo.Alert
import io.beatmaps.common.dbo.Beatmap
import io.beatmaps.common.dbo.BeatmapDao
import io.beatmaps.common.dbo.Collaboration
import io.beatmaps.common.dbo.Difficulty
import io.beatmaps.common.dbo.DifficultyDao
import io.beatmaps.common.dbo.Follows
import io.beatmaps.common.dbo.Versions
import io.beatmaps.common.dbo.VersionsDao
import io.beatmaps.common.dbo.joinUploader
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryParameter
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.lang.Integer.toHexString
import java.math.BigDecimal
import java.time.Instant

fun publishVersion(mapId: Int, hash: String, additionalCallback: (Op<Boolean>) -> Op<Boolean> = { it }): Boolean {
    val publishingVersion = VersionsDao.wrapRow(
        Versions.select {
            Versions.hash eq hash
        }.single()
    )

    val originalState = MapVersion.from(publishingVersion, "")

    fun updateState() =
        Versions.join(Beatmap, JoinType.INNER, onColumn = Versions.mapId, otherColumn = Beatmap.id).update({
            additionalCallback((Versions.mapId eq mapId))
        }) {
            val exp = Expression.build {
                case()
                    .When(Versions.hash eq hash, QueryParameter(EMapState.Published, Versions.state.columnType))
                    .Else(QueryParameter(EMapState.Uploaded, Versions.state.columnType))
            }

            it[Versions.state] = exp
            it[Versions.scheduledAt] = null
        }

    return (updateState() > 0).also { valid ->
        if (!valid) return@also

        val stats = DifficultyDao.wrapRows(
            Difficulty.select {
                Difficulty.versionId eq publishingVersion.id
            }
        )

        Beatmap
            .joinUploader()
            .select {
                (Beatmap.id eq mapId) and (Beatmap.lastPublishedAt.isNull())
            }.firstOrNull()?.let {
                pushAlerts(BeatmapDao.wrapRow(it))
            }

        // Set published time for sorting, but don't allow gaming the system
        Beatmap.updateReturning(
            { Beatmap.id eq mapId },
            {
                it[uploaded] = SqlExpressionBuilder.coalesce(uploaded, NowExpression<Instant?>(uploaded.columnType))
                it[lastPublishedAt] = NowExpression(lastPublishedAt.columnType)
                it[updatedAt] = NowExpression(updatedAt.columnType)

                it[chroma] = stats.any { s -> s.chroma }
                it[noodle] = stats.any { s -> s.ne }
                it[me] = stats.any { s -> s.me }
                it[cinema] = stats.any { s -> s.cinema }

                it[minNps] = stats.minByOrNull { s -> s.nps }?.nps ?: BigDecimal.ZERO
                it[maxNps] = stats.maxByOrNull { s -> s.nps }?.nps ?: BigDecimal.ZERO
                it[fullSpread] = stats.filter { diff -> diff.characteristic == ECharacteristic.Standard }
                    .map { diff -> diff.difficulty }
                    .distinct().count() == 5

                // Because of magical typing reasons this can't be one line
                // They actually call completely different setting functions
                if (originalState.sageScore != null && originalState.sageScore < -4) {
                    it[automapper] = true
                } else {
                    it[automapper] = ai
                }
            },
            Beatmap.uploaded
        )?.singleOrNull()?.let { rr ->
            Collaboration.update({
                Collaboration.mapId eq mapId
            }) {
                it[uploadedAt] = rr[Beatmap.uploaded]
            }
        }
    }
}

fun pushAlerts(map: BeatmapDao) {
    val recipients = Follows.select {
        Follows.userId eq map.uploaderId
    }.let {
        it.map { row -> row[Follows.followerId].value }
    }

    Alert.insert(
        "New Map Release",
        "@${map.uploader.uniqueName} just released #${toHexString(map.id.value)}: **${map.name}**.\n" +
            "*\"${map.description.replace(Regex("\n+"), " ").take(100)}...\"*",
        EAlertType.MapRelease,
        recipients
    )
}
