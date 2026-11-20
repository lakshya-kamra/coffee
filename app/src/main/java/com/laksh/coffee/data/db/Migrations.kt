package com.laksh.coffee.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.laksh.coffee.domain.catalog.LegacyIds

// Every migration is additive: no existing row or value is rewritten except the 1.x enum
// names in v4, which become catalog ids. Never fallbackToDestructiveMigration.

/** Adds grind-click tracking. Additive only, so existing brews survive untouched. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE grinders ADD COLUMN clicksPerRotation INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE grinders ADD COLUMN micronsPerClick REAL")
        db.execSQL("ALTER TABLE grinders ADD COLUMN lowerIsFiner INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE brew_logs ADD COLUMN grindClicks INTEGER")
    }
}

/**
 * Adds the stored analyses. Additive only: no existing table or row is touched. The SQL
 * is exactly what Room generates for AnalysisEntity (schemas/…/3.json now checks it).
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `analyses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `brewLogId` INTEGER, `createdAt` INTEGER NOT NULL, `promptVersion` INTEGER NOT NULL, `inputHash` TEXT NOT NULL, `brewCount` INTEGER NOT NULL, `newestBrewAt` INTEGER NOT NULL, `model` TEXT NOT NULL, `host` TEXT NOT NULL, `rawText` TEXT NOT NULL, `resultJson` TEXT, `flagsJson` TEXT NOT NULL, `promptTokens` INTEGER, `completionTokens` INTEGER, `costMicroUsd` INTEGER, `latencyMs` INTEGER NOT NULL, FOREIGN KEY(`brewLogId`) REFERENCES `brew_logs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyses_brewLogId` ON `analyses` (`brewLogId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyses_kind_createdAt` ON `analyses` (`kind`, `createdAt`)")
    }
}

/**
 * 2.0: stable uids for backup, catalog ids (process, roast level, brewer), bean details, grinder
 * catalog links, the photos table. grindClicks, grindSetting and the grinder's clicks per turn
 * are left exactly as they are: a K-Ultra scale correction needs the catalog and the user's OK.
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach(db::execSQL)
    }
}

/**
 * v4 app (backup 2.1): a bean's form (whole or ground), what ground coffee was ground for, a
 * grinder's setting unit (seconds on a blade grinder) and how a brew's grind was set. ADD COLUMN
 * only; every existing row reads "" (not recorded) for all four.
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_4_5_SQL.forEach(db::execSQL)
    }
}

internal val MIGRATION_4_5_SQL: List<String> = listOf(
    "ALTER TABLE `beans` ADD COLUMN `form` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `beans` ADD COLUMN `groundFor` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `grinders` ADD COLUMN `settingUnit` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `brew_logs` ADD COLUMN `grindKind` TEXT NOT NULL DEFAULT ''",
)

internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

/** A random 32-hex uid per row, so every existing row gets a distinct one. */
private const val RANDOM_UID = "lower(hex(randomblob(16)))"

internal val MIGRATION_3_4_SQL: List<String> = listOf(
    // beans
    "ALTER TABLE `beans` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `beans` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `beans` ADD COLUMN `roasterId` TEXT",
    "ALTER TABLE `beans` ADD COLUMN `originId` TEXT",
    "ALTER TABLE `beans` ADD COLUMN `farm` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `beans` ADD COLUMN `producer` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `beans` ADD COLUMN `washingStation` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `beans` ADD COLUMN `lotTypeId` TEXT",
    "ALTER TABLE `beans` ADD COLUMN `speciesId` TEXT",
    "ALTER TABLE `beans` ADD COLUMN `varietalIds` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `beans` ADD COLUMN `isDecaf` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `beans` ADD COLUMN `decafMethodId` TEXT",
    "ALTER TABLE `beans` ADD COLUMN `roastIntentId` TEXT",
    "ALTER TABLE `beans` ADD COLUMN `harvest` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `beans` ADD COLUMN `restDays` INTEGER",
    "ALTER TABLE `beans` ADD COLUMN `priceAmount` REAL",
    "ALTER TABLE `beans` ADD COLUMN `currency` TEXT NOT NULL DEFAULT ''",
    "UPDATE `beans` SET `uid` = $RANDOM_UID WHERE `uid` = ''",
    "UPDATE `beans` SET `updatedAt` = `createdAt`",
    "UPDATE `beans` SET `process` = " + LegacyIds.caseSql("process", LegacyIds.PROCESS, "''"),
    "UPDATE `beans` SET `roastLevel` = " + LegacyIds.caseSql("roastLevel", LegacyIds.ROAST_LEVEL, "''"),
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_beans_uid` ON `beans` (`uid`)",

    // grinders
    "ALTER TABLE `grinders` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `grinders` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `grinders` ADD COLUMN `catalogId` TEXT",
    "ALTER TABLE `grinders` ADD COLUMN `dialFormat` TEXT NOT NULL DEFAULT ''",
    "UPDATE `grinders` SET `uid` = $RANDOM_UID WHERE `uid` = ''",
    "UPDATE `grinders` SET `updatedAt` = CAST(strftime('%s','now') AS INTEGER) * 1000",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_grinders_uid` ON `grinders` (`uid`)",

    // brew_logs
    "ALTER TABLE `brew_logs` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `brew_logs` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `brew_logs` ADD COLUMN `brewerId` TEXT",
    "ALTER TABLE `brew_logs` ADD COLUMN `grindValue` REAL",
    "ALTER TABLE `brew_logs` ADD COLUMN `waterId` TEXT",
    "UPDATE `brew_logs` SET `uid` = $RANDOM_UID WHERE `uid` = ''",
    "UPDATE `brew_logs` SET `updatedAt` = `startedAt`",
    "UPDATE `brew_logs` SET `brewerId` = " + LegacyIds.caseSql("brewer", LegacyIds.BREWER, "NULL"),
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_brew_logs_uid` ON `brew_logs` (`uid`)",

    // analyses
    "ALTER TABLE `analyses` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `analyses` ADD COLUMN `providerId` TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE `analyses` ADD COLUMN `photoCount` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `analyses` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'android'",
    "UPDATE `analyses` SET `uid` = $RANDOM_UID WHERE `uid` = ''",
    "UPDATE `analyses` SET `providerId` = CASE WHEN `host` = 'api.commandcode.ai' THEN 'commandcode' " +
        "WHEN `host` = 'sample.local' THEN 'sample' ELSE 'custom' END",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_analyses_uid` ON `analyses` (`uid`)",

    // photos: copied from schemas/…/4.json
    "CREATE TABLE IF NOT EXISTS `photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, " +
        "`brewLogId` INTEGER, `beanId` INTEGER, `kind` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
        "`widthPx` INTEGER NOT NULL, `heightPx` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, " +
        "`takenAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `caption` TEXT NOT NULL DEFAULT '', " +
        "`sendToAi` INTEGER NOT NULL DEFAULT 1, " +
        "FOREIGN KEY(`brewLogId`) REFERENCES `brew_logs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`beanId`) REFERENCES `beans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_uid` ON `photos` (`uid`)",
    "CREATE INDEX IF NOT EXISTS `index_photos_brewLogId` ON `photos` (`brewLogId`)",
    "CREATE INDEX IF NOT EXISTS `index_photos_beanId` ON `photos` (`beanId`)",
)
