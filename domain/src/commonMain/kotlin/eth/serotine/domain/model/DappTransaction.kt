package eth.serotine.domain.model

/**
 * What a dApp's `eth_sendTransaction` asks (docs/walletconnect.md, *Transactions*): [value] wei and
 * [data] from [from] to [to], and, when the dApp gives one, the [gas] limit it asks for. Every field of
 * the request is applied or refused, never accepted and ignored (Myotis #314): these are the ones
 * applied; the adapter refuses the others (a nonce, fee fields, another chain or type, an access list).
 * At most [MAX_DATA_BYTES] of data.
 */
class DappTransactionRequest(
    val from: Address,
    val to: Address,
    val value: UInt256,
    data: ByteArray,
    val gas: Long?,
) {
    private val callData: ByteArray = data.copyOf()

    init {
        require(callData.size <= MAX_DATA_BYTES) { "call data of more than $MAX_DATA_BYTES bytes" }
        require(gas == null || gas > 0) { "a gas limit is positive" }
    }

    /** The call data, empty for a plain transfer of ether: a copy. */
    val data: ByteArray get() = callData.copyOf()

    /** The call data as lower-case hex, no prefix. */
    val dataHex: String get() = callData.toHex()

    /** How many bytes of call data it has. */
    val dataSize: Int get() = callData.size

    /** What its call does, for the calls this app knows: decoded once, its data being immutable. */
    val meaning: DappCallMeaning by lazy {
        when {
            callData.isEmpty() -> DappCallMeaning.Payment
            else -> Erc20.transferOf(callData)?.let { DappCallMeaning.TokenTransfer(to, it.to, it.amount) }
                ?: Erc20.approveOf(callData)?.let { DappCallMeaning.TokenApproval(to, it.spender, it.amount) }
                ?: SafeExecCall.of(callData)?.let { DappCallMeaning.SafeExecution(to, it) }
                // On chain the Safe still runs one it does not read as exactly: trailing bytes, any offset.
                ?: DappCallMeaning.SafeExecutionNotDecoded(to).takeIf { SafeExecCall.isExecTransaction(callData) }
                ?: SafeExecCall.approvedHashOf(callData)?.let { DappCallMeaning.SafeApproveHash(to, it) }
                ?: DappCallMeaning.NotDecoded(callData.copyOf(4).toHex().takeIf { callData.size >= 4 })
        }
    }

    override fun equals(other: Any?): Boolean = other is DappTransactionRequest && from == other.from &&
        to == other.to && value == other.value && callData.contentEquals(other.callData) && gas == other.gas

    override fun hashCode(): Int =
        listOf(from, to, value, callData.contentHashCode(), gas).hashCode()

    override fun toString(): String =
        "DappTransactionRequest(${from.hex} → ${to.hex}, $value wei, ${callData.size} bytes)"

    companion object {
        /** The most call data a dApp's transaction may carry. */
        const val MAX_DATA_BYTES = 128 * 1024
    }
}

/** What a dApp's transaction does, for the calls this app knows; anything else is not decoded. */
sealed interface DappCallMeaning {
    /** No call data: a payment of the transaction's value to its recipient. */
    data object Payment : DappCallMeaning

    /** An ERC-20 `transfer` of [amount] of [token] to [recipient]. */
    data class TokenTransfer(val token: Address, val recipient: Address, val amount: UInt256) : DappCallMeaning

    /** An ERC-20 `approve`: [spender] may take up to [amount] of [token]. It moves nothing itself. */
    data class TokenApproval(val token: Address, val spender: Address, val amount: UInt256) : DappCallMeaning

    /**
     * A Safe's `execTransaction`, called on [safe] (the transaction's recipient): it runs [call], the Safe
     * transaction its owners signed, now, at the Safe's nonce then. Safe{Wallet} asks an owner connected over
     * WalletConnect to send one to execute a transaction (docs/walletconnect.md, *A Safe transaction to sign*).
     */
    data class SafeExecution(val safe: Address, val call: SafeExecCall) : DappCallMeaning

    /**
     * A call of `execTransaction` on [safe] whose data is not exactly that function's encoding (a trailing byte, an
     * offset of its own, a dirty address word): the Safe would still run a transaction from it, which is not known
     * here, so it is refused as a call of the Safe not decoded exactly.
     */
    data class SafeExecutionNotDecoded(val safe: Address) : DappCallMeaning

    /**
     * A Safe's `approveHash(hash)`, called on [safe]: the sender approves, on chain and as an owner, the Safe
     * transaction whose `safeTxHash` is [hash]. What that transaction does is not in the call.
     */
    data class SafeApproveHash(val safe: Address, val hash: Digest) : DappCallMeaning

    /** A call this app does not decode, [selector] its first four bytes as hex (null with fewer). */
    data class NotDecoded(val selector: String?) : DappCallMeaning
}

/**
 * The Safe transaction an `execTransaction` call runs, as its arguments name it: [operation] on [to] with [value] wei
 * and [data], its gas and refund terms, and [signaturesSize] bytes of owners' signatures. Its nonce is not among
 * them: the Safe's own nonce when it runs. Equal by value, [data] included.
 */
class SafeExecCall(
    val to: Address,
    val value: UInt256,
    data: ByteArray,
    val operation: SafeOperation,
    val safeTxGas: UInt256,
    val baseGas: UInt256,
    val gasPrice: UInt256,
    val gasToken: Address,
    val refundReceiver: Address,
    val signaturesSize: Int,
) {
    private val callData: ByteArray = data.copyOf()

    /** The call data: a copy. */
    val data: ByteArray get() = callData.copyOf()

    /** What it does for the Safe at [safe], decoded as screen 17 decodes a Safe transaction. */
    fun action(safe: Address): SafeTxAction = SafeTxAction.of(safe, to, value, callData, operation)

    /** Its gas and refund terms, or null when it carries none. */
    val gasTerms: SafeTxGasTerms?
        get() = SafeTxGasTerms.of(SafeTx(to, value, callData, operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver, 0))

    override fun equals(other: Any?): Boolean = other is SafeExecCall && to == other.to && value == other.value &&
        callData.contentEquals(other.callData) && operation == other.operation && safeTxGas == other.safeTxGas &&
        baseGas == other.baseGas && gasPrice == other.gasPrice && gasToken == other.gasToken &&
        refundReceiver == other.refundReceiver && signaturesSize == other.signaturesSize

    override fun hashCode(): Int = listOf(
        to, value, callData.contentHashCode(), operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver,
        signaturesSize,
    ).hashCode()

    override fun toString(): String = "SafeExecCall($operation ${to.hex}, $value wei, ${callData.size} bytes)"

    companion object {
        private val EXEC_TRANSACTION = AbiWords.selector(
            "execTransaction(address,uint256,bytes,uint8,uint256,uint256,uint256,address,address,bytes)",
        )
        private val APPROVE_HASH = AbiWords.selector("approveHash(bytes32)")

        // execTransaction's ten head words: its two `bytes` arguments are the third and the tenth.
        private const val HEAD_WORDS = 10

        /**
         * The Safe transaction [data] runs when it is exactly the call data of `execTransaction`, as the ABI
         * encodes it (its two `bytes` arguments each at the offset that follows what comes before it, zero-padded,
         * nothing after them, every static word inside its type); null for anything else.
         */
        fun of(data: ByteArray): SafeExecCall? {
            val word = AbiWords.WORD
            if (data.size < AbiWords.SELECTOR + HEAD_WORDS * word) return null
            if (!data.copyOf(AbiWords.SELECTOR).contentEquals(EXEC_TRANSACTION)) return null
            val body = data.copyOfRange(AbiWords.SELECTOR, data.size)
            fun head(index: Int) = body.copyOfRange(index * word, (index + 1) * word)
            var expected = HEAD_WORDS * word
            /** The `bytes` whose offset is head word [index], when it is at [expected], read strictly. */
            fun bytesAt(index: Int): ByteArray? {
                if (AbiWords.long(head(index)) != expected.toLong()) return null
                if (body.size < expected + word) return null
                val length = AbiWords.long(body.copyOfRange(expected, expected + word))
                    ?.takeIf { it <= body.size - expected - word }?.toInt() ?: return null
                val start = expected + word
                val end = start + length + AbiWords.padding(length)
                if (end > body.size || (start + length until end).any { body[it] != 0.toByte() }) return null
                expected = end
                return body.copyOfRange(start, start + length)
            }
            val callData = bytesAt(2) ?: return null
            val signatures = bytesAt(9) ?: return null
            if (expected != body.size) return null
            val operation = AbiWords.uint(head(3), 8)?.toLongOrNull()?.let { SafeOperation.of(it.toInt()) } ?: return null
            return SafeExecCall(
                to = AbiWords.address(head(0)) ?: return null,
                value = AbiWords.uint(head(1)),
                data = callData,
                operation = operation,
                safeTxGas = AbiWords.uint(head(4)),
                baseGas = AbiWords.uint(head(5)),
                gasPrice = AbiWords.uint(head(6)),
                gasToken = AbiWords.address(head(7)) ?: return null,
                refundReceiver = AbiWords.address(head(8)) ?: return null,
                signaturesSize = signatures.size,
            )
        }

        /** Whether [data] starts with `execTransaction`'s selector, decoded exactly or not. */
        fun isExecTransaction(data: ByteArray): Boolean =
            data.size >= AbiWords.SELECTOR && data.copyOf(AbiWords.SELECTOR).contentEquals(EXEC_TRANSACTION)

        /** The hash [data] approves when it is exactly the call data of `approveHash(bytes32)`; null otherwise. */
        fun approvedHashOf(data: ByteArray): Digest? {
            val (hash) = AbiWords.staticArguments(data, APPROVE_HASH, 1) ?: return null
            return Digest.of(hash.toHex())
        }

        /**
         * The call data of `execTransaction` running [tx] (its nonce left out, as the call has none) with
         * [signatures]: what [of] reads back. For tests and previews.
         */
        fun data(tx: SafeTx, signatures: ByteArray): ByteArray {
            val word = AbiWords.WORD
            val callTail = AbiWords.dynamic(tx.data)
            return EXEC_TRANSACTION +
                AbiWords.word(tx.to) + tx.value.toBytes32() + AbiWords.word((HEAD_WORDS * word).toLong()) +
                AbiWords.word(tx.operation.code.toLong()) + tx.safeTxGas.toBytes32() + tx.baseGas.toBytes32() +
                tx.gasPrice.toBytes32() + AbiWords.word(tx.gasToken) + AbiWords.word(tx.refundReceiver) +
                AbiWords.word((HEAD_WORDS * word + callTail.size).toLong()) + callTail + AbiWords.dynamic(signatures)
        }
    }
}
