package eth.serotine.domain.model

/**
 * Safe's MultiSend and MultiSendCallOnly: the contracts a Safe runs, as a delegate call, to make several calls in one
 * transaction (a "batch", as Safe{Wallet} and its Transaction Builder make them). Read here for display only
 * (docs/walletconnect.md, *A Safe transaction to sign*): a delegate call is still refused (`SafeTxRefusal.DelegateCall`,
 * #57).
 *
 * The deployments are Safe's own, from safe-deployments (1.3.0 canonical and EIP-155, 1.4.1 and 1.5.0), each deployed
 * by CREATE2 from a deterministic deployer, so the address alone fixes the code: the same code on every chain, or
 * none. `MultiSendCallOnly` reverts an inner delegate call; `MultiSend` runs one.
 */
object SafeMultiSend {

    /** The MultiSend deployments, which also run an inner delegate call. */
    val MULTI_SEND: Set<Address> = setOf(
        Address.of("0xa238cbeb142c10ef7ad8442c6d1f9e89e07e7761"), // 1.3.0, canonical
        Address.of("0x998739bfdaadde7c933b942a68053933098f9eda"), // 1.3.0, EIP-155
        Address.of("0x38869bf66a61cf6bdb996a6ae40d5853fd43b526"), // 1.4.1
        Address.of("0x218543288004cd07832472d464648173c77d7eb7"), // 1.5.0
    )

    /** The MultiSendCallOnly deployments, which revert an inner delegate call. */
    val MULTI_SEND_CALL_ONLY: Set<Address> = setOf(
        Address.of("0x40a2accbd92bca938b02010e17a5b8929b49130d"), // 1.3.0, canonical
        Address.of("0xa1dabef33b3b82c7814b6d82a79e50f4ac44102b"), // 1.3.0, EIP-155
        Address.of("0x9641d764fc13c8b624c04430c7356c1c7c8102e2"), // 1.4.1
        Address.of("0xa83c336b20401af773b6219ba5027174338d1836"), // 1.5.0
    )

    /**
     * The 1.5.0 deployments, which read a call to the zero address as a call of the Safe itself
     * (`to := or(to, mul(iszero(to), address()))`, under the delegate call); 1.3.0 and 1.4.1 do not.
     */
    val ZERO_IS_SELF: Set<Address> = setOf(
        Address.of("0x218543288004cd07832472d464648173c77d7eb7"),
        Address.of("0xa83c336b20401af773b6219ba5027174338d1836"),
    )

    /** The most calls a batch is read with: a longer one is not decoded. */
    const val MAX_CALLS = 64

    private val MULTI_SEND_SELECTOR = AbiWords.selector("multiSend(bytes)")

    /** Whether [address] is one of Safe's MultiSend or MultiSendCallOnly deployments. */
    fun isBatcher(address: Address): Boolean = address in MULTI_SEND || address in MULTI_SEND_CALL_ONLY

    /**
     * The calls [data] makes when it is exactly the call data of `multiSend(bytes transactions)`: the selector, the
     * offset of its one argument (one word), its length and its bytes, zero-padded to a whole word, and nothing more;
     * the bytes each call packed as MultiSend reads them (`uint8 operation`, `address to`, `uint256 value`, `uint256
     * dataLength`, `bytes data`), with no byte left over. Null for anything else, an operation other than 0 or 1, or
     * more than [MAX_CALLS] calls: only what is unambiguously a batch is read as one.
     */
    fun callsOf(data: ByteArray): List<BatchedCall>? {
        val word = AbiWords.WORD
        val head = AbiWords.SELECTOR + 2 * word
        if (data.size < head || !data.copyOf(AbiWords.SELECTOR).contentEquals(MULTI_SEND_SELECTOR)) return null
        val offset = AbiWords.long(data.copyOfRange(AbiWords.SELECTOR, AbiWords.SELECTOR + word)) ?: return null
        if (offset != word.toLong()) return null
        val length = AbiWords.long(data.copyOfRange(AbiWords.SELECTOR + word, head))?.takeIf { it <= data.size } ?: return null
        val size = length.toInt()
        if (data.size != head + size + AbiWords.padding(size)) return null
        if ((head + size until data.size).any { data[it] != 0.toByte() }) return null
        val packed = data.copyOfRange(head, head + size)
        val calls = mutableListOf<BatchedCall>()
        var at = 0
        while (at < packed.size) {
            if (calls.size == MAX_CALLS) return null
            if (packed.size - at < PACKED_HEAD) return null
            val operation = SafeOperation.of(packed[at].toInt() and 0xff) ?: return null
            val to = Address.of("0x" + packed.copyOfRange(at + 1, at + 21).toHex())
            val value = AbiWords.uint(packed.copyOfRange(at + 21, at + 53))
            val dataLength = AbiWords.long(packed.copyOfRange(at + 53, at + PACKED_HEAD))
                ?.takeIf { it <= packed.size - at - PACKED_HEAD } ?: return null
            val start = at + PACKED_HEAD
            val end = start + dataLength.toInt()
            calls += BatchedCall(operation, to, value, packed.copyOfRange(start, end))
            at = end
        }
        return calls
    }

    /**
     * The call data of `multiSend(bytes)` making [calls], packed as MultiSend reads them: what [callsOf] reads back.
     * For tests and previews; this wallet proposes no batch.
     */
    fun data(calls: List<BatchedCall>): ByteArray {
        var packed = ByteArray(0)
        for (call in calls) {
            packed += byteArrayOf(call.operation.code.toByte()) + call.to.toBytes() + call.value.toBytes32() +
                AbiWords.word(call.dataSize.toLong()) + call.data
        }
        return MULTI_SEND_SELECTOR + AbiWords.word(AbiWords.WORD.toLong()) + AbiWords.dynamic(packed)
    }

    // One call's fixed part: the operation's byte, the address's 20, and two words.
    private const val PACKED_HEAD = 1 + 20 + 32 + 32
}

/**
 * One call of a batch ([SafeMultiSend.callsOf]): [operation] on [to], with [value] wei and [data]. Equal by value,
 * [data] included.
 */
class BatchedCall(val operation: SafeOperation, val to: Address, val value: UInt256, data: ByteArray) {
    private val callData: ByteArray = data.copyOf()

    /** The call data: a copy. */
    val data: ByteArray get() = callData.copyOf()

    /** How many bytes of call data it carries. */
    val dataSize: Int get() = callData.size

    override fun equals(other: Any?): Boolean = other is BatchedCall && operation == other.operation &&
        to == other.to && value == other.value && callData.contentEquals(other.callData)

    override fun hashCode(): Int = listOf(operation, to, value, callData.contentHashCode()).hashCode()

    override fun toString(): String = "BatchedCall($operation ${to.hex}, $value wei, ${callData.size} bytes)"
}
