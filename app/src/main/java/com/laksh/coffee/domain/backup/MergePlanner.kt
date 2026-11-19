package com.laksh.coffee.domain.backup

enum class MergeMode {
    /** "Add to this phone": new records are added, newer copies replace older ones. */
    Add,

    /** "Replace everything": what is on the phone is deleted first, then everything is added. */
    Replace,
}

/** A record already on this phone: its local id and when it was last changed (null = never stamped). */
data class Existing<Id>(val id: Id, val updatedAt: Long?)

data class Replacement<T, Id>(val item: T, val id: Id)

data class TablePlan<T, Id>(
    val add: List<T>,
    val replace: List<Replacement<T, Id>>,
    val keep: List<Replacement<T, Id>>,
)

/**
 * The pure merge rules (shared/schema/backup-v2.md, "Importing"). Per record:
 *   add      the key is new here;
 *   replace  the key exists and the file's updatedAt is strictly newer;
 *   keep     the key exists and the file's copy is older, equal, or has no updatedAt
 *            (records without updatedAt never replace).
 * "Replace everything" plans against an empty phone, so every record is added. Running the same
 * plan twice therefore changes nothing the second time.
 */
object MergePlanner {

    /**
     * A key that appears twice in the file is planned once, from its newest copy (the first copy
     * when neither is newer), so a hand-merged file cannot add a row twice.
     */
    fun <T, Id> plan(
        incoming: List<T>,
        key: (T) -> String,
        updatedAt: (T) -> Long?,
        existing: Map<String, Existing<Id>>,
        mode: MergeMode,
    ): TablePlan<T, Id> {
        val unique = LinkedHashMap<String, T>()
        for (item in incoming) {
            val k = key(item)
            val prev = unique[k]
            if (prev == null || (updatedAt(item) ?: Long.MIN_VALUE) > (updatedAt(prev) ?: Long.MIN_VALUE)) unique[k] = item
        }
        val add = ArrayList<T>()
        val replace = ArrayList<Replacement<T, Id>>()
        val keep = ArrayList<Replacement<T, Id>>()
        for ((k, item) in unique) {
            val here = if (mode == MergeMode.Replace) null else existing[k]
            if (here == null) {
                add += item
                continue
            }
            val theirs = updatedAt(item)
            if (theirs != null && (here.updatedAt == null || theirs > here.updatedAt)) {
                replace += Replacement(item, here.id)
            } else {
                keep += Replacement(item, here.id)
            }
        }
        return TablePlan(add, replace, keep)
    }
}
