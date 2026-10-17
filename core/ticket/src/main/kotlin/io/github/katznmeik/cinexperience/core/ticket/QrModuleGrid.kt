package io.github.katznmeik.cinexperience.core.ticket

/**
 * A QR code's modules as read from the ticket's image (spec §11.2): [size] × [size]
 * modules (21, 25, 29 or 33, i.e. versions 1 to 4), upright, without the quiet zone.
 * Only a grid whose finder, separator, timing and alignment patterns, dark module and
 * both copies of the format information check out is ever created; the content is
 * never decoded. Immutable.
 */
class QrModuleGrid internal constructor(val size: Int, modules: BooleanArray) {
    private val modules: BooleanArray = modules.copyOf()

    init {
        require(size in QR_SIZES) { "Unsupported QR code size $size" }
        require(modules.size == size * size) { "Expected ${size * size} modules, got ${modules.size}" }
    }

    /** True if the module in [row] (0 at the top) and [column] (0 on the left) is dark. */
    fun isDark(row: Int, column: Int): Boolean {
        require(row in 0 until size && column in 0 until size) { "Module ($row, $column) outside the $size \u00d7 $size grid" }
        return modules[row * size + column]
    }

    /** All modules row by row, top to bottom, each row left to right (`true` = dark); a copy. */
    fun toBooleanArray(): BooleanArray = modules.copyOf()

    override fun equals(other: Any?): Boolean =
        other is QrModuleGrid && other.size == size && other.modules.contentEquals(modules)

    override fun hashCode(): Int = 31 * size + modules.contentHashCode()

    /** Only the size: the modules are the booking's code and never go into logs. */
    override fun toString(): String = "QrModuleGrid(size=$size)"

    companion object {
        /** The accepted sizes: versions 1 to 4 (spec §11.2). */
        val QR_SIZES: List<Int> = listOf(21, 25, 29, 33)
    }
}
