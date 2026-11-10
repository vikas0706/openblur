package io.github.vikas0706.openblur.export

/** File names for saved exports: Movies/OpenBlur/<name>.mp4, then "<name> (2).mp4", "<name> (3).mp4" and so on. */
internal object OutputNames {
    const val FOLDER = "OpenBlur"
    const val EXTENSION = ".mp4"
    const val MIME_TYPE = "video/mp4"
    const val FALLBACK_BASE = "video"

    // Leaves room inside the 255 byte file name limit for " (9999).mp4" and MediaStore's ".pending-<time>-" prefix.
    private const val MAX_BASE_BYTES = 180

    private const val FORBIDDEN = "\\/:*?\"<>|"

    /** Turns a requested display name into a base name that every Android file system accepts. */
    fun sanitize(displayName: String): String {
        val cleaned = StringBuilder(displayName.length)
        for (ch in displayName) {
            cleaned.append(if (ch < ' ' || ch == '\u007f' || ch in FORBIDDEN) ' ' else ch)
        }
        var base = cleaned.toString().replace(Regex(" +"), " ").trim()
        if (base.endsWith(EXTENSION, ignoreCase = true)) base = base.dropLast(EXTENSION.length)
        // A leading dot hides the file from galleries; a trailing dot or space is dropped by FAT volumes.
        base = base.trim(' ', '.')
        base = truncateUtf8(base, MAX_BASE_BYTES).trim(' ', '.')
        return base.ifEmpty { FALLBACK_BASE }
    }

    /** [attempt] 1 is the plain name; 2 and up carry the counter. */
    fun candidate(base: String, attempt: Int): String =
        if (attempt <= 1) base + EXTENSION else "$base ($attempt)$EXTENSION"

    /** The first candidate not in [taken]. Names compare without case because shared storage does. */
    fun unique(base: String, taken: Collection<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        var attempt = 1
        while (true) {
            val name = candidate(base, attempt)
            if (name.lowercase() !in used) return name
            attempt++
        }
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            index += Character.charCount(codePoint)
        }
        return text.substring(0, index)
    }
}
