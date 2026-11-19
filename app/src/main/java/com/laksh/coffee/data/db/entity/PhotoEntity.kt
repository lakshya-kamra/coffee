package com.laksh.coffee.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A photo of a brew (bed, cup) or of a bag. The file lives at filesDir/photos/[fileName]. */
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(entity = BrewLogEntity::class, parentColumns = ["id"], childColumns = ["brewLogId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BeanEntity::class, parentColumns = ["id"], childColumns = ["beanId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["uid"], unique = true), Index("brewLogId"), Index("beanId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val brewLogId: Long? = null,
    val beanId: Long? = null,
    /** bed | cup | bag | other */
    val kind: String,
    /** Relative to filesDir/photos: "<uid>.jpg". */
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val bytes: Long,
    val takenAt: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "") val caption: String = "",
    @ColumnInfo(defaultValue = "1") val sendToAi: Boolean = true,
)
