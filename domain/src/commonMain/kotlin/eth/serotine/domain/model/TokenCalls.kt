package eth.serotine.domain.model

/**
 * A call of a token contract beyond ERC-20's `transfer` and `approve` ([Erc20]), decoded exactly
 * ([TokenCalls.of]): what MetaMask's test dApp and ordinary NFT and token dApps send. What the contract is (an
 * ERC-20 token, an ERC-721 or ERC-1155 collection) is its own claim; the call is read for what its function means
 * in the standards that define it.
 */
sealed interface TokenCall {

    /**
     * `transferFrom(from, to, value)`: ERC-20's (move [value] of `from`'s tokens, under an allowance) and ERC-721's
     * (move NFT #[value]) share it; which one is the contract's.
     */
    data class TransferFrom(val from: Address, val to: Address, val value: UInt256) : TokenCall

    /** `increaseAllowance(spender, added)`: [spender] may take [added] more of the sender's tokens. An approval. */
    data class AllowanceIncrease(val spender: Address, val added: UInt256) : TokenCall

    /**
     * `setApprovalForAll(operator, approved)` (ERC-721, ERC-1155): [operator] may take every NFT of the collection
     * the sender holds, now and later ([approved]), or no longer may (not [approved]).
     */
    data class ApprovalForAll(val operator: Address, val approved: Boolean) : TokenCall

    /** ERC-721's `safeTransferFrom(from, to, tokenId[, data])`: NFT #[tokenId] to [to], with [dataSize] bytes for it. */
    data class NftTransfer(val from: Address, val to: Address, val tokenId: UInt256, val dataSize: Int) : TokenCall

    /** ERC-1155's `safeTransferFrom(from, to, id, amount, data)`: [amount] of token [id] to [to]. */
    data class MultiTokenTransfer(val from: Address, val to: Address, val id: UInt256, val amount: UInt256) : TokenCall

    /** ERC-1155's `safeBatchTransferFrom(from, to, ids, amounts, data)`: each id's amount to [to]. */
    data class MultiTokenBatchTransfer(val from: Address, val to: Address, val items: List<Pair<UInt256, UInt256>>) :
        TokenCall

    /** Whether it grants an approval: lets someone take tokens later without asking. */
    val grantsApproval: Boolean
        get() = this is AllowanceIncrease || (this is ApprovalForAll && approved)
}

/** Reads [TokenCall]s from call data, strictly ([AbiDecoder]). */
object TokenCalls {
    private val FUNCTIONS = listOf(
        "transferFrom(address,address,uint256)",
        "increaseAllowance(address,uint256)",
        "setApprovalForAll(address,bool)",
        "safeTransferFrom(address,address,uint256)",
        "safeTransferFrom(address,address,uint256,bytes)",
        "safeTransferFrom(address,address,uint256,uint256,bytes)",
        "safeBatchTransferFrom(address,address,uint256[],uint256[],bytes)",
    ).map { signature -> AbiWords.selector(signature).toHex() to checkNotNull(AbiFunction.parse(signature)) }.toMap()

    /** The selectors read here, `0x` and 8 hex digits: a hand-written decoder's, which no descriptor describes. */
    val SELECTORS: Set<String> = FUNCTIONS.keys.map { "0x$it" }.toSet()

    /** What [data] does when it is exactly one of these functions' encoding; null otherwise. */
    fun of(data: ByteArray): TokenCall? {
        if (data.size < AbiWords.SELECTOR) return null
        val function = FUNCTIONS[data.copyOf(AbiWords.SELECTOR).toHex()] ?: return null
        val args = AbiDecoder.decode(function.params, data.copyOfRange(AbiWords.SELECTOR, data.size))
            ?.members?.map { it.second } ?: return null
        fun address(i: Int) = (args[i] as AbiValue.AddressValue).address
        fun uint(i: Int) = (args[i] as AbiValue.UintValue).value
        fun uints(i: Int) = (args[i] as AbiValue.ArrayValue).items.map { (it as AbiValue.UintValue).value }
        fun bytes(i: Int) = (args[i] as AbiValue.BytesValue).hex.removePrefix("0x").length / 2
        return when (function.name to function.params.size) {
            "transferFrom" to 3 -> TokenCall.TransferFrom(address(0), address(1), uint(2))
            "increaseAllowance" to 2 -> TokenCall.AllowanceIncrease(address(0), uint(1))
            "setApprovalForAll" to 2 -> TokenCall.ApprovalForAll(address(0), (args[1] as AbiValue.BoolValue).value)
            "safeTransferFrom" to 3 -> TokenCall.NftTransfer(address(0), address(1), uint(2), 0)
            "safeTransferFrom" to 4 -> TokenCall.NftTransfer(address(0), address(1), uint(2), bytes(3))
            "safeTransferFrom" to 5 -> TokenCall.MultiTokenTransfer(address(0), address(1), uint(2), uint(3))
            "safeBatchTransferFrom" to 5 -> {
                val ids = uints(2)
                val amounts = uints(3)
                // Arrays of unequal length revert on chain: not read as a transfer.
                if (ids.size != amounts.size) return null
                TokenCall.MultiTokenBatchTransfer(address(0), address(1), ids.zip(amounts))
            }
            else -> null
        }
    }
}
