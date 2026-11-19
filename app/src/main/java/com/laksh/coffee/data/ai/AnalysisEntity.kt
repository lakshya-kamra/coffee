package com.laksh.coffee.data.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.newUid

/**
 * One stored analysis, for a brew or for the whole log. Every completed request is kept,
 * including replies that didn't parse (rawText), so reopening never pays for the same answer.
 *
 * Deleting a brew cascades to its analyses. The v3 columns deliberately have no
 * @ColumnInfo(defaultValue): MIGRATION_2_3 creates this table by hand and must match it exactly.
 * The v4 columns are added by MIGRATION_3_4 with the defaults declared here.
 */
@Entity(
    tableName = "analyses",
    foreignKeys = [
        ForeignKey(
            entity = BrewLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["brewLogId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("brewLogId"), Index("kind", "createdAt"), Index(value = ["uid"], unique = true)],
)
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [KIND_BREW] or [KIND_TREND]. */
    val kind: String,
    /** Null for a trend. */
    val brewLogId: Long?,
    val createdAt: Long,
    val promptVersion: Int,
    /** Hash of the exact prompt, so the UI can tell when the brew changed since. */
    val inputHash: String,
    val brewCount: Int,
    val newestBrewAt: Long,
    val model: String,
    val host: String,
    val rawText: String,
    /** AiJson.stored-encoded StoredResult; null when the reply didn't parse. */
    val resultJson: String?,
    /** The List<DataFlag> the prompt was built with. */
    val flagsJson: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val costMicroUsd: Long?,
    val latencyMs: Long,
    // ---- v4
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
    /** providers.json id the analysis ran on; "custom" for any other address. */
    @ColumnInfo(defaultValue = "") val providerId: String = "",
    /** Photos sent with the request. */
    @ColumnInfo(defaultValue = "0") val photoCount: Int = 0,
    /** Where the analysis ran: android | web. */
    @ColumnInfo(defaultValue = "android") val origin: String = "android",
) {
    companion object {
        const val KIND_BREW = "brew"
        const val KIND_TREND = "trend"
    }
}
