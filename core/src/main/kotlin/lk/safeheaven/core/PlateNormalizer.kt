package lk.safeheaven.core

/**
 * Normalises raw OCR text into a Sri Lankan registration plate.
 *
 * Supported shapes (after uppercase + stripping spaces/symbols + removing Sinhala/Tamil prefixes):
 *  - modern:  <province 1-2 letters> <2-3 letters> <4 digits>   e.g. WP CAB-1234, CAB-1234, SP KA-1234
 *  - older:   <2-3 digits> <4 digits>                            e.g. 251-1234
 *
 * OCR confusions are fixed by position: letter slots map 0->O 1->I 8->B 5->S 2->Z, digit slots
 * map O->0 I->1 B->8 S->5 Z->2.
 */
object PlateNormalizer {
    private val PROVINCES = setOf("WP", "CP", "SP", "NP", "EP", "NW", "NC", "UVA", "UP", "SG", "SAB")
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '8' to 'B', '5' to 'S', '2' to 'Z')
    private val toDigit = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'B' to '8', 'S' to '5', 'Z' to '2')

    data class Result(val plate: String?, val display: String?, val candidate: String)

    fun clean(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun normalize(raw: String): Result {
        // Try each line / token window so extra text on the plate (e.g. "SRI LANKA") is ignored.
        val lines = raw.lines().map(::clean).filter { it.length >= 6 }
        val whole = clean(raw)
        for (c in lines + whole) {
            matchAny(c)?.let { return Result(it, display(it), c) }
        }
        return Result(null, null, whole)
    }

    private fun matchAny(cleaned: String): String? {
        // Slide a window: the plate is at most 9 chars (province 2 + letters 3 + digits 4).
        for (len in 9 downTo 6) {
            for (start in 0..(cleaned.length - len).coerceAtLeast(-1)) {
                if (start + len > cleaned.length) break
                match(cleaned.substring(start, start + len))?.let { return it }
            }
        }
        return null
    }

    private fun match(c: String): String? {
        // Older numeric: 2-3 digits + 4 digits
        if (c.length in 6..7) {
            val digits = c.map { toDigit[it] ?: it }
            if (digits.all { it in '0'..'9' }) return digits.joinToString("")
        }
        // Modern: split as <letters prefix 2..5><4 digits>
        if (c.length < 6) return null
        val digitPart = c.takeLast(4).map { toDigit[it] ?: it }
        if (!digitPart.all { it in '0'..'9' }) return null
        val letters = c.dropLast(4).map { toLetter[it] ?: it }
        if (!letters.all { it in 'A'..'Z' }) return null
        val lp = letters.joinToString("")
        val ok = when (lp.length) {
            2, 3 -> true // CAB-1234 / KA-1234 (no province, e.g. newer or two-letter series)
            4 -> true    // province(2) + 2 letters
            5 -> lp.take(2) in PROVINCES || lp.take(3) in PROVINCES // province(2-3)+ 2-3 letters
            else -> false
        }
        return if (ok) lp + digitPart.joinToString("") else null
    }

    fun display(plate: String): String = when {
        plate.length >= 6 && plate.takeLast(4).all { it.isDigit() } && plate.dropLast(4).all { it.isLetter() } -> {
            val l = plate.dropLast(4)
            val d = plate.takeLast(4)
            when {
                l.length >= 5 -> "${l.take(l.length - 3)} ${l.takeLast(3)}-$d"
                l.length == 4 -> "${l.take(2)} ${l.takeLast(2)}-$d"
                else -> "$l-$d"
            }
        }
        plate.all { it.isDigit() } && plate.length >= 6 -> "${plate.dropLast(4)}-${plate.takeLast(4)}"
        else -> plate
    }

    /** Validates a manually edited plate (manual override still has to be encodable on the mesh). */
    fun isAcceptable(input: String): Boolean {
        val c = clean(input)
        return c.length in 5..10
    }
}
