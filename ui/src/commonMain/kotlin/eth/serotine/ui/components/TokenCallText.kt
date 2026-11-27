package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.TokenCall
import eth.serotine.domain.model.UInt256

/**
 * A [TokenCall] on [contract] in words, for D6 and screen 17: [title]; [sentence], what it does; [fields], each value
 * in full; [addresses], each address it names, the counterparty (recipient, spender, operator) first, the contract
 * last; [warnings], what to know first. [token] is the contract as a token the wallet knows, which names an amount in
 * its units, warned about when it only takes a built-in token's symbol; [sender] is the account that makes the
 * call, named [holder] ("this account", "the Safe"); [approvalLine] is the screen's own approval line, so that an
 * approval is flagged in that screen's words; with [hidden], every amount reads [AMOUNT_MASK].
 */
internal class TokenCallText(
    contract: Address,
    call: TokenCall,
    token: DecodingToken?,
    sender: Address,
    holder: String,
    approvalLine: String,
    hidden: Boolean = false,
) {
    val title: String
    val sentence: String
    val fields: List<Pair<String, String>>
    val addresses: List<ActionAddress>
    val warnings: List<String>

    init {
        val at = Eip55.format(contract)
        val symbol = token?.let { displaySymbol(it.token.symbol) }
        val named = symbol?.let { "$it · $at" } ?: at
        val shared = token?.takeIf { it.sharesBuiltInSymbol }?.let { "Not the built-in $symbol: another contract." }
        fun figure(value: UInt256, decimals: Int? = null) =
            if (hidden) AMOUNT_MASK else decimals?.let { value.formatUnits(it) } ?: grouped(value)
        fun amount(value: UInt256) = token?.let { "${figure(value, it.token.decimals)} $symbol" }
        fun address(label: String, value: Address) = ActionAddress(label, Eip55.format(value))
        when (call) {
            is TokenCall.TransferFrom -> {
                // ERC-20 and ERC-721 share the function: a token the wallet knows says which.
                val what = amount(call.value) ?: "${figure(call.value)} (an amount of a token, or NFT #${figure(call.value)})"
                if (call.from == sender) {
                    // The holder's own tokens: a plain send, whatever approval there is.
                    title = "Send ${amount(call.value) ?: "a token or an NFT"}"
                    sentence = "Sends $what of the contract at $at, $holder's own, to ${Eip55.format(call.to)} " +
                        "(transferFrom)."
                } else {
                    title = "Move ${amount(call.value) ?: "a token or an NFT"} from another address"
                    sentence = "Moves $what of the contract at $at from ${Eip55.format(call.from)} to " +
                        "${Eip55.format(call.to)} (transferFrom), under an approval $holder was given."
                }
                fields = listOf("Contract" to named, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to), "Value" to what)
                addresses = listOf(address("To", call.to), address("From", call.from), address("Contract", contract))
                warnings = listOfNotNull(shared)
            }
            is TokenCall.AllowanceIncrease -> {
                val what = amount(call.added) ?: "${figure(call.added)} units of the token at $at"
                title = "Allow a contract to take $what more"
                sentence = "An approval: lets ${Eip55.format(call.spender)} take up to $what more of $holder's " +
                    "tokens (increaseAllowance). It moves nothing itself."
                fields = listOf("Token" to named, "Spender" to Eip55.format(call.spender), "Added" to what)
                addresses = listOf(address("Spender", call.spender), address("Token contract", contract))
                warnings = listOfNotNull(approvalLine, shared)
            }
            is TokenCall.ApprovalForAll -> if (call.approved) {
                title = "Allow an operator to take every NFT of this collection"
                sentence = "Lets ${Eip55.format(call.operator)} take every NFT of the collection at $at that $holder " +
                    "holds, now and later, without asking again (setApprovalForAll)."
                fields = listOf("Collection" to at, "Operator" to Eip55.format(call.operator), "Approved" to "Yes, for all")
                addresses = listOf(address("Operator", call.operator), address("Collection contract", contract))
                warnings = listOf(
                    approvalLine,
                    "This hands over the whole collection: the operator may take every NFT of it that $holder " +
                        "holds, now and in future. Phishing sites ask for exactly this. Approve only a marketplace you " +
                        "know.",
                )
            } else {
                title = "Withdraw an operator's approval for this collection"
                sentence = "Withdraws ${Eip55.format(call.operator)}'s approval for every NFT of the collection at $at " +
                    "(setApprovalForAll, false)."
                fields = listOf("Collection" to at, "Operator" to Eip55.format(call.operator), "Approved" to "No")
                addresses = listOf(address("Operator", call.operator), address("Collection contract", contract))
                warnings = emptyList()
            }
            is TokenCall.NftTransfer -> {
                val id = "#${call.tokenId.toDecimal()}"
                title = "Send NFT $id"
                sentence = "Sends NFT $id of the collection at $at from ${Eip55.format(call.from)} to " +
                    "${Eip55.format(call.to)} (safeTransferFrom)" +
                    (if (call.dataSize > 0) ", with ${call.dataSize} bytes of data for the recipient." else ".")
                fields = listOf("Collection" to at, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to), "NFT" to id)
                addresses = listOf(address("To", call.to), address("From", call.from), address("Collection contract", contract))
                warnings = emptyList()
            }
            is TokenCall.MultiTokenTransfer -> {
                title = "Send ${figure(call.amount)} of token #${call.id.toDecimal()}"
                sentence = "Sends ${figure(call.amount)} of token #${call.id.toDecimal()} of the ERC-1155 contract at " +
                    "$at from ${Eip55.format(call.from)} to ${Eip55.format(call.to)}."
                fields = listOf(
                    "Contract" to at, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to),
                    "Token" to "#${call.id.toDecimal()}", "Amount" to figure(call.amount),
                )
                addresses = listOf(address("To", call.to), address("From", call.from), address("Contract", contract))
                warnings = emptyList()
            }
            is TokenCall.MultiTokenBatchTransfer -> {
                val items = call.items.joinToString(", ") { (id, amount) -> "${figure(amount)} of #${id.toDecimal()}" }
                title = "Send ${call.items.size} kinds of token"
                sentence = "Sends $items of the ERC-1155 contract at $at from ${Eip55.format(call.from)} to " +
                    "${Eip55.format(call.to)}."
                fields = listOf("Contract" to at, "From" to Eip55.format(call.from), "To" to Eip55.format(call.to), "Tokens" to items)
                addresses = listOf(address("To", call.to), address("From", call.from), address("Contract", contract))
                warnings = emptyList()
            }
        }
    }

    private companion object {
        fun grouped(value: UInt256): String = value.toDecimal().reversed().chunked(3).joinToString(",").reversed()
    }
}
