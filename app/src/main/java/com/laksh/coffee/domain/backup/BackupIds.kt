package com.laksh.coffee.domain.backup

import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.Sludge

/**
 * The app's enums as the ids a backup carries, both ways, plus what 1.x wrote for the same
 * things: its labels (the old JSON export) and its Kotlin enum names. FROZEN, like LegacyIds:
 * old files point at these strings forever. null / "not recorded" is the enum's Unset entry.
 */
object BackupIds {

    private class Table<E : Enum<E>>(
        private val unset: E,
        /** id → entry, in the order the ids are documented. */
        private val byId: Map<String, E>,
        /** 1.x label → entry. Enum names are accepted as well. */
        private val byLabel: Map<String, E>,
    ) {
        private val idOf: Map<E, String> = byId.entries.associate { (id, e) -> e to id }
        private val byName: Map<String, E> = byId.values.associateBy { it.name }

        val ids: Set<String> get() = byId.keys

        fun id(e: E): String? = idOf[e]

        /** A v2 id, a 1.x label or an enum name; anything else (and null) is "not recorded". */
        fun read(raw: String?): E {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return unset
            return byId[s] ?: byLabel[s] ?: byName[s] ?: unset
        }

        /** The id for whatever [raw] means, or null. */
        fun normalise(raw: String?): String? = id(read(raw))
    }

    private val BED = Table(
        unset = BedShape.Unset,
        byId = linkedMapOf(
            "flat" to BedShape.Flat, "domed" to BedShape.Domed, "crater" to BedShape.Crater,
            "high-and-dry" to BedShape.HighAndDry, "sloped" to BedShape.Sloped,
            "sinkhole" to BedShape.Sinkhole, "muddy" to BedShape.Muddy,
        ),
        byLabel = mapOf(
            "Flat and even" to BedShape.Flat, "Domed in the middle" to BedShape.Domed,
            "Cratered / dug out" to BedShape.Crater, "High and dry ring" to BedShape.HighAndDry,
            "Sloped to one side" to BedShape.Sloped, "Sinkholes / pits" to BedShape.Sinkhole,
            "Muddy and compacted" to BedShape.Muddy,
        ),
    )

    private val SLUDGE = Table(
        unset = Sludge.Unset,
        byId = linkedMapOf("none" to Sludge.None, "light" to Sludge.Light, "heavy" to Sludge.Heavy),
        byLabel = mapOf("Clean" to Sludge.None, "A little silt" to Sludge.Light, "Thick muddy cap" to Sludge.Heavy),
    )

    private val CHANNELING = Table(
        unset = Channeling.Unset,
        byId = linkedMapOf("none" to Channeling.None, "suspected" to Channeling.Suspected, "obvious" to Channeling.Obvious),
        byLabel = mapOf("None visible" to Channeling.None, "Maybe" to Channeling.Suspected, "Obvious holes" to Channeling.Obvious),
    )

    private val FLOW = Table(
        unset = FlowFeel.Unset,
        byId = linkedMapOf(
            "stalled" to FlowFeel.Stalled, "slow" to FlowFeel.Slow, "on-target" to FlowFeel.OnTarget,
            "fast" to FlowFeel.Fast, "gusher" to FlowFeel.Gusher,
        ),
        byLabel = mapOf(
            "Stalled / never finished" to FlowFeel.Stalled, "Slower than target" to FlowFeel.Slow,
            "On target" to FlowFeel.OnTarget, "Faster than target" to FlowFeel.Fast,
            "Fell straight through" to FlowFeel.Gusher,
        ),
    )

    private val BURR = Table(
        unset = BurrType.Unknown,
        byId = linkedMapOf(
            "conical" to BurrType.Conical, "flat" to BurrType.Flat, "ghost" to BurrType.Ghost, "blade" to BurrType.Blade,
        ),
        byLabel = mapOf(
            "Conical" to BurrType.Conical, "Flat" to BurrType.Flat, "Ghost burr" to BurrType.Ghost, "Blade" to BurrType.Blade,
        ),
    )

    // SettingStyle has no "not recorded": a row that says nothing is a click grinder, as in Room.
    private val STYLE = Table(
        unset = SettingStyle.Clicks,
        byId = linkedMapOf(
            "clicks" to SettingStyle.Clicks, "numbers" to SettingStyle.Numbers,
            "stepless" to SettingStyle.Stepless, "microns" to SettingStyle.Microns,
        ),
        byLabel = mapOf(
            "Clicks" to SettingStyle.Clicks, "Numbered dial" to SettingStyle.Numbers,
            "Microns" to SettingStyle.Microns, "Stepless / degrees" to SettingStyle.Stepless,
        ),
    )

    fun bed(e: BedShape): String? = BED.id(e)
    fun bed(raw: String?): BedShape = BED.read(raw)
    fun bedId(raw: String?): String? = BED.normalise(raw)

    fun sludge(e: Sludge): String? = SLUDGE.id(e)
    fun sludge(raw: String?): Sludge = SLUDGE.read(raw)
    fun sludgeId(raw: String?): String? = SLUDGE.normalise(raw)

    fun channelling(e: Channeling): String? = CHANNELING.id(e)
    fun channelling(raw: String?): Channeling = CHANNELING.read(raw)
    fun channellingId(raw: String?): String? = CHANNELING.normalise(raw)

    fun flow(e: FlowFeel): String? = FLOW.id(e)
    fun flow(raw: String?): FlowFeel = FLOW.read(raw)
    fun flowId(raw: String?): String? = FLOW.normalise(raw)

    fun burr(e: BurrType): String? = BURR.id(e)
    fun burr(raw: String?): BurrType = BURR.read(raw)
    fun burrId(raw: String?): String? = BURR.normalise(raw)

    fun style(e: SettingStyle): String = STYLE.id(e) ?: "clicks"
    fun style(raw: String?): SettingStyle = STYLE.read(raw)
    fun styleId(raw: String?): String = STYLE.normalise(raw) ?: "clicks"

    val PHOTO_KINDS: Set<String> = setOf("bed", "cup", "bag", "other")
    val BED_IDS: Set<String> get() = BED.ids
    val FLOW_IDS: Set<String> get() = FLOW.ids
}
