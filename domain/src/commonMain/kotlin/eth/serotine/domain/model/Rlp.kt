package eth.serotine.domain.model

/**
 * Recursive Length Prefix, Ethereum's serialization (the yellow paper, appendix B): encoding, and
 * the strict reading back of what this wallet signed itself ([decode]). An item is a byte string or a list of items; a list is
 * built from items already encoded, so nesting is the caller's composition of [encodeList]s.
 * Integers are byte strings: their minimal big-endian bytes, zero the empty string.
 */
internal object Rlp {
    private const val STRING_OFFSET = 0x80
    private const val LIST_OFFSET = 0xc0

    /** The longest payload whose length fits in the prefix byte itself. */
    private const val SHORT_MAX = 55

    /**
     * [bytes] as an RLP string: a single byte below 0x80 is its own encoding; anything else is
     * prefixed with its length — 0x80 + length up to 55 bytes, beyond that 0xb7 + the length's
     * own byte count, then the length, big-endian.
     */
    fun encodeString(bytes: ByteArray): ByteArray =
        if (bytes.size == 1 && (bytes[0].toInt() and 0xff) < STRING_OFFSET) {
            bytes.copyOf()
        } else {
            prefix(bytes.size, STRING_OFFSET) + bytes
        }

    /**
     * The RLP list of [items], each already RLP-encoded: their concatenation, prefixed with its
     * length as [encodeString] prefixes a string's, from 0xc0 instead of 0x80.
     */
    fun encodeList(items: List<ByteArray>): ByteArray {
        val payload = ByteArray(items.sumOf { it.size })
        var offset = 0
        for (item in items) {
            item.copyInto(payload, offset)
            offset += item.size
        }
        return prefix(payload.size, LIST_OFFSET) + payload
    }

    /** [value] as an RLP integer: its minimal big-endian bytes as a string, so zero is 0x80. */
    fun encodeUInt(value: UInt256): ByteArray = encodeString(value.toBigEndian())

    /** [value], which is not negative, as an RLP integer. */
    fun encodeUInt(value: Long): ByteArray = encodeUInt(UInt256.of(value))

    private fun prefix(length: Int, offset: Int): ByteArray {
        if (length <= SHORT_MAX) return byteArrayOf((offset + length).toByte())
        val lengthBytes = UInt256.of(length.toLong()).toBigEndian()
        return byteArrayOf((offset + SHORT_MAX + lengthBytes.size).toByte()) + lengthBytes
    }

    /** An RLP item read back: a byte string, or a list of items. */
    sealed interface Item {
        data class Bytes(val bytes: ByteArray) : Item {
            override fun equals(other: Any?): Boolean = other is Bytes && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class Items(val items: List<Item>) : Item
    }

    /** [data] as exactly one RLP item, nothing after it, nested at most 8 deep; null when it is not that. */
    fun decode(data: ByteArray): Item? = try {
        val (item, end) = read(data, 0, depth = 0)
        item.takeIf { end == data.size }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun read(data: ByteArray, at: Int, depth: Int): Pair<Item, Int> {
        require(depth <= 8)
        val first = data[at].toInt() and 0xff
        fun length(bytes: Int, from: Int): Int {
            require(bytes in 1..4 && data[from] != 0.toByte())
            var value = 0L
            for (i in 0 until bytes) value = (value shl 8) or (data[from + i].toLong() and 0xff)
            require(value in (SHORT_MAX + 1)..Int.MAX_VALUE.toLong())
            return value.toInt()
        }
        return when {
            first < STRING_OFFSET -> Item.Bytes(byteArrayOf(data[at])) to at + 1
            first <= STRING_OFFSET + SHORT_MAX -> {
                val size = first - STRING_OFFSET
                val bytes = data.copyOfRange(at + 1, at + 1 + size).also { require(it.size == size) }
                // A single byte below 0x80 is its own encoding: a prefix before it is not canonical.
                require(!(size == 1 && (bytes[0].toInt() and 0xff) < STRING_OFFSET))
                Item.Bytes(bytes) to at + 1 + size
            }
            first < LIST_OFFSET -> {
                val lengthBytes = first - STRING_OFFSET - SHORT_MAX
                val size = length(lengthBytes, at + 1)
                val start = at + 1 + lengthBytes
                require(start + size <= data.size)
                Item.Bytes(data.copyOfRange(start, start + size)) to start + size
            }
            else -> {
                val (start, size) = if (first <= LIST_OFFSET + SHORT_MAX) {
                    at + 1 to first - LIST_OFFSET
                } else {
                    val lengthBytes = first - LIST_OFFSET - SHORT_MAX
                    at + 1 + lengthBytes to length(lengthBytes, at + 1)
                }
                val end = start + size
                require(end <= data.size)
                val items = mutableListOf<Item>()
                var next = start
                while (next < end) {
                    val (item, after) = read(data, next, depth + 1)
                    items += item
                    next = after
                }
                require(next == end)
                Item.Items(items) to end
            }
        }
    }
}
