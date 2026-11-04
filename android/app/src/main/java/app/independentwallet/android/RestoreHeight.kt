package app.independentwallet.android

/** Validate again when the action runs; an enabled button is not validation. */
internal fun parseRestoreHeight(value: String): ULong? {
    if (value.length !in 1..10 || value.any { it !in '0'..'9' }) return null
    return value.toULongOrNull()?.takeIf { it <= UInt.MAX_VALUE.toULong() }
}
