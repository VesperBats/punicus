package eth.serotine.domain.model

/**
 * What typed data lets someone do, for the structs this app knows (docs/walletconnect.md, *Typed data*).
 * Every other struct is shown field by field and marked as not decoded. A struct is known only when its
 * type is exactly the standard's (`encodeType` equal, member by member) and, for Permit2 and CoW
 * Protocol, the domain names the contract that standard is verified by: the same struct for another
 * contract can mean anything.
 */
sealed interface TypedDataMeaning {

    /**
     * An ERC-2612 `Permit`: [owner] lets [spender] take up to [value] of [token] (the domain's verifying
     * contract) until [deadline] (unix seconds). It moves nothing itself.
     */
    data class Permit(
        val token: Address,
        val owner: Address,
        val spender: Address,
        val value: UInt256,
        val deadline: UInt256,
    ) : TypedDataMeaning

    /**
     * Permit2's `PermitSingle`: lets [spender] take up to [amount] of [token] through Permit2 until
     * [expiration] (unix seconds; 0 is only the block it is used in), the signature good until [sigDeadline].
     */
    data class Permit2Allowance(
        val token: Address,
        val amount: UInt256,
        val expiration: UInt256,
        val spender: Address,
        val sigDeadline: UInt256,
    ) : TypedDataMeaning

    /** Permit2's `PermitTransferFrom`: lets [spender] take [amount] of [token] once, until [deadline]. */
    data class Permit2Transfer(
        val token: Address,
        val amount: UInt256,
        val spender: Address,
        val deadline: UInt256,
    ) : TypedDataMeaning

    /**
     * A CoW Protocol (GPv2) order: sell [sellAmount] of [sellToken] for at least [buyAmount] of [buyToken]
     * ([kind] "sell"), or buy exactly [buyAmount] for at most [sellAmount] ("buy"), paid to [receiver] (the
     * zero address: the owner), valid until [validTo].
     */
    data class CowOrder(
        val sellToken: Address,
        val buyToken: Address,
        val receiver: Address,
        val sellAmount: UInt256,
        val buyAmount: UInt256,
        val validTo: UInt256,
        val kind: String,
        val partiallyFillable: Boolean,
    ) : TypedDataMeaning

    /**
     * A Safe transaction ([tx]) of the Safe at [safe] (the domain's verifying contract), for one of its owners to
     * sign, as Safe{Wallet} asks an owner connected over WalletConnect: Safe's own `SafeTx` struct, unchanged since
     * 1.0.0, with the domain of Safe 1.3.0 on (`chainId`, `verifyingContract`) or the one before it
     * (`verifyingContract` alone). What it does is decoded from its fields ([SafeTxAction]), as screen 17 decodes
     * one (docs/walletconnect.md, *A Safe transaction to sign*).
     */
    data class SafeTransaction(val safe: Address, val tx: SafeTx) : TypedDataMeaning

    /**
     * A message signed for the Safe at [safe] (the domain's verifying contract), for one of its owners to sign, as
     * Safe{Wallet} asks for an off-chain signature of the Safe (EIP-1271): Safe's `SafeMessage(bytes message)`.
     * [message] is what the struct holds, as lower-case hex: for Safe{Wallet}, the hash of the message the dApp
     * asked the Safe to sign, from which that message cannot be read back.
     */
    data class SafeMessage(val safe: Address, val message: String) : TypedDataMeaning

    /**
     * Session keys granted on smart accounts, one per chain ([SmartSessionGrants]): Rhinestone's Smart Sessions v2
     * `MultiChainSession`. Its domain names no contract, so nothing binds it to code: it is read from the struct.
     */
    data class SmartSession(val grants: SmartSessionGrants) : TypedDataMeaning

    /**
     * A transaction a Zodiac modifier's member signs for a relayer to send ([ModuleTransaction]): Gnosis Pay's app
     * asks a Delay member for one to change the card's limit, or anything else the Safe may do through the Delay.
     */
    data class ModuleTx(val tx: ModuleTransaction) : TypedDataMeaning

    companion object {
        /** Permit2's address, the same on every chain. */
        val PERMIT2: Address = Address.of("0x000000000022d473030f116ddee9f6b43ac78ba3")

        /** CoW Protocol's settlement contract, the same on every chain. */
        val GPV2_SETTLEMENT: Address = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")

        private const val PERMIT =
            "Permit(address owner,address spender,uint256 value,uint256 nonce,uint256 deadline)"
        private const val PERMIT_SINGLE = "PermitSingle(PermitDetails details,address spender,uint256 sigDeadline)" +
            "PermitDetails(address token,uint160 amount,uint48 expiration,uint48 nonce)"
        private const val PERMIT_TRANSFER =
            "PermitTransferFrom(TokenPermissions permitted,address spender,uint256 nonce,uint256 deadline)" +
                "TokenPermissions(address token,uint256 amount)"

        private const val SAFE_TX = "SafeTx(address to,uint256 value,bytes data,uint8 operation,uint256 safeTxGas," +
            "uint256 baseGas,uint256 gasPrice,address gasToken,address refundReceiver,uint256 nonce)"
        private const val SAFE_MESSAGE = "SafeMessage(bytes message)"

        // Safe's domains: 1.3.0 on, and the one before it, which names no chain.
        private const val SAFE_DOMAIN = "EIP712Domain(uint256 chainId,address verifyingContract)"
        private const val OLD_SAFE_DOMAIN = "EIP712Domain(address verifyingContract)"

        /**
         * The struct types this decoder claims, as their `encodeType`: what it says of one of them stands, "not
         * decoded" included, and no clear-signing descriptor describes it (docs/clear-signing.md, A3).
         */
        val CLAIMED_TYPES: Set<String>
            get() = setOf(
                PERMIT, PERMIT_SINGLE, PERMIT_TRANSFER, GPv2Signing.ORDER_TYPE, SAFE_TX, SAFE_MESSAGE,
                SmartSessionGrants.MULTI_CHAIN_SESSION_TYPE, ModuleTransaction.TYPE,
            )

        /** What [encoded] lets someone do, or null when its struct is not one this app knows. */
        fun of(encoded: EncodedTypedData): TypedDataMeaning? {
            val data = encoded.data
            val type = Eip712.encodeType(data.types, data.primaryType)
            val verifier = data.domain["verifyingContract"]?.let(::address)
            val message = data.message
            return when (type) {
                PERMIT -> Permit(
                    token = verifier ?: return null,
                    owner = address(message.getValue("owner")),
                    spender = address(message.getValue("spender")),
                    value = uint(message.getValue("value")),
                    deadline = uint(message.getValue("deadline")),
                )
                PERMIT_SINGLE -> {
                    if (verifier != PERMIT2) return null
                    val details = (message.getValue("details") as TypedValue.Struct).fields
                    Permit2Allowance(
                        token = address(details.getValue("token")),
                        amount = uint(details.getValue("amount")),
                        expiration = uint(details.getValue("expiration")),
                        spender = address(message.getValue("spender")),
                        sigDeadline = uint(message.getValue("sigDeadline")),
                    )
                }
                PERMIT_TRANSFER -> {
                    if (verifier != PERMIT2) return null
                    val permitted = (message.getValue("permitted") as TypedValue.Struct).fields
                    Permit2Transfer(
                        token = address(permitted.getValue("token")),
                        amount = uint(permitted.getValue("amount")),
                        spender = address(message.getValue("spender")),
                        deadline = uint(message.getValue("deadline")),
                    )
                }
                GPv2Signing.ORDER_TYPE -> {
                    if (verifier != GPV2_SETTLEMENT) return null
                    val kind = (message.getValue("kind") as TypedValue.Text).text
                    // Only GPv2's own values: another kind, or a Balancer vault balance, is not decoded.
                    if (kind != "sell" && kind != "buy") return null
                    val balances = listOf("sellTokenBalance", "buyTokenBalance")
                        .map { (message.getValue(it) as TypedValue.Text).text }
                    if (balances.any { it != "erc20" }) return null
                    // A fee is taken on top of the sell amount: this app's own orders carry none, and one that
                    // does is not decoded, so the headline never understates what is signed away.
                    if (!uint(message.getValue("feeAmount")).isZero) return null
                    CowOrder(
                        sellToken = address(message.getValue("sellToken")),
                        buyToken = address(message.getValue("buyToken")),
                        receiver = address(message.getValue("receiver")),
                        sellAmount = uint(message.getValue("sellAmount")),
                        buyAmount = uint(message.getValue("buyAmount")),
                        validTo = uint(message.getValue("validTo")),
                        kind = kind,
                        partiallyFillable = (message.getValue("partiallyFillable") as TypedValue.Bool).value,
                    )
                }
                SAFE_TX -> {
                    if (!safeDomain(data)) return null
                    val operation = SafeOperation.of(uint(message.getValue("operation")).toLongOrNull()?.toInt() ?: -1)
                        ?: return null
                    // A nonce past a Long is no nonce a Safe reaches: not decoded.
                    val nonce = uint(message.getValue("nonce")).toLongOrNull() ?: return null
                    SafeTransaction(
                        safe = verifier ?: return null,
                        tx = SafeTx(
                            to = address(message.getValue("to")),
                            value = uint(message.getValue("value")),
                            data = Eip712.bytesOf(message.getValue("data")),
                            operation = operation,
                            safeTxGas = uint(message.getValue("safeTxGas")),
                            baseGas = uint(message.getValue("baseGas")),
                            gasPrice = uint(message.getValue("gasPrice")),
                            gasToken = address(message.getValue("gasToken")),
                            refundReceiver = address(message.getValue("refundReceiver")),
                            nonce = nonce,
                        ),
                    )
                }
                SmartSessionGrants.MULTI_CHAIN_SESSION_TYPE -> SmartSessionGrants.of(encoded)?.let(::SmartSession)
                ModuleTransaction.TYPE -> ModuleTransaction.of(encoded)?.let(::ModuleTx)
                SAFE_MESSAGE -> {
                    if (!safeDomain(data)) return null
                    SafeMessage(verifier ?: return null, Eip712.bytesOf(message.getValue("message")).toHex())
                }
                else -> null
            }
        }

        /** Whether [data]'s domain is a Safe's: exactly the members of one of Safe's own domains. */
        private fun safeDomain(data: TypedData): Boolean =
            Eip712.encodeType(data.types, Eip712.DOMAIN_TYPE).let { it == SAFE_DOMAIN || it == OLD_SAFE_DOMAIN }

        // The values were checked against their types by Eip712.encode: these only read them.
        private fun address(value: TypedValue): Address = Address.of((value as TypedValue.Text).text)

        private fun uint(value: TypedValue): UInt256 = Eip712.unsignedOf(value)
    }
}
