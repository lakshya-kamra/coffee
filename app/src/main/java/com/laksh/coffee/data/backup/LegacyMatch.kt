package com.laksh.coffee.data.backup

import com.laksh.coffee.data.catalog.CatalogLinker
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.backup.BackupDoc
import com.laksh.coffee.domain.backup.BackupTime
import java.util.Locale

/**
 * A 1.x export has no uids, so its records get ids made from the file (LegacyExportReader).
 * The same brews can already be on the phone under other uids: they came through the database
 * upgrade from 1.x, or from an export saved on another day. Matched by uid alone they would all
 * arrive a second time, with no undo.
 *
 * So before the merge is planned, a record that is not here by uid takes the uid of the row it
 * matches by content: a brew by its start time and recipe name, a coffee by its name and
 * roaster, a grinder by its brand and model. It then plans as "already here" (a 1.x record has
 * no updatedAt, so it never replaces), and the brews in the file point at the rows that are
 * here. Each row stands for one record at most, so two bags of the same coffee stay two.
 *
 * Only for 1.x files: a v2 backup's uids are the records' own.
 */
object LegacyMatch {

    fun onto(doc: BackupDoc, beans: List<BeanEntity>, grinders: List<GrinderEntity>, brews: List<BrewLogEntity>): BackupDoc {
        val beanUids = pair(
            doc.beans, { it.uid }, { beanKey(it.name, it.roaster) },
            beans, { it.uid }, { beanKey(it.name, it.roaster) },
        )
        val grinderUids = pair(
            doc.grinders, { it.uid }, { grinderKey(it.brand, it.model) },
            grinders, { it.uid }, { grinderKey(it.brand, it.model) },
        )
        val brewUids = pair(
            doc.brews, { it.uid }, { d -> BackupTime.parse(d.startedAt)?.let { brewKey(it, d.recipeName) } },
            brews, { it.uid }, { brewKey(it.startedAt, it.recipeName) },
        )
        if (beanUids.isEmpty() && grinderUids.isEmpty() && brewUids.isEmpty()) return doc
        return doc.copy(
            beans = doc.beans.map { b -> beanUids[b.uid]?.let { b.copy(uid = it) } ?: b },
            grinders = doc.grinders.map { g -> grinderUids[g.uid]?.let { g.copy(uid = it) } ?: g },
            brews = doc.brews.map { b ->
                b.copy(
                    uid = brewUids[b.uid] ?: b.uid,
                    beanUid = b.beanUid?.let { beanUids[it] ?: it },
                    grinderUid = b.grinderUid?.let { grinderUids[it] ?: it },
                )
            },
        )
    }

    /** True when [brand] + [model] name the same grinder: case, spaces and accents aside. */
    fun sameGrinder(brand: String, model: String, otherBrand: String, otherModel: String): Boolean {
        val key = grinderKey(brand, model) ?: return false
        return key == grinderKey(otherBrand, otherModel)
    }

    private fun words(s: String): String = s.trim().lowercase(Locale.ROOT).replace(SPACES, " ")

    private fun beanKey(name: String, roaster: String): String? =
        words(name).takeIf { it.isNotEmpty() }?.let { it + "\n" + words(roaster) }

    private fun grinderKey(brand: String, model: String): String? =
        CatalogLinker.norm(brand + model).takeIf { it.isNotEmpty() }

    private fun brewKey(startedAtMs: Long, recipeName: String): String = "$startedAtMs\n${recipeName.trim()}"

    /** file uid → the uid of the row here that is the same record. */
    private fun <D, R> pair(
        incoming: List<D>,
        uid: (D) -> String,
        key: (D) -> String?,
        here: List<R>,
        hereUid: (R) -> String,
        hereKey: (R) -> String?,
    ): Map<String, String> {
        val known = here.mapTo(HashSet(), hereUid)
        val inFile = incoming.mapTo(HashSet(), uid)
        // A row the file already names by uid is that record, not free to stand for another.
        val free = HashMap<String, ArrayDeque<String>>()
        for (row in here) {
            val id = hereUid(row)
            if (id in inFile) continue
            val k = hereKey(row) ?: continue
            free.getOrPut(k) { ArrayDeque() }.addLast(id)
        }
        val out = HashMap<String, String>()
        for (item in incoming) {
            val id = uid(item)
            if (id in known || id in out) continue
            val k = key(item) ?: continue
            out[id] = free[k]?.removeFirstOrNull() ?: continue
        }
        return out
    }

    private val SPACES = Regex("\\s+")
}
