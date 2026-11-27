package eth.serotine.ui.components

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.UInt256

/**
 * A past transaction's call in words, as Punicus reads it itself (docs/clear-signing.md, *Past transactions*): in
 * the past tense, naming its sender ([sender]) rather than "this account", since a third party may have sent it.
 * Amounts read [AMOUNT_MASK] while [hidden]. Null for a call Punicus does not read itself.
 */
internal fun pastMeaningLine(meaning: DappCallMeaning, sender: Address, hidden: Boolean): String? {
    val from = Eip55.format(sender)
    fun units(value: UInt256, token: Address) =
        "${if (hidden) AMOUNT_MASK else value.toDecimal()} units of the token at ${Eip55.format(token)}"
    return when (meaning) {
        DappCallMeaning.Payment -> "$from paid its value to the recipient."
        is DappCallMeaning.TokenTransfer ->
            "$from sent ${units(meaning.amount, meaning.token)} to ${Eip55.format(meaning.recipient)}."
        is DappCallMeaning.TokenApproval -> "An approval: $from let ${Eip55.format(meaning.spender)} take up to " +
            "${units(meaning.amount, meaning.token)} of its tokens."
        is DappCallMeaning.TokenCallOn ->
            TokenCallText(meaning.contract, meaning.call, null, sender, from, "", hidden).sentence
        is DappCallMeaning.SafeExecution -> "$from executed a transaction of the Safe ${Eip55.format(meaning.safe)}: " +
            "what the Safe ran is below."
        is DappCallMeaning.SafeExecutionNotDecoded -> "$from called execTransaction on ${Eip55.format(meaning.safe)}, " +
            "with data that is not exactly that function's: what the Safe ran is not read here."
        is DappCallMeaning.SafeApproveHash -> "$from approved, on chain, the transaction of the Safe " +
            "${Eip55.format(meaning.safe)} whose hash is 0x${meaning.hash.hex}."
        is DappCallMeaning.NotDecoded -> null
    }
}

/**
 * This item as a transaction that already ran shows it: the advice written for signing (sign only if…, does not
 * sign…, check before you sign) said of what ran instead, in its calls too.
 */
internal fun SafeTxActionItem.asPast(): SafeTxActionItem = copy(
    warning = warning
        ?.replace(SafeTxActionItem.PARTLY_NOT_DECODED, PAST_PARTLY_NOT_DECODED)
        ?.replace(PRE_SIGNATURE_WARNING, PAST_PRE_SIGNATURE)
        ?.replace(DELEGATE_CALL_WARNING, PAST_DELEGATE_CALL)
        ?.trim()?.ifEmpty { null },
    calls = calls.map { it.asPast() },
)

private const val PAST_PARTLY_NOT_DECODED = "Part of it is not decoded: a call inside it is shown raw, and Punicus " +
    "cannot tell what that part did."
private const val PAST_PRE_SIGNATURE = "It let CoW Protocol fill an order whose amounts are not in the transaction."
private const val PAST_DELEGATE_CALL = "A delegate call: the Safe ran this contract's code as itself, with its " +
    "storage, owners and funds."
