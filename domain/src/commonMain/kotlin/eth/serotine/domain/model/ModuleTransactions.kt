package eth.serotine.domain.model

/**
 * A transaction a Zodiac modifier's member signs for a relayer to send: the modifier's EIP-712 `ModuleTx(bytes data,
 * bytes32 salt)`, domain `{chainId, verifyingContract}` the modifier, as Gnosis Pay's app asks a Delay member to sign
 * one (account-kit's `typedDataForModifierTransaction`; docs/gnosis-pay.md, *A change signed for Gnosis Pay's app*).
 * Signed, it lets anyone hand [data] to the [modifier] as the member's own call: for a Delay, its
 * `execTransactionFromModule`, which queues the Safe's [call] for execution after the cooldown. [salt] (hex) keeps a
 * signature from being used twice.
 */
data class ModuleTransaction(val modifier: Address, val salt: String, val dataSize: Int, val call: ModuleCall?) {
    companion object {
        private const val MODULE_TX = "ModuleTx(bytes data,bytes32 salt)"
        private const val DOMAIN = "EIP712Domain(uint256 chainId,address verifyingContract)"

        /** The struct type it claims, as its `encodeType`. */
        const val TYPE = MODULE_TX

        /** What [encoded] asks, when it is exactly a modifier's `ModuleTx`; null otherwise. */
        fun of(encoded: EncodedTypedData): ModuleTransaction? {
            val data = encoded.data
            if (Eip712.encodeType(data.types, data.primaryType) != MODULE_TX) return null
            if (Eip712.encodeType(data.types, Eip712.DOMAIN_TYPE) != DOMAIN) return null
            val modifier = (data.domain["verifyingContract"] as? TypedValue.Text)?.text?.let(Address::parse) ?: return null
            val bytes = Eip712.bytesOf(data.message.getValue("data"))
            val salt = Eip712.bytesOf(data.message.getValue("salt")).toHex()
            return ModuleTransaction(modifier, salt, bytes.size, ModuleCall.of(bytes))
        }
    }
}

/**
 * What a member asks a modifier to have the Safe do: `execTransactionFromModule(to, value, data, operation)`, read
 * strictly, [operation] on [to] with [value] wei and [data]; [allowance] when it sets a Zodiac Roles allowance.
 * Equal by value.
 */
class ModuleCall(val to: Address, val value: UInt256, data: ByteArray, val operation: SafeOperation) {
    private val bytes = data.copyOf()

    /** The call data: a copy. */
    val data: ByteArray get() = bytes.copyOf()

    /**
     * What the Safe does, decoded as a Safe transaction is: a change of its own settings decoded where [to] is the
     * Safe (which a modifier's data does not name, so a settings call is read as the Safe's own), any other call as
     * the Safe's call of [to].
     */
    val action: SafeTxAction
        get() = SafeTxAction.of(to, to, value, bytes, operation).takeIf { it is SafeTxAction.SettingsChange }
            ?: SafeTxAction.of(Address.ZERO, to, value, bytes, operation)

    /** The Roles allowance it sets, when it is exactly `setAllowance` with no value, as a call. */
    val allowance: RolesAllowance?
        get() = RolesAllowance.of(bytes).takeIf { value.isZero && operation == SafeOperation.CALL }

    override fun equals(other: Any?): Boolean = other is ModuleCall && to == other.to && value == other.value &&
        bytes.contentEquals(other.bytes) && operation == other.operation

    override fun hashCode(): Int = listOf(to, value, bytes.contentHashCode(), operation).hashCode()

    override fun toString(): String = "ModuleCall($operation ${to.hex}, $value wei, ${bytes.size} bytes)"

    companion object {
        private val EXEC = AbiFunction.parse("execTransactionFromModule(address to,uint256 value,bytes data,uint8 operation)")!!
        private val SELECTOR = AbiWords.selector("execTransactionFromModule(address,uint256,bytes,uint8)")

        /** The call [data] asks for, when it is exactly `execTransactionFromModule`'s encoding; null otherwise. */
        fun of(data: ByteArray): ModuleCall? {
            if (data.size < AbiWords.SELECTOR || !data.copyOf(AbiWords.SELECTOR).contentEquals(SELECTOR)) return null
            val args = AbiDecoder.decode(EXEC.params, data.copyOfRange(AbiWords.SELECTOR, data.size))
                ?.members?.map { it.second } ?: return null
            val operation = (args[3] as AbiValue.UintValue).value.toLongOrNull()?.toInt()?.let(SafeOperation::of)
                ?: return null
            return ModuleCall(
                (args[0] as AbiValue.AddressValue).address,
                (args[1] as AbiValue.UintValue).value,
                (args[2] as AbiValue.BytesValue).hex.hexToBytes(),
                operation,
            )
        }
    }
}

/**
 * A Zodiac Roles v2 allowance set: `setAllowance(key, balance, maxRefill, refill, period, timestamp)`. [balance] is
 * what may be spent now; every [period] seconds it is refilled by [refill], up to [maxRefill]; [timestamp] (unix
 * seconds; 0: now) is when its periods are counted from. In the units of what it is spent in. [spending] when
 * [key] is Gnosis Pay's `SPENDING_ALLOWANCE` (account-kit's `SPENDING_ALLOWANCE_KEY`): the card's daily limit.
 */
data class RolesAllowance(
    val key: String,
    val balance: UInt256,
    val maxRefill: UInt256,
    val refill: UInt256,
    val period: Long,
    val timestamp: Long,
) {
    /** Whether it is the Gnosis Pay card's spending limit. */
    val spending: Boolean get() = key == SPENDING_ALLOWANCE_KEY

    companion object {
        private val SET_ALLOWANCE = AbiWords.selector("setAllowance(bytes32,uint128,uint128,uint128,uint64,uint64)")

        /** keccak256("SPENDING_ALLOWANCE"), as account-kit sets the card's allowance under. */
        val SPENDING_ALLOWANCE_KEY: String = keccak256("SPENDING_ALLOWANCE".encodeToByteArray()).toHex()

        /** The allowance [data] sets, when it is exactly `setAllowance`'s encoding, every word in its type; null otherwise. */
        fun of(data: ByteArray): RolesAllowance? {
            val words = AbiWords.staticArguments(data, SET_ALLOWANCE, 6) ?: return null
            return RolesAllowance(
                key = words[0].toHex(),
                balance = AbiWords.uint(words[1], 128) ?: return null,
                maxRefill = AbiWords.uint(words[2], 128) ?: return null,
                refill = AbiWords.uint(words[3], 128) ?: return null,
                period = AbiWords.uint(words[4], 64)?.toLongOrNull() ?: return null,
                timestamp = AbiWords.uint(words[5], 64)?.toLongOrNull() ?: return null,
            )
        }
    }
}
