package eth.serotine.ui.activity

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.DelayCall
import eth.serotine.domain.model.DelayMemberChange
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PinnedFallbackHandlers
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderEnd
import eth.serotine.domain.repository.CowOrderRecord
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.QueuedTransaction
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.usecase.BuiltInCheck
import eth.serotine.domain.usecase.IndexedEvent
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.components.DecodingToken
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.FALLBACK_HANDLER_REMOVED
import eth.serotine.ui.components.GUARD_REMOVED_WARNING
import eth.serotine.ui.components.GUARD_SET_WARNING
import eth.serotine.ui.components.MODULE_DISABLED_WARNING
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.exactAmount
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.safeTxActionItem
import eth.serotine.ui.components.shortAddress
import eth.serotine.ui.components.shortDigits
import eth.serotine.ui.outbox.pastItsHour

/**
 * What the account at [address] is told of each transaction and event of its history, in the
 * words the Activity screen lists it with and its detail screen heads it with (docs/wallet-ux.md,
 * screens 25 and 26): one place, so the two never say it differently. [network] names its
 * currency, [formatTime] dates a transaction a peer took and an event's block. With [hideAmounts]
 * every amount it says reads [AMOUNT_MASK] beside its unit (docs/wallet-ux.md, *Hiding amounts*).
 * A Safe transaction approved or executed is said as what it does ("Send 40 USDC"), as its queue
 * row says it: its transfer in the units of a token the wallet knows ([decodingTokens]), and an
 * execution or approval on chain by the request this device holds of it ([requests], by the Safe
 * transaction's hash), or as "a Safe transaction" where it holds none.
 */
internal class ActivityRows(
    private val address: Address,
    private val network: Network,
    private val formatTime: (Long) -> String,
    private val hideAmounts: Boolean = false,
    private val decodingTokens: List<DecodingToken> = emptyList(),
    private val requests: Map<Digest, SigningRequest> = emptyMap(),
) {

    /**
     * A token's [event] as a row: what moved, to or from whom, in which block. Only a token the
     * wallet shows has its transfer said as one: an unlisted contract can log any transfer it likes,
     * between any addresses, so its row says that the contract logged one, names the contract and
     * not the other side, and is marked as unconfirmed. A token the user added whose symbol is a
     * built-in token's says it is another contract, as every screen does. One received from a
     * sender the user does not deal with ([Parties.familiar]) is cautioned: the sender may be a
     * look-alike of an address the user uses, planted for them to copy.
     */
    fun transferRow(
        event: TokenTransferEvent,
        parties: Parties,
        tokens: Map<Address, KnownToken>,
    ): ActivityRow {
        val names = parties.names
        val transfer = event.transfer
        if (transfer is TokenTransfer.Fungible && transfer.isEth) return ethRow(event, transfer, parties)
        val contract = shortAddress(Eip55.format(transfer.token))
        val listed = tokens[transfer.token]
        val known = listed?.takeIf { it.shown }
        val direction = when {
            transfer.from == address && transfer.to == address -> Direction.ITSELF
            transfer.to == address -> Direction.IN
            else -> Direction.OUT
        }
        val row = ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = "",
            detail = "",
            about = Eip55.format(transfer.token),
            time = timeOf(event),
            simpleTime = event.blockTimestamp?.let(formatTime),
            status = TOKEN_ON_CHAIN,
            state = ActivityState.DONE,
            opens = ActivityKey(event.transactionHash, event.logIndex),
        )
        if (known == null) {
            val title = when (direction) {
                Direction.IN -> "An unlisted token logged a transfer to it"
                Direction.OUT -> "An unlisted token logged a transfer from it"
                Direction.ITSELF -> "An unlisted token logged a transfer to itself"
            }
            // What would name it, for the reason it is not named.
            val status = when {
                listed == null -> UNLISTED
                listed.check is BuiltInCheck.Disagrees -> CONTRADICTED
                else -> HIDDEN
            }
            return row.copy(
                title = title,
                detail = "Contract $contract",
                status = status,
                state = ActivityState.UNKNOWN,
            )
        }
        val symbol = displaySymbol(known.token.symbol)
        val what = when (transfer) {
            is TokenTransfer.Fungible -> figure(transfer.amount, known.token.decimals, symbol)
            is TokenTransfer.Nft -> "$symbol NFT #${tokenId(transfer.tokenId)}"
        }
        val (title, other, detail) = when (direction) {
            Direction.ITSELF -> Triple("Moved $what to itself", transfer.token, "Token $contract")
            Direction.IN -> Triple("Received $what", transfer.from, "From ${named(transfer.from, names)}")
            Direction.OUT -> Triple("Sent $what", transfer.to, "To ${named(transfer.to, names)}")
        }
        // What a swap sent from here bought is its pool's, not a stranger's: of a built-in token only, its
        // address pinned and its contract not contradicting it, since an added one's contract could log
        // any sender.
        val builtIn = known.check != null && known.check !is BuiltInCheck.Disagrees
        val bought = builtIn && parties.boughtHere(event.transactionHash, transfer)
        return when {
            known.sharesBuiltInSymbol -> row.copy(
                title = title,
                detail = "$detail · token $contract",
                about = Eip55.format(other),
                status = "Not the built-in $symbol: another contract",
                state = ActivityState.WARNING,
            )
            direction == Direction.IN && !parties.familiar(transfer.from) && !bought -> row.copy(
                title = title,
                detail = detail,
                about = Eip55.format(other),
                status = UNFAMILIAR_SENDER,
                state = ActivityState.CAUTION,
            )
            else -> row.copy(title = title, detail = detail, about = Eip55.format(other))
        }
    }

    /**
     * The protocol's log of an ETH [transfer] (EIP-7708) as a row: ETH sent or received, whoever
     * the other side is, said as the chain's own word; cautioned as a token's is when received
     * from a sender the user does not deal with.
     */
    private fun ethRow(event: TokenTransferEvent, transfer: TokenTransfer.Fungible, parties: Parties): ActivityRow {
        val what = amount(transfer.amount, TransferAsset.Native)
        val incoming = transfer.to == address
        val other = if (incoming) transfer.from else transfer.to
        val cautioned = incoming && !parties.familiar(transfer.from) &&
            !parties.boughtHere(event.transactionHash, transfer)
        return ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = if (incoming) "Received $what" else "Sent $what",
            detail = (if (incoming) "From " else "To ") + named(other, parties.names),
            about = Eip55.format(other),
            time = timeOf(event),
            simpleTime = event.blockTimestamp?.let(formatTime),
            status = if (cautioned) UNFAMILIAR_SENDER else ethOnChain(network),
            state = if (cautioned) ActivityState.CAUTION else ActivityState.DONE,
            opens = ActivityKey(event.transactionHash, event.logIndex),
        )
    }

    /**
     * A verified row of the addr1 index ([indexed]) as a row of the Activity (owner ruling, 2026-11-21, #334), in the
     * words the log index's rows use, so that a transfer reads alike from either source; null for one that is not
     * listed: a transfer of nothing the account did not send, as the log index leaves one out ([movesNothing]). A
     * token's transfer is said as one only for a token the wallet shows; an unlisted contract's is its claim, naming
     * the contract alone. What it moved, or that the account is named in it ([rolesText]), and from whom: one the
     * account did not send, from someone the user does not deal with ([Parties.familiar]), is cautioned. A
     * transaction's value is said as moved only when its verified receipt says it succeeded
     * ([AddressIndexTransaction.succeeded]); reverted, it failed on chain, as a sent row says; not known (the engine
     * does not report it yet), the row says how the account is named in it, and the value is left to the detail. Its
     * status
     * says it was checked against the chain, and in which block; dated by that block alone, like an event the engine
     * did not date: the index carries no time.
     */
    fun indexRow(indexed: AddressIndexRow, parties: Parties, tokens: Map<Address, KnownToken>): ActivityRow? {
        val entry = indexed.entry
        val block = grouped(entry.block)
        val (status, simpleStatus) = if (indexed.final) {
            "Finalized in block $block · checked against the chain" to "Final"
        } else {
            "In block $block · checked against the chain, not final yet" to "Confirmed"
        }
        val base = ActivityRow(
            key = "index:${entry.block}:${entry.slot}",
            title = "",
            detail = "",
            about = Eip55.format(address),
            time = "Block $block",
            simpleTime = null,
            status = status,
            simpleStatus = simpleStatus,
            state = ActivityState.DONE,
            opens = ActivityKey(
                indexed.transaction?.hash.orEmpty(),
                index = IndexEntryKey(entry.block, entry.slot, entry.roles),
            ),
        )
        val tx = indexed.transaction ?: return base.copy(
            // No block number in the title: simple mode shows none, and the row's time names it.
            title = "Named by the block itself",
            detail = rolesText(entry.roles).replaceFirstChar { it.uppercase() },
        )
        val sentByIt = tx.from == address
        val transfer = tx.transfer
        if (transfer != null && transfer.movesNothing() && !sentByIt) return null
        if (transfer != null && !transfer.movesNothing()) {
            val listed = tokens[transfer.token]
            val known = listed?.takeIf { it.shown }
            val itself = transfer.from == address && transfer.to == address
            val incoming = transfer.to == address && !itself
            if (known == null) {
                return base.copy(
                    title = when {
                        itself -> "An unlisted token logged a transfer to itself"
                        incoming -> "An unlisted token logged a transfer to it"
                        else -> "An unlisted token logged a transfer from it"
                    },
                    detail = "Contract ${shortAddress(Eip55.format(transfer.token))}",
                    about = Eip55.format(transfer.token),
                    status = when {
                        listed == null -> UNLISTED
                        listed.check is BuiltInCheck.Disagrees -> CONTRADICTED
                        else -> HIDDEN
                    },
                    simpleStatus = null,
                    state = ActivityState.UNKNOWN,
                )
            }
            val symbol = displaySymbol(known.token.symbol)
            val what = when (transfer) {
                is TokenTransfer.Fungible -> figure(transfer.amount, known.token.decimals, symbol)
                is TokenTransfer.Nft -> "$symbol NFT #${tokenId(transfer.tokenId)}"
            }
            val other = if (incoming) transfer.from else transfer.to
            val row = when {
                // As the log index says it (transferRow, Direction.ITSELF).
                itself -> base.copy(
                    title = "Moved $what to itself",
                    detail = "Token ${shortAddress(Eip55.format(transfer.token))}",
                    about = Eip55.format(transfer.token),
                )
                else -> base.copy(
                    title = if (incoming) "Received $what" else "Sent $what",
                    detail = (if (incoming) "From " else "To ") + named(other, parties.names),
                    about = Eip55.format(other),
                )
            }
            return when {
                known.sharesBuiltInSymbol -> row.copy(
                    status = "Not the built-in $symbol: another contract",
                    simpleStatus = null,
                    state = ActivityState.WARNING,
                )
                incoming && !parties.familiar(transfer.from) -> row.cautioned()
                else -> row
            }
        }
        val value = tx.value.toUInt256OrNull()
        val other = if (sentByIt) tx.to else tx.from
        val counterpart = if (sentByIt) {
            tx.to?.let { "To ${named(it, parties.names)}" } ?: "It creates a contract"
        } else {
            "By ${named(tx.from, parties.names)}"
        }
        val row = when (tx.succeeded) {
            // The engine does not report the receipt's status yet: a reverted transaction keeps its value field and
            // moves nothing, so the row says only how the account is named in it. Its value is in the detail.
            null -> base.copy(title = "Named in a transaction · ${rolesText(entry.roles)}", detail = counterpart)
            // As a sent row says a revert: its fee paid, nothing else done.
            false -> base.copy(
                title = "Failed on chain",
                detail = counterpart,
                status = "Reverted in block $block · checked against the chain",
                simpleStatus = "Failed",
                state = ActivityState.FAILED,
            )
            true -> succeededRow(base, tx, value, sentByIt, parties, entry.roles)
        }.let { r -> other?.let { r.copy(about = Eip55.format(it)) } ?: r }
        // Anyone can name the account in a transaction of their own, from a look-alike address planted for the user
        // to copy: one it did not send, from someone the user does not deal with, is cautioned.
        return if (!sentByIt && !parties.familiar(tx.from)) row.cautioned() else row
    }

    /** A transaction whose verified receipt says it succeeded ([tx]), as its row says what it did. */
    private fun succeededRow(
        base: ActivityRow,
        tx: AddressIndexTransaction,
        value: UInt256?,
        sentByIt: Boolean,
        parties: Parties,
        roles: Set<AddressIndexRole>,
    ): ActivityRow = when {
        sentByIt && tx.to == null -> base.copy(title = "Created a contract", detail = rolesText(roles))
        sentByIt -> base.copy(
            title = if (value == null || value.isZero) {
                "Sent a transaction"
            } else {
                "Sent ${amount(value, TransferAsset.Native)}"
            },
            detail = "To ${named(checkNotNull(tx.to), parties.names)}",
        )
        tx.to == address && value != null && !value.isZero -> base.copy(
            title = "Received ${amount(value, TransferAsset.Native)}",
            detail = "From ${named(tx.from, parties.names)}",
        )
        else -> base.copy(
            title = "Named in a transaction",
            detail = "By ${named(tx.from, parties.names)} · ${rolesText(roles)}",
        )
    }

    /** This row cautioned about its sender, the address poisoning protection's words (screen 25). */
    private fun ActivityRow.cautioned(): ActivityRow =
        copy(status = UNFAMILIAR_SENDER, simpleStatus = null, state = ActivityState.CAUTION)

    /** Which way a token moved, as the account sees it. */
    private enum class Direction { IN, OUT, ITSELF }

    /** An NFT's id as screens show it: whole when short, its ends when long ([shortDigits]). */
    fun tokenId(id: UInt256): String = shortDigits(id)

    /** [other] as screens name it: the wallet's name for it and its short address, or the short address. */
    fun named(other: Address, names: Map<Address, String>): String {
        val short = shortAddress(Eip55.format(other))
        return names[other]?.let { "$it · $short" } ?: short
    }

    /**
     * A Safe's [event] as a row; ETH received from a sender the user does not deal with is cautioned.
     * An execution or approval says what the Safe transaction does where this device holds its
     * request ("Executed: Send 40 USDC"), and that it is a Safe transaction where it does not.
     */
    fun eventRow(event: SafeChainEvent, parties: Parties): ActivityRow {
        fun named(other: Address): String = named(other, parties.names)
        val (title, other, detail) = when (val what = event.event) {
            is SafeEvent.SetUp ->
                Triple("Set up", address, "${what.threshold} of ${owners(what.owners.size.toLong())} must sign")
            is SafeEvent.Executed -> Triple(
                when {
                    what.succeeded -> does(what.safeTxHash)?.let { "Executed: $it" } ?: "Executed a Safe transaction"
                    else -> does(what.safeTxHash)?.let { "Failed: $it" } ?: "A Safe transaction failed"
                },
                address,
                "Safe transaction ${shortHash(what.safeTxHash)}",
            )
            is SafeEvent.Received -> Triple(
                "Received ${amount(what.amount, TransferAsset.Native)}",
                what.from,
                "From ${named(what.from)}",
            )
            is SafeEvent.Approved -> Triple(
                does(what.safeTxHash)?.let { "Approved: $it" } ?: "Approved a Safe transaction",
                what.owner,
                "By ${named(what.owner)}",
            )
            is SafeEvent.OwnerAdded -> Triple("Owner added", what.owner, named(what.owner))
            is SafeEvent.OwnerRemoved -> Triple("Owner removed", what.owner, named(what.owner))
            is SafeEvent.ThresholdChanged ->
                Triple("Threshold changed", address, "${owners(what.threshold)} must sign now")
            is SafeEvent.ModuleEnabled -> Triple("Module enabled", what.module, named(what.module))
            is SafeEvent.ModuleDisabled -> Triple("Module disabled", what.module, named(what.module))
            is SafeEvent.GuardChanged -> if (what.guard == Address.ZERO) {
                Triple("Guard removed", address, "No guard checks its transactions now")
            } else {
                Triple("Guard set", what.guard, named(what.guard))
            }
            is SafeEvent.FallbackHandlerChanged -> if (what.handler == Address.ZERO) {
                Triple("Fallback handler removed", address, "No fallback handler now")
            } else {
                Triple("Fallback handler set", what.handler, named(what.handler))
            }
            is SafeEvent.ModuleExecuted -> Triple(
                if (what.succeeded) "A module ran a transaction" else "A module's transaction failed",
                what.module,
                "Module ${named(what.module)}",
            )
            is SafeEvent.MessageSigned -> Triple("Signed a message", address, "Message ${shortHash(what.messageHash)}")
        }
        val received = event.event as? SafeEvent.Received
        val (status, state) = if (received != null && !parties.familiar(received.from)) {
            UNFAMILIAR_SENDER to ActivityState.CAUTION
        } else {
            statusOf(event.event)
        }
        return ActivityRow(
            key = "${event.transactionHash}:${event.logIndex}",
            title = title,
            detail = detail,
            about = Eip55.format(other),
            time = timeOf(event),
            simpleTime = event.blockTimestamp?.let(formatTime),
            status = status,
            state = state,
            opens = ActivityKey(event.transactionHash, event.logIndex),
        )
    }

    /**
     * When [event] happened, as its row says it: when its block was produced, where the engine
     * reported it from the block's verified header; the block alone where it did not.
     */
    fun timeOf(event: IndexedEvent): String =
        event.blockTimestamp?.let(formatTime) ?: "Block ${grouped(event.blockNumber)}"

    /**
     * What the Safe transaction [safeTxHash] does, where this device holds its request; null where
     * it holds none, or the hash is not one.
     */
    private fun does(safeTxHash: String): String? = Digest.parse(safeTxHash)?.let(requests::get)?.let { does(it) }

    /**
     * What [request]'s Safe transaction does, as its queue row says it ("Send 40 USDC", "Add an
     * owner"), its amounts masked while they are hidden.
     */
    fun does(request: SigningRequest): String =
        safeTxActionItem(SafeTxAction.of(request.safe, request.tx), request.nonce, decodingTokens, network, hideAmounts)
            .title

    /**
     * How an event's row is marked: failed for a call that reverted; a warning for what can move
     * the Safe's funds without its owners (CLAUDE.md, *Trust*): a module enabled, or a fallback
     * handler other than the pinned one; and for every other change that weakens the Safe, with the
     * warning its Safe transaction is signed with (docs/wallet-ux.md, 17): a module disabled, a
     * guard set or removed, a fallback handler removed.
     */
    fun statusOf(event: SafeEvent): Pair<String, ActivityState> = when {
        (event as? SafeEvent.Executed)?.succeeded == false ||
            (event as? SafeEvent.ModuleExecuted)?.succeeded == false -> FAILED_ON_CHAIN to ActivityState.FAILED
        event is SafeEvent.ModuleEnabled -> MODULE_WARNING to ActivityState.WARNING
        event is SafeEvent.ModuleDisabled -> MODULE_DISABLED_WARNING to ActivityState.WARNING
        event is SafeEvent.GuardChanged && event.guard == Address.ZERO -> GUARD_REMOVED_WARNING to ActivityState.WARNING
        event is SafeEvent.GuardChanged -> GUARD_SET_WARNING to ActivityState.WARNING
        // Removing it is signed here, with a warning (owner ruling, 2026-10-20, #106): the row warns alike.
        event is SafeEvent.FallbackHandlerChanged && event.handler == Address.ZERO ->
            FALLBACK_HANDLER_REMOVED to ActivityState.WARNING
        event is SafeEvent.FallbackHandlerChanged && !PinnedFallbackHandlers.allowed(network, event.handler) ->
            HANDLER_WARNING to ActivityState.WARNING
        else -> ON_CHAIN to ActivityState.DONE
    }

    /** [count] owners, as a sentence says it: "1 owner", "2 owners". */
    fun owners(count: Long): String = if (count == 1L) "1 owner" else "$count owners"

    /** A 32-byte hash as screens shorten it, as they do an address: "0xab12…cd34". */
    fun shortHash(hash: String): String = shortAddress(hash)


    /** A transaction sent from this device ([entry]) as a row; [names] are the wallet's names for addresses. */
    fun row(entry: HistoryEntry, names: Map<Address, String>): ActivityRow {
        val transfer = entry.transfer
        // Its other side: whom a transfer went to, the Safe a call was for, or, seen from that Safe,
        // the account that sent it.
        val (title, other, detail) = when (val action = transfer.action) {
            is SentAction.Transfer -> Triple("Sent ${amount(action.amount, action.asset)}", action.to, "To")
            // A zero approval takes the allowance back, before the whole amount is approved. The spender
            // is Uniswap's router or CoW's vault relayer, which the record does not tell apart.
            is SentAction.TokenApproval -> if (action.amount.isZero) {
                Triple("Allowance reset for a swap", action.spender, "Spender")
            } else {
                val approved = amount(action.amount, TransferAsset.Erc20Token(action.token))
                Triple("Approved $approved", action.spender, "Spender")
            }
            is SentAction.Swap -> {
                val (symbol, decimals) = shown(action.buy)
                val title = "Swapped ${amount(action.amountIn, action.sell)} for at least " +
                    figure(action.minimumOut, decimals, symbol)
                Triple(title, action.router, "On Uniswap ·")
            }
            is SentAction.Wrap -> {
                val weth = TransferAsset.Erc20Token(action.weth)
                val title = "Wrapped ${amount(action.amount, TransferAsset.Native)} into ${shown(weth).first}"
                Triple(title, action.weth.address, "Through WETH9 ·")
            }
            is SentAction.Unwrap -> {
                val weth = TransferAsset.Erc20Token(action.weth)
                val title = "Unwrapped ${amount(action.amount, weth)} into ${network.nativeSymbol}"
                Triple(title, action.weth.address, "Through WETH9 ·")
            }
            is SentAction.SafeApproval -> safeCall("Approved", transfer.account, action.request)
            is SentAction.SafeExecution -> safeCall("Executed", transfer.account, action.request)
            is SentAction.SafeDeployment -> if (transfer.account == address) {
                Triple("Deployed a Safe", action.safe, "Safe")
            } else {
                Triple("Deployed", transfer.account, "Paid by")
            }
            is SentAction.CowCancellation -> Triple("Cancelled a CoW order on chain", action.settlement, "On CoW ·")
            // The dApp's name is its own claim: said as such.
            is SentAction.DappCall -> Triple("Sent for a dApp stating it is “${action.dapp}”", action.to, "To")
            // A Gnosis Pay account's transfer through its Delay: queued first, executed after the cooldown.
            is SentAction.DelayQueue -> delayCall("Queued", action.call)
            is SentAction.DelayExecution -> delayCall("Executed", action.call)
            // Moves nothing: the Delay's next entry past every expired one.
            is SentAction.DelaySkip -> if (transfer.account == address) {
                Triple("Skipped expired entries of a Delay", action.safe, "Of")
            } else {
                Triple("Skipped expired entries of the Delay", transfer.account, "Paid by")
            }
        }
        val (status, state) = statusOf(entry)
        return ActivityRow(
            key = transfer.hash,
            opens = ActivityKey(transfer.hash),
            title = title,
            detail = "$detail ${named(other, names)}",
            about = Eip55.format(other),
            time = formatTime(transfer.sentAt),
            status = status,
            state = state,
            simpleStatus = simpleStatusOf(entry),
        )
    }

    /**
     * A transaction queued in lenient mode ([queued]: a transfer, or a Safe's approval on chain or
     * execution) as a row, listed before every other one (docs/lenient-mode.md, *Where it shows*):
     * what it sends, to whom (the Safe, for a Safe's), when it was signed, and
     * whether it waits to be sent or needs a signature again. A tap opens its status, not 26.
     */
    fun queuedRow(queued: QueuedTransaction, names: Map<Address, String>, now: Long): ActivityRow {
        val (title, other) = when (val action = queued.action) {
            is SentAction.Transfer -> "Send ${amount(action.amount, action.asset)}" to action.to
            // A Safe's approval or execution, queued while syncing: the Safe it is of.
            is SentAction.SafeApproval -> "Approve on chain: Safe nonce ${action.request.nonce}" to action.request.safe
            is SentAction.SafeExecution -> "Execute: Safe nonce ${action.request.nonce}" to action.request.safe
            // Nothing else is queued: any other is said as a transaction to its contract.
            else -> "A transaction waiting to be sent" to queued.transaction.to
        }
        val (status, state) = when {
            queued.conflict != null -> "Needs your signature again" to ActivityState.FAILED
            // Past its hour it is never sent by itself: it waits for the user's answer on its status.
            !queued.sending && queued.pastItsHour(now) -> "Waiting for your answer" to ActivityState.WAITING
            else -> "Waiting to send" to ActivityState.WAITING
        }
        return ActivityRow(
            key = "queued:${queued.id}",
            opens = ActivityKey(queued.id, queued = true),
            title = title,
            detail = "To ${named(other, names)}",
            about = Eip55.format(other),
            time = "Signed ${formatTime(queued.signedAt)}",
            status = status,
            state = state,
        )
    }

    /** [units] of [asset] as screens say it: "0.25 ETH". */
    fun amount(units: UInt256, asset: TransferAsset): String {
        val (symbol, decimals) = shown(asset)
        return figure(units, decimals, symbol)
    }

    /** [units] at [decimals] in [symbol]: "0.25 ETH", or "•••• ETH" while amounts are hidden. */
    fun figure(units: UInt256, decimals: Int, symbol: String): String =
        if (hideAmounts) "$AMOUNT_MASK $symbol" else exactAmount(units, decimals, symbol)

    /**
     * A CoW order signed here as a row: what it sells and buys, and where it stands as the chain
     * proves it (docs/swaps.md, *Proving an order's end*), dated when it was signed. A tap opens the
     * order's status.
     */
    fun orderRow(order: CowOrderRecord): ActivityRow {
        val sells = amount(order.order.sellAmount, order.sell)
        val simple = when (val end = order.end) {
            is CowOrderEnd.Filled -> "Filled"
            is CowOrderEnd.Unfilled -> if (end.cancelled) "Cancelled" else "Not filled: it expired"
            else -> null
        }
        val (title, status, state) = when (val end = order.end) {
            null -> Triple(
                "CoW order: $sells for at least ${amount(order.order.buyAmount, order.buy)}",
                "May fill until ${formatTime(order.order.validTo)}",
                ActivityState.WAITING,
            )
            is CowOrderEnd.Filled -> Triple(
                "Swapped $sells for ${amount(end.trade.buyAmount, order.buy)} on CoW",
                "Filled in block ${grouped(end.block)}",
                ActivityState.DONE,
            )
            is CowOrderEnd.Unfilled -> {
                val block = grouped(end.endBlock)
                if (end.cancelled) {
                    Triple("CoW order cancelled: $sells", "Cancelled in block $block", ActivityState.DONE)
                } else {
                    Triple("CoW order not filled: $sells", "Not filled by block $block", ActivityState.DONE)
                }
            }
            CowOrderEnd.NotProvable -> Triple("CoW order: $sells", "Its end cannot be proven", ActivityState.UNKNOWN)
        }
        return ActivityRow(
            key = order.uid.hex,
            title = title,
            detail = "On CoW · ${shortAddress(Eip55.format(order.settlement))}",
            about = Eip55.format(order.settlement),
            time = formatTime(order.signedAt),
            status = status,
            state = state,
            opens = ActivityKey(order.uid.hex, cowOrder = true),
            simpleStatus = simple,
        )
    }

    /** [asset]'s symbol as screens show it, and its decimals. */
    fun shown(asset: TransferAsset): Pair<String, Int> = when (asset) {
        TransferAsset.Native -> network.nativeSymbol to NATIVE_DECIMALS
        is TransferAsset.Erc20Token -> displaySymbol(asset.token.symbol) to asset.token.decimals
    }

    /**
     * An approval or execution ([verb]) of [request]'s Safe transaction, sent by [sender]: its title,
     * with what the transaction does, and its other side as the account at [address] sees it, the
     * Safe or the sender.
     */
    private fun safeCall(verb: String, sender: Address, request: SigningRequest): Triple<String, Address, String> =
        if (sender == address) {
            Triple("$verb: ${does(request)}", request.safe, "Safe nonce ${request.nonce} ·")
        } else {
            Triple("$verb transaction ${request.nonce}: ${does(request)}", sender, "By")
        }

    /**
     * A Gnosis Pay account's [call] through its Delay, queued or executed ([verb]): a transfer with its
     * amount and recipient, or a member of the Delay added or removed, with that member.
     */
    private fun delayCall(verb: String, call: DelayCall): Triple<String, Address, String> = when (call) {
        is DelayTransfer -> Triple("$verb ${amount(call.amount, call.asset)} through the Delay", call.recipient, "To")
        is DelayMemberChange -> when (call.kind) {
            DelayMemberChange.Kind.ADD -> Triple("$verb: add a member of the Delay", call.member, "Member")
            DelayMemberChange.Kind.REMOVE -> Triple("$verb: remove a member of the Delay", call.member, "Member")
        }
    }

    /** What simple mode says instead of a sent transaction's block: null where [statusOf] names none. */
    private fun simpleStatusOf(entry: HistoryEntry): String? = when (val outcome = entry.outcome) {
        is SentOutcome.Finalized -> if (outcome.succeeded) "Final" else "Failed: its fee was paid, nothing else done"
        is SentOutcome.Included -> if (outcome.succeeded) "Confirmed" else "Failed: its fee was paid, nothing else done"
        else -> null
    }

    /** How a sent transaction's row is marked: how it ended as this device saw it. */
    fun statusOf(entry: HistoryEntry): Pair<String, ActivityState> = when (val outcome = entry.outcome) {
        is SentOutcome.Finalized -> if (outcome.succeeded) {
            "Finalized in block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        is SentOutcome.Included -> if (outcome.succeeded) {
            "In block ${grouped(outcome.blockNumber)}" to ActivityState.DONE
        } else {
            "Reverted in block ${grouped(outcome.blockNumber)}" to ActivityState.FAILED
        }
        SentOutcome.NonceUsed ->
            "Mined while the app was not watching, unless another transaction took its nonce" to
                ActivityState.UNKNOWN
        SentOutcome.NotSeen -> if (entry.transfer.maybeSent) {
            "No peer confirmed its broadcast" to ActivityState.UNCONFIRMED
        } else {
            "No block seen to include it yet" to ActivityState.WAITING
        }
    }

    companion object {
        /**
         * The tokens a Safe transaction's transfer is said in the units of, from the tokens known
         * ([tokens], by address; null until they are read): every one, hidden ones too, but no
         * built-in entry its contract contradicts, as the queue decodes them.
         */
        fun decodingTokens(tokens: Map<Address, KnownToken>?): List<DecodingToken> = tokens?.values.orEmpty()
            .filter { it.check !is BuiltInCheck.Disagrees }
            .map { DecodingToken(it.token, it.sharesBuiltInSymbol) }

        /** The Safe transactions the [entries] approve or execute, by hash: what an event on chain is decoded by. */
        fun requestsIn(entries: List<HistoryEntry>): Map<Digest, SigningRequest> = entries.mapNotNull {
            when (val action = it.transfer.action) {
                is SentAction.SafeApproval -> action.request
                is SentAction.SafeExecution -> action.request
                else -> null
            }
        }.associateBy { it.hash }

        private const val NATIVE_DECIMALS = 18
        private const val ON_CHAIN = "On chain, from the Safe's verified logs"
        private const val TOKEN_ON_CHAIN = "On chain, from the token's verified logs"

        /** The status of the network's own log of a transfer of its currency (EIP-7708). */
        private fun ethOnChain(network: Network) =
            "On chain, from the network's own verified log of ${network.nativeSymbol} transfers"
        private const val UNLISTED =
            "The claim of a contract the wallet does not list: open it to add it as a token and see what moved"
        private const val HIDDEN =
            "The claim of a token you hid: show it again in Settings → Tokens to see what moved"
        private const val CONTRADICTED =
            "The claim of a built-in token whose contract says otherwise: what moved is not shown"
        private const val FAILED_ON_CHAIN = "Failed on chain: its call reverted"
        private const val MODULE_WARNING = "A module can move this Safe's funds without its owners"
        private const val UNFAMILIAR_SENDER =
            "From an address you have not sent to: compare every character before you copy it"
        private const val HANDLER_WARNING = "Not the pinned fallback handler: it answers signature checks for this Safe"
    }
}
