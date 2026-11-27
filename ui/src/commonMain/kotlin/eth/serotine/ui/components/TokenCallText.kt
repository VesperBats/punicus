package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenCall
import eth.serotine.domain.model.UInt256

/**
 * A [TokenCall] on [contract] in words, for D6 and screen 17: [sentence], what it does; [fields], each value in
 * full; [warning], what to know first (an approval of a whole collection), or null. [token] is the contract as a
 * built-in ERC-20 token, which names an amount in its units; [holder] is whose tokens they are ("this account",
 * "the Safe").
 */
internal class TokenCallText(contract: Address, call: TokenCall, token: Token?, holder: String) {

    val sentence: String
    val title: String
    val fields: List<Pair<String, String>>
    val warning: String?

    init {
        val at = Eip55.format(contract)
        val named = token?.let { "${it.symbol} · $at" } ?: at
        fun amount(value: UInt256) = token?.let { "${value.formatUnits(it.decimals)} ${it.symbol}" }
        when (call) {
            is TokenCall.TransferFrom -> {
                // ERC-20 and ERC-721 share the function: a built-in token says which.
                val what = amount(call.value) ?: "${grouped(call.value)} (an amount of a token, or NFT #${call.value.toDecimal()})"
                title = "Move ${amount(call.value) ?: "tokens"} from another address"
                sentence = "Moves $what of the contract at $at from ${Eip55.format(call.from)} to ${Eip55.format(call.to)} " +
                    "(transferFrom), under an approval the sender was given."
                fields = listOf("Contract" to named, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to), "Value" to what)
                warning = null
            }
            is TokenCall.AllowanceIncrease -> {
                val what = amount(call.added) ?: "${grouped(call.added)} units of the token at $at"
                title = "Allow a contract to take $what more"
                sentence = "An approval: lets ${Eip55.format(call.spender)} take up to $what more of $holder's tokens " +
                    "(increaseAllowance). It moves nothing itself."
                fields = listOf("Token" to named, "Spender" to Eip55.format(call.spender), "Added" to what)
                warning = APPROVAL_FIRST
            }
            is TokenCall.ApprovalForAll -> if (call.approved) {
                title = "Allow an operator to take every NFT of this collection"
                sentence = "Lets ${Eip55.format(call.operator)} take every NFT of the collection at $at that $holder " +
                    "holds, now and later, without asking again (setApprovalForAll)."
                fields = listOf("Collection" to at, "Operator" to Eip55.format(call.operator), "Approved" to "Yes, for all")
                warning = "This hands over the whole collection: the operator may take every NFT of it that " +
                    "$holder holds, now and in future. Phishing sites ask for exactly this. Approve only a marketplace " +
                    "you know."
            } else {
                title = "Withdraw an operator's approval for this collection"
                sentence = "Withdraws ${Eip55.format(call.operator)}'s approval for every NFT of the collection at $at " +
                    "(setApprovalForAll, false)."
                fields = listOf("Collection" to at, "Operator" to Eip55.format(call.operator), "Approved" to "No")
                warning = null
            }
            is TokenCall.NftTransfer -> {
                title = "Send NFT #${call.tokenId.toDecimal()}"
                sentence = "Sends NFT #${call.tokenId.toDecimal()} of the collection at $at from " +
                    "${Eip55.format(call.from)} to ${Eip55.format(call.to)} (safeTransferFrom)" +
                    (if (call.dataSize > 0) ", with ${call.dataSize} bytes of data for the recipient." else ".")
                fields = listOf(
                    "Collection" to at, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to),
                    "NFT" to "#${call.tokenId.toDecimal()}",
                )
                warning = null
            }
            is TokenCall.MultiTokenTransfer -> {
                title = "Send ${grouped(call.amount)} of token #${call.id.toDecimal()}"
                sentence = "Sends ${grouped(call.amount)} of token #${call.id.toDecimal()} of the ERC-1155 contract at " +
                    "$at from ${Eip55.format(call.from)} to ${Eip55.format(call.to)}."
                fields = listOf(
                    "Contract" to at, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to),
                    "Token" to "#${call.id.toDecimal()}", "Amount" to grouped(call.amount),
                )
                warning = null
            }
            is TokenCall.MultiTokenBatchTransfer -> {
                val items = call.items.joinToString(", ") { (id, amount) -> "${grouped(amount)} of #${id.toDecimal()}" }
                title = "Send ${call.items.size} kinds of token"
                sentence = "Sends $items of the ERC-1155 contract at $at from ${Eip55.format(call.from)} to " +
                    "${Eip55.format(call.to)}."
                fields = listOf(
                    "Contract" to at, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to), "Tokens" to items,
                )
                warning = null
            }
        }
    }

    private companion object {
        const val APPROVAL_FIRST = "It grants an approval: it moves nothing on its own, but the spender may then take " +
            "the tokens without asking again."

        fun grouped(value: UInt256): String = value.toDecimal().reversed().chunked(3).joinToString(",").reversed()
    }
}
