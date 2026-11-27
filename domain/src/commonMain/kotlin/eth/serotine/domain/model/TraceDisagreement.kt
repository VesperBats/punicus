package eth.serotine.domain.model

/**
 * Where a verified trace says otherwise than a call's description (docs/clear-signing.md, *Phase B*, decision
 * 2): warned about loudly, the call still signable.
 */
sealed interface TraceDisagreement {
    /** The account sends [token] (a token, wrapped currency or an NFT collection) the claims do not name. */
    data class TokenNotNamed(val token: Address) : TraceDisagreement

    /**
     * Some of [asset] ends up with [holder], which is neither the account, nor a recipient the description
     * shows, nor the zero address (a burn), nor, for an asset the account sends, a counterparty that pays
     * on toward the account or a recipient in the trace (docs/clear-signing.md, *The disagreements*).
     */
    data class OtherRecipient(val asset: TracedAsset, val holder: Address) : TraceDisagreement

    /**
     * The trace grants [right] where the claims grant none (no approval flag, A3, and no `approve`), or none over
     * its token, or none to its spender.
     */
    data class RightNotFlagged(val right: TracedRight) : TraceDisagreement
}

/**
 * What a call claims it does, which its verified trace is held against (docs/clear-signing.md, *What the trace
 * is held against*): the [tokens] it names (and, with [currency], the network's own), the [addresses] it shows
 * the user, and whether it [grantsRight]. From its description ([of]), or from a hand-written decoder's
 * meaning ([ofMeaning], [ofSafeAction]); a call neither describes has none (null), and is shown as not decoded.
 */
data class TraceClaims(
    val tokens: Set<Address>,
    val currency: Boolean,
    val addresses: Set<Address>,
    val grantsRight: Boolean,
) {
    companion object {
        /** A description's claims, its nested calls' included; null when it is not described. */
        fun of(description: ClearSignResult): TraceClaims? {
            val described = description as? ClearSignResult.ClearSigned ?: return null
            val tokens = mutableSetOf<Address>()
            val addresses = mutableSetOf<Address>()
            val currency = collect(described, tokens, addresses)
            return TraceClaims(tokens, currency, addresses, described.grantsApproval)
        }

        /** A dApp call's claims as its hand-written decoder reads it (sent to [to]); null when it is not decoded. */
        fun ofMeaning(meaning: DappCallMeaning, to: Address): TraceClaims? = when (meaning) {
            DappCallMeaning.Payment -> TraceClaims(emptySet(), currency = true, addresses = setOf(to), grantsRight = false)
            is DappCallMeaning.TokenTransfer -> TraceClaims(setOf(meaning.token), false, setOf(meaning.recipient), false)
            is DappCallMeaning.TokenApproval -> TraceClaims(setOf(meaning.token), false, setOf(meaning.spender), true)
            is DappCallMeaning.TokenCallOn -> meaning.call.claims(meaning.contract)
            // An Execute is traced as the Safe's own call, against ofSafeAction's claims; the outer call has none.
            is DappCallMeaning.SafeExecution, is DappCallMeaning.SafeExecutionNotDecoded,
            is DappCallMeaning.SafeApproveHash, is DappCallMeaning.NotDecoded -> null
        }

        /** A Safe transaction's claims as screen 17 decodes it, for [account] (the Safe); null when not decoded. */
        fun ofSafeAction(action: SafeTxAction, account: Address): TraceClaims? = when (action) {
            is SafeTxAction.NativeTransfer -> TraceClaims(emptySet(), true, setOf(action.to), false)
            is SafeTxAction.TokenTransfer -> TraceClaims(setOf(action.token), false, setOf(action.to), false)
            is SafeTxAction.TokenApproval -> TraceClaims(setOf(action.token), false, setOf(action.spender), true)
            is SafeTxAction.Swap -> TraceClaims(
                action.swap.route.tokens.toSet(),
                currency = action.swap.sellsEther || action.swap.buysEther,
                addresses = setOf(account),
                grantsRight = false,
            )
            // It moves nothing and grants nothing: whatever the trace finds is a disagreement.
            is SafeTxAction.SettingsChange, SafeTxAction.Rejection -> TraceClaims(emptySet(), false, emptySet(), false)
            is SafeTxAction.NftTransfer -> TraceClaims(setOf(action.contract), false, setOf(action.to), false)
            is SafeTxAction.TokenCallOn -> action.call.claims(action.contract)
            is SafeTxAction.CowPreSignature, is SafeTxAction.Batch,
            is SafeTxAction.ContractCall, is SafeTxAction.DelegateCall -> null
        }

        /** The tokens and the addresses a description names, its nested calls' included; true when it shows currency. */
        private fun collect(described: ClearSignResult.ClearSigned, named: MutableSet<Address>, shown: MutableSet<Address>): Boolean {
            var currency = false
            for (field in described.fields) when (field) {
                is ClearSignedField.Value -> {
                    field.token?.let(named::add)
                    field.recipient?.let(shown::add)
                    if (field.nativeCurrency) currency = true
                }
                is ClearSignedField.Nested ->
                    if ((field.result as? ClearSignResult.ClearSigned)?.let { collect(it, named, shown) } == true) currency = true
            }
            return currency
        }
    }
}

/**
 * Holds a [TraceReading] of [account]'s call against its [TraceClaims] (docs/clear-signing.md, *Phase B*, *The
 * disagreements*). A call with no claims, or that reverts, has none: there is nothing to hold it against, or
 * nothing it does stays.
 */
object TraceDisagreements {

    /**
     * Every disagreement, each once, in the order: tokens not named, other recipients, rights not granted.
     * [listed] are the built-in tokens: with the tokens the claims name, the currency and its wrapped token,
     * the assets whose moves are believed to pay something (a token anyone deploys can log any move).
     */
    fun check(
        account: Address,
        reading: TraceReading,
        claims: TraceClaims?,
        listed: Set<Address> = emptySet(),
        /** The contract the call is sent to, which the review names: what it is paid and keeps is no theft. */
        called: Address? = null,
    ): List<TraceDisagreement> {
        claims ?: return emptyList()
        val effects = reading.effects
        if (effects.revert != null) return emptyList()
        val named = claims.tokens
        // Claims that show no address say the account keeps what the call gives it.
        val recipients = claims.addresses.ifEmpty { setOf(account) } + listOfNotNull(called)
        val valued = named + listed + CURRENCY + listOfNotNull(reading.wrappedNative)

        // A burn is sent to no one: it counts only where nothing of value comes to the account or an address the
        // claims show, and the account pays nothing the claims name (a loan repaid burns its debt token).
        val returned = reading.allMoves.any { it.to in recipients + account && it.from != account && key(it.asset) in valued }
        val paysNamed = effects.moves.any { it.from == account && it.to != Address.ZERO && key(it.asset) in named + namedCurrency(claims) }
        val excused = returned || paysNamed
        val sent = effects.moves.filter { it.from == account && !(excused && it.to == Address.ZERO) }.mapNotNull { it.asset.contract }
        val notNamed = sent.distinct().filter { it !in named }.map { TraceDisagreement.TokenNotNamed(it) }

        val others = otherRecipients(account, reading, named, recipients, claims.currency, valued)

        // A right is claimed only where the claims grant one, over a token they name, to an address they show.
        val unflagged = effects.rights.filterNot { right ->
            claims.grantsRight && right.over() in named && right.spender in claims.addresses
        }.map { TraceDisagreement.RightNotFlagged(it) }
        return notNamed + others + unflagged
    }

    /**
     * Where the account's assets, and those the description names, end up, against where the description says.
     * The assets held: each the account sends or receives, each the description names, and the network's
     * currency where the account moves it or the description shows an amount of it. An end holder ([endHolders])
     * is fine when it is the account, a recipient or the zero address; for an asset the account sends, also
     * when it pays on: a move of anything leads from it, directly or through others, to the account or a
     * recipient (a pool that is paid and pays back). The currency and its wrapped token count as one here. An asset the account does not send has no such exception:
     * a pool keeps what it is paid, never what it pays out.
     */
    private fun otherRecipients(
        account: Address,
        reading: TraceReading,
        named: Set<Address>,
        recipients: Set<Address>,
        namesCurrency: Boolean,
        valued: Set<Address>,
    ): List<TraceDisagreement.OtherRecipient> {
        val mine = reading.effects.moves
        // The currency and its wrapped token are one to the account: it pays in either, wrapped or unwrapped on the way.
        val wrapped = reading.wrappedNative
        val paid = mine.filter { it.from == account }.map { key(it.asset) }.toSet()
        val sent = if (wrapped != null && (CURRENCY in paid || wrapped in paid)) paid + CURRENCY + wrapped else paid
        val held = mine.map { key(it.asset) }.toSet() + named + listOfNotNull(CURRENCY.takeIf { namesCurrency })
        // The currency and its wrapped token are one: held to where one ends up, the other is too.
        val considered = if (wrapped != null && (CURRENCY in held || wrapped in held)) held + CURRENCY + wrapped else held
        val ends = setOf(account, Address.ZERO) + recipients
        val paysOn = payers(reading.allMoves, recipients + account) { key(it.asset) in valued || it.from == Address.ZERO }
        return endHolders(reading.allMoves.filter { key(it.asset) in considered })
            // A token ending with its own contract is burnt there, as wrapped currency is when it is unwrapped.
            .filter { (asset, holder) -> holder !in ends && holder != asset.contract && !(key(asset) in sent && holder in paysOn) }
            .map { (asset, holder) -> TraceDisagreement.OtherRecipient(asset, holder) }
    }

    /**
     * Every address from which a chain of [moves] leads to one of [targets]. The last move, into a target, must
     * be [paying]: an asset of value, since any contract can log a junk token's move, or a mint, its issuer
     * paying in its own shares as a lending pool or a vault does for what it takes. Moves further up may be in
     * any asset: a route's middle token is often in no list.
     */
    private fun payers(moves: List<TracedMove>, targets: Set<Address>, paying: (TracedMove) -> Boolean): Set<Address> {
        fun payer(move: TracedMove) = if (move.from == Address.ZERO) move.asset.contract ?: move.from else move.from
        val reached = targets.toMutableSet()
        val queue = ArrayDeque<Address>()
        for (move in moves) if (move.to in targets && paying(move) && reached.add(payer(move))) queue.addLast(payer(move))
        val into = moves.groupBy({ it.to }, ::payer)
        while (queue.isNotEmpty()) {
            for (from in into[queue.removeFirst()].orEmpty()) if (reached.add(from)) queue.addLast(from)
        }
        return reached - targets
    }

    /** [CURRENCY] when the claims show an amount of the network's currency. */
    private fun namedCurrency(claims: TraceClaims): Set<Address> = if (claims.currency) setOf(CURRENCY) else emptySet()

    /** The token a right is over: Permit2's is the token it lets the spender take. */
    private fun TracedRight.over(): Address = if (this is TracedRight.Permit2) token else contract

    /** The contract an asset is told by: its issuer, or [CURRENCY] for the network's own. */
    private fun key(asset: TracedAsset): Address = asset.contract ?: CURRENCY

    /** Stands for the network's currency among contracts: no contract is at it ([Address.ZERO] is a burn). */
    private val CURRENCY = Address.of("0x" + "e".repeat(40))

    /**
     * Each asset with each address that ends up holding more of it than it started with, in the order first
     * credited: where the tokens end up, past those that only pass them on.
     */
    private fun endHolders(moves: List<TracedMove>): List<Pair<TracedAsset, Address>> {
        val credits = linkedMapOf<Pair<TracedAsset, Address>, UInt256>()
        val debits = mutableMapOf<Pair<TracedAsset, Address>, UInt256>()
        for (move in moves) {
            if (move.from == move.to) continue
            val asset = move.asset
            credits[asset to move.to] = (credits[asset to move.to] ?: UInt256.ZERO).plusOrNull(move.amount) ?: UInt256.MAX
            debits[asset to move.from] = (debits[asset to move.from] ?: UInt256.ZERO).plusOrNull(move.amount) ?: UInt256.MAX
        }
        return credits.filter { (key, credit) -> credit > (debits[key] ?: UInt256.ZERO) }.keys.toList()
    }
}

/** What a [TokenCall] on [contract] claims: the contract's tokens moved to, or approved for, the address it names. */
internal fun TokenCall.claims(contract: Address): TraceClaims = when (this) {
    is TokenCall.TransferFrom -> TraceClaims(setOf(contract), false, setOf(from, to), false)
    is TokenCall.AllowanceIncrease -> TraceClaims(setOf(contract), false, setOf(spender), true)
    is TokenCall.ApprovalForAll -> TraceClaims(setOf(contract), false, setOf(operator), approved)
    is TokenCall.NftTransfer -> TraceClaims(setOf(contract), false, setOf(from, to), false)
    is TokenCall.MultiTokenTransfer -> TraceClaims(setOf(contract), false, setOf(from, to), false)
    is TokenCall.MultiTokenBatchTransfer -> TraceClaims(setOf(contract), false, setOf(from, to), false)
}
