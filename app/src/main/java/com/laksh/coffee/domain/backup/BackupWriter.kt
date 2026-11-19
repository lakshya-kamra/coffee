package com.laksh.coffee.domain.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/**
 * Writes a backup as a stream: the envelope and every table first, then the photos one at a
 * time, so only one photo's base64 is ever in memory. The result is the same pretty-printed
 * JSON [BackupCodec.encode] would give for the whole document.
 */
object BackupWriter {

    /**
     * @param doc everything but the photos ([BackupDoc.photos] is ignored).
     * @param photos each photo with its data already filled in (or null data for a backup saved
     *   without photos); pulled lazily, one at a time.
     * @return how many photos were written.
     */
    fun write(out: Appendable, doc: BackupDoc, photos: Sequence<PhotoDto>): Int {
        out.append("{\n")
        field(out, "format", String.serializer(), doc.format)
        field(out, "version", Int.serializer(), doc.version)
        field(out, "exportedAt", String.serializer(), doc.exportedAt)
        field(out, "app", BackupApp.serializer(), doc.app)
        field(out, "options", BackupOptions.serializer(), doc.options)
        field(out, "settings", SettingsDto.serializer().nullable, doc.settings)
        field(out, "ai", AiDto.serializer().nullable, doc.ai)
        field(out, "beans", ListSerializer(BeanDto.serializer()), doc.beans)
        field(out, "grinders", ListSerializer(GrinderDto.serializer()), doc.grinders)
        field(out, "brews", ListSerializer(BrewDto.serializer()), doc.brews)
        field(out, "recipes", ListSerializer(JsonObject.serializer()), doc.recipes)
        field(out, "analyses", ListSerializer(AnalysisDto.serializer()), doc.analyses)

        out.append("  \"photos\": [")
        var written = 0
        for (photo in photos) {
            out.append(if (written == 0) "\n    " else ",\n    ")
            out.append(indent(BackupCodec.encode(PhotoDto.serializer(), photo), "    "))
            written++
        }
        out.append(if (written == 0) "]\n}" else "\n  ]\n}")
        return written
    }

    private fun <T> field(out: Appendable, name: String, serializer: KSerializer<T>, value: T) {
        out.append("  \"").append(name).append("\": ")
        out.append(indent(BackupCodec.encode(serializer, value), "  "))
        out.append(",\n")
    }

    // Pretty-printed JSON never has a raw line break inside a string (they are written as \n),
    // so every line break is structure and can take the extra indent.
    private fun indent(json: String, by: String): String = json.replace("\n", "\n" + by)
}
