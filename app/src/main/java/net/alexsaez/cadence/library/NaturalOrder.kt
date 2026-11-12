package net.alexsaez.cadence.library

/**
 * Compares strings the way humans expect file names to be ordered: digit runs are compared
 * numerically, so "Part 2" < "Part 10", and letters are compared case-insensitively.
 */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a).map { it.value }.toList()
        val cb = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val result = if (x[0].isDigit() && y[0].isDigit()) {
                compareNumeric(x, y)
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (result != 0) return result
        }
        return ca.size.compareTo(cb.size).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun compareNumeric(x: String, y: String): Int {
        val tx = x.trimStart('0')
        val ty = y.trimStart('0')
        if (tx.length != ty.length) return tx.length.compareTo(ty.length)
        val cmp = tx.compareTo(ty)
        // Same value: fewer leading zeros first so ordering stays total.
        return if (cmp != 0) cmp else x.length.compareTo(y.length)
    }
}
