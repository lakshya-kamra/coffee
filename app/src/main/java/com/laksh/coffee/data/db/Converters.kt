package com.laksh.coffee.data.db

import androidx.room.TypeConverter
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.Sludge

/**
 * Enums are stored by name, not ordinal, so reordering an enum never silently
 * rewrites old rows. Unknown names fall back to the enum's first entry.
 */
class Converters {

    private companion object {
        /** ASCII unit separator (0x1F): can never appear in a typed descriptor. */
        val SEP: String = 31.toChar().toString()
    }

    @TypeConverter fun listToString(v: List<String>?): String = v.orEmpty().joinToString(SEP)

    @TypeConverter fun stringToList(v: String?): List<String> =
        if (v.isNullOrEmpty()) emptyList() else v.split(SEP).filter { it.isNotBlank() }

    @TypeConverter fun burrToString(v: BurrType): String = v.name
    @TypeConverter fun stringToBurr(v: String): BurrType =
        runCatching { BurrType.valueOf(v) }.getOrDefault(BurrType.Unknown)

    @TypeConverter fun settingStyleToString(v: SettingStyle): String = v.name
    @TypeConverter fun stringToSettingStyle(v: String): SettingStyle =
        runCatching { SettingStyle.valueOf(v) }.getOrDefault(SettingStyle.Clicks)

    @TypeConverter fun bedToString(v: BedShape): String = v.name
    @TypeConverter fun stringToBed(v: String): BedShape =
        runCatching { BedShape.valueOf(v) }.getOrDefault(BedShape.Unset)

    @TypeConverter fun sludgeToString(v: Sludge): String = v.name
    @TypeConverter fun stringToSludge(v: String): Sludge =
        runCatching { Sludge.valueOf(v) }.getOrDefault(Sludge.Unset)

    @TypeConverter fun channelingToString(v: Channeling): String = v.name
    @TypeConverter fun stringToChanneling(v: String): Channeling =
        runCatching { Channeling.valueOf(v) }.getOrDefault(Channeling.Unset)

    @TypeConverter fun flowToString(v: FlowFeel): String = v.name
    @TypeConverter fun stringToFlow(v: String): FlowFeel =
        runCatching { FlowFeel.valueOf(v) }.getOrDefault(FlowFeel.Unset)
}
