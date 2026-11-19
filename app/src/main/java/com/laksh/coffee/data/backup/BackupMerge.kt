package com.laksh.coffee.data.backup

import com.laksh.coffee.data.ai.AnalysisEntity
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.db.entity.SavedRecipeEntity
import com.laksh.coffee.domain.backup.BackupDoc
import com.laksh.coffee.domain.backup.BackupMapping
import com.laksh.coffee.domain.backup.BackupTime
import com.laksh.coffee.domain.backup.Counts
import com.laksh.coffee.domain.backup.Existing
import com.laksh.coffee.domain.backup.GrinderDto
import com.laksh.coffee.domain.backup.Lenient
import com.laksh.coffee.domain.backup.MergeMode
import com.laksh.coffee.domain.backup.MergePlanner

/**
 * What an import needs from the database. The real one is Room inside one transaction
 * ([RoomBackupStore]); tests use a list-backed one, so the merge is proven without a phone.
 */
interface BackupStore {
    suspend fun beans(): List<BeanEntity>
    suspend fun grinders(): List<GrinderEntity>
    suspend fun brews(): List<BrewLogEntity>
    suspend fun savedRecipes(): List<SavedRecipeEntity>
    suspend fun analyses(): List<AnalysisEntity>
    suspend fun photos(): List<PhotoEntity>

    /** Beans, grinders, brews, analyses, photo rows and the user's recipes. */
    suspend fun deleteEverything()

    suspend fun insertBean(bean: BeanEntity): Long
    suspend fun updateBean(bean: BeanEntity)
    suspend fun insertGrinder(grinder: GrinderEntity): Long
    suspend fun updateGrinder(grinder: GrinderEntity)
    suspend fun insertBrew(brew: BrewLogEntity): Long
    suspend fun updateBrew(brew: BrewLogEntity)
    suspend fun upsertRecipe(recipe: SavedRecipeEntity)
    suspend fun insertAnalysis(analysis: AnalysisEntity): Long
    suspend fun insertPhoto(photo: PhotoEntity): Long
    suspend fun updatePhoto(photo: PhotoEntity)
}

/** A photo from the file, already written to this phone's storage under [fileName]. */
data class StagedPhoto(
    val fileName: String,
    val bytes: Long,
    /** Measured from the image when the phone could; else the file's own numbers are used. */
    val widthPx: Int? = null,
    val heightPx: Int? = null,
)

data class MergeResult(
    val added: Counts,
    val replaced: Counts,
    /** Already here and not older here: left as they are. */
    val kept: Counts,
    /** Plain-word notes about records that could not be placed. */
    val skipped: List<String>,
    /** uid → this phone's id, for every bean and grinder now here. */
    val beanIds: Map<String, Long>,
    val grinderIds: Map<String, Long>,
    /** Uids of the beans and grinders written (added or replaced), for catalog linking. */
    val written: Set<String>,
    /** File names the new photo rows, and the rows whose missing image came back, point at. */
    val usedPhotoFiles: Set<String>,
    /** Replace mode: the files of the photo rows that were deleted. */
    val removedPhotoFiles: Set<String>,
) {
    val arrived: Boolean get() = added.total > 0 || replaced.total > 0
}

/**
 * Applies a decoded backup to a [BackupStore] following the pure plan from [MergePlanner]:
 * uids become this phone's ids, rows keep the file's updatedAt (which is what makes a second
 * import of the same file a no-op), and existing rows are updated in place, never re-inserted,
 * so their photos and analyses are not cascaded away.
 *
 * Three things are matched by more than the uid, because the same thing can be on a phone under
 * two uids: the records of a 1.x export ([LegacyMatch]); a grinder made in setup that the backup
 * also holds (the empty row here becomes the backup's, see [twin]); and a photo whose row is
 * here but whose image is not, which takes the image from the file.
 */
object BackupMerge {

    private fun <T> index(rows: List<T>, uid: (T) -> String, id: (T) -> Long, updatedAt: (T) -> Long?): Map<String, Existing<Long>> =
        rows.associate { uid(it) to Existing(id(it), updatedAt(it)?.takeIf { stamp -> stamp > 0 }) }

    private fun stamp(text: String?): Long? = BackupTime.parse(text)

    suspend fun apply(
        store: BackupStore,
        doc: BackupDoc,
        mode: MergeMode,
        /** photo uid → its staged file. A photo with no entry is "not in the file". */
        staged: Map<String, StagedPhoto>,
        now: Long,
        /** The file is a 1.x export: its records are matched to rows already here by content. */
        legacy: Boolean = false,
        /**
         * Uids of photo rows here whose image is not on this phone (the database came over in
         * a device transfer, the photo folder did not). Their staged image is attached to the
         * row; any other photo that is already here keeps what it has.
         */
        photosWithoutFile: Set<String> = emptySet(),
    ): MergeResult {
        val fallbackMs = stamp(doc.exportedAt) ?: now
        val skipped = ArrayList<String>()

        var removedPhotoFiles = emptySet<String>()
        if (mode == MergeMode.Replace) {
            removedPhotoFiles = store.photos().mapTo(HashSet()) { it.fileName }
            store.deleteEverything()
        }
        val beans = store.beans()
        val grinders = store.grinders()
        val brews = store.brews()
        val saved = store.savedRecipes()
        val analyses = store.analyses()
        val photos = store.photos()
        @Suppress("NAME_SHADOWING")
        val doc = if (legacy && mode == MergeMode.Add) LegacyMatch.onto(doc, beans, grinders, brews) else doc

        // ---- beans
        val beanPlan = MergePlanner.plan(doc.beans, { it.uid }, { stamp(it.updatedAt) }, index(beans, { it.uid }, { it.id }, { it.updatedAt }), mode)
        val beanIds = beans.associateTo(HashMap()) { it.uid to it.id }
        for (d in beanPlan.add) beanIds[d.uid] = store.insertBean(BackupMapping.bean(d, fallbackMs))
        for ((d, id) in beanPlan.replace) store.updateBean(BackupMapping.bean(d, fallbackMs, id))

        // ---- grinders. At most one default: in Add mode the one already here stays default.
        val grinderPlan = MergePlanner.plan(doc.grinders, { it.uid }, { stamp(it.updatedAt) }, index(grinders, { it.uid }, { it.id }, { it.updatedAt }), mode)
        val grinderById = grinders.associateBy { it.id }
        val grinderIds = grinders.associateTo(HashMap()) { it.uid to it.id }
        var defaultTaken = grinders.any { it.isDefault }
        // Grinders here that no brew uses and the file does not name: free to become the file's.
        val usedGrinderIds = brews.mapNotNullTo(HashSet()) { it.grinderId }
        val fileGrinderUids = doc.grinders.mapTo(HashSet()) { it.uid }
        val spare = grinders.filterTo(ArrayList()) { it.id !in usedGrinderIds && it.uid !in fileGrinderUids }
        var grindersFolded = 0
        for (d in grinderPlan.add) {
            val twin = spare.firstOrNull { twin(it, d) }
            if (twin != null) {
                // Same id, so it stays the default grinder if it was; the file's uid, numbers
                // and words, so the brews in the file that were ground on it link to it.
                spare -= twin
                val isDefault = twin.isDefault || (d.isDefault && !defaultTaken)
                if (isDefault) defaultTaken = true
                // A file that names no catalog entry does not undo the link setup made.
                val row = BackupMapping.grinder(d, fallbackMs, twin.id)
                store.updateGrinder(row.copy(isDefault = isDefault, catalogId = row.catalogId ?: twin.catalogId))
                grinderIds.remove(twin.uid)
                grinderIds[d.uid] = twin.id
                grindersFolded++
                continue
            }
            var row = BackupMapping.grinder(d, fallbackMs)
            if (row.isDefault && defaultTaken) row = row.copy(isDefault = false)
            if (row.isDefault) defaultTaken = true
            grinderIds[d.uid] = store.insertGrinder(row)
        }
        for ((d, id) in grinderPlan.replace) {
            val here = grinderById[id]
            store.updateGrinder(BackupMapping.grinder(d, fallbackMs, id).copy(isDefault = here?.isDefault ?: false))
        }

        val written = HashSet<String>()
        beanPlan.add.mapTo(written) { it.uid }
        beanPlan.replace.mapTo(written) { it.item.uid }
        grinderPlan.add.mapTo(written) { it.uid }
        grinderPlan.replace.mapTo(written) { it.item.uid }

        // ---- brews
        val brewPlan = MergePlanner.plan(doc.brews, { it.uid }, { stamp(it.updatedAt) }, index(brews, { it.uid }, { it.id }, { it.updatedAt }), mode)
        val brewIds = brews.associateTo(HashMap()) { it.uid to it.id }
        for (d in brewPlan.add) {
            brewIds[d.uid] = store.insertBrew(BackupMapping.brew(d, d.beanUid?.let(beanIds::get), d.grinderUid?.let(grinderIds::get)))
        }
        for ((d, id) in brewPlan.replace) {
            store.updateBrew(BackupMapping.brew(d, d.beanUid?.let(beanIds::get), d.grinderUid?.let(grinderIds::get), id))
        }

        // ---- the user's recipes and their edits of built-ins, by recipe id
        val recipePlan = MergePlanner.plan(
            doc.recipes,
            { Lenient.str(it["id"]) },
            { BackupTime.parse(it["updatedAt"]) },
            saved.associate { it.id to Existing(it.id, it.updatedAt.takeIf { stamp -> stamp > 0 }) },
            mode,
        )
        var recipesAdded = 0
        var recipesReplaced = 0
        var recipesUnreadable = 0
        for ((isNew, entry) in recipePlan.add.map { true to it } + recipePlan.replace.map { false to it.item }) {
            val row = BackupMapping.savedRecipe(entry, fallbackMs)
            if (row == null) {
                recipesUnreadable++
                continue
            }
            store.upsertRecipe(row)
            if (isNew) recipesAdded++ else recipesReplaced++
        }
        if (recipesUnreadable > 0) skipped += count(recipesUnreadable, "recipe", "recipes") + " couldn't be read"

        // ---- analyses carry no updatedAt: only ever added or kept
        val analysisPlan = MergePlanner.plan(doc.analyses, { it.uid }, { null }, index(analyses, { it.uid }, { it.id }, { null }), mode)
        var analysesAdded = 0
        var analysesOrphaned = 0
        for (d in analysisPlan.add) {
            val brewLogId = if (d.kind == AnalysisEntity.KIND_BREW) d.brewUid?.let(brewIds::get) else null
            // A brew analysis whose brew is not here would never be shown.
            if (d.kind == AnalysisEntity.KIND_BREW && brewLogId == null) {
                analysesOrphaned++
                continue
            }
            store.insertAnalysis(BackupMapping.analysis(d, brewLogId))
            analysesAdded++
        }
        if (analysesOrphaned > 0) {
            skipped += if (analysesOrphaned == 1) "1 analysis is of a brew that isn't in the backup" else "$analysesOrphaned analyses are of brews that aren't in the backup"
        }

        // ---- photos, like analyses. The file is already on disk; the row points at it.
        val photoPlan = MergePlanner.plan(doc.photos, { it.uid }, { null }, index(photos, { it.uid }, { it.id }, { null }), mode)
        val usedFiles = HashSet<String>()
        var photosAdded = 0
        // A row that is here without its image gets the image; nothing else about it changes.
        val photoById = photos.associateBy { it.id }
        var photosRestored = 0
        for ((d, id) in photoPlan.keep) {
            if (d.uid !in photosWithoutFile) continue
            val file = staged[d.uid] ?: continue
            val row = photoById[id] ?: continue
            store.updatePhoto(
                row.copy(
                    fileName = file.fileName,
                    bytes = file.bytes,
                    widthPx = file.widthPx ?: row.widthPx,
                    heightPx = file.heightPx ?: row.heightPx,
                ),
            )
            usedFiles += file.fileName
            photosRestored++
        }
        var photosMissing = 0
        var photosOrphaned = 0
        for (d in photoPlan.add) {
            val file = staged[d.uid]
            if (file == null) {
                photosMissing++
                continue
            }
            val brewLogId = d.brewUid?.let(brewIds::get)
            val beanId = d.beanUid?.let(beanIds::get)
            if (brewLogId == null && beanId == null) {
                photosOrphaned++
                continue
            }
            store.insertPhoto(
                BackupMapping.photo(
                    d, brewLogId, beanId,
                    fileName = file.fileName,
                    bytes = file.bytes,
                    widthPx = file.widthPx ?: d.widthPx,
                    heightPx = file.heightPx ?: d.heightPx,
                ),
            )
            usedFiles += file.fileName
            photosAdded++
        }
        if (photosMissing > 0) skipped += if (photosMissing == 1) "1 photo isn't in the file" else "$photosMissing photos aren't in the file"
        if (photosOrphaned > 0) {
            skipped += if (photosOrphaned == 1) "1 photo is of a brew or coffee that isn't in the backup" else "$photosOrphaned photos are of brews or coffees that aren't in the backup"
        }

        return MergeResult(
            // A photo whose image came back counts as added: that is what arrived. A grinder
            // folded into the one made in setup counts as updated: no new row, new contents.
            added = Counts(
                brews = brewPlan.add.size, beans = beanPlan.add.size, grinders = grinderPlan.add.size - grindersFolded,
                recipes = recipesAdded, analyses = analysesAdded, photos = photosAdded + photosRestored,
            ),
            replaced = Counts(
                brews = brewPlan.replace.size, beans = beanPlan.replace.size, grinders = grinderPlan.replace.size + grindersFolded,
                recipes = recipesReplaced,
            ),
            kept = Counts(
                brews = brewPlan.keep.size, beans = beanPlan.keep.size, grinders = grinderPlan.keep.size,
                recipes = recipePlan.keep.size, analyses = analysisPlan.keep.size, photos = photoPlan.keep.size - photosRestored,
            ),
            skipped = skipped,
            beanIds = beanIds,
            grinderIds = grinderIds,
            written = written,
            usedPhotoFiles = usedFiles,
            removedPhotoFiles = removedPhotoFiles,
        )
    }

    private fun count(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

    /**
     * The grinder in the file is the grinder in this row: the same catalog entry, or the same
     * brand and model. Someone who picked their grinder in setup and then restored a backup has
     * one grinder, not two; left apart, new brews would go to the empty one and the history,
     * the grind chart and "last time you used" would stay with the other.
     */
    private fun twin(here: GrinderEntity, d: GrinderDto): Boolean =
        (d.catalogId != null && d.catalogId == here.catalogId) ||
            LegacyMatch.sameGrinder(here.brand, here.model, d.brand, d.model)
}
