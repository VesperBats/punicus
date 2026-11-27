package eth.serotine.ui.activitydetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.DelayCall
import eth.serotine.domain.model.DelayMemberChange
import eth.serotine.domain.model.DelayTransfer
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeEvent
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.SwapKind
import eth.serotine.domain.model.SwarmCalls
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.usecase.AddressIndexEntryCheck
import eth.serotine.domain.usecase.CheckAddressIndexEntry
import eth.serotine.domain.usecase.IndexedEvent
import eth.serotine.domain.usecase.IndexedHistory
import eth.serotine.domain.usecase.KnownToken
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAmountsHidden
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentTo
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.SafeChainEvent
import eth.serotine.domain.usecase.ToggleAmountsHidden
import eth.serotine.domain.usecase.DescribePastTransaction
import eth.serotine.domain.usecase.PastCallUnavailable
import eth.serotine.domain.usecase.PastDescription
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.ui.components.ClearSignedText
import eth.serotine.ui.dapptransaction.DappTransactionViewModel
import eth.serotine.domain.usecase.TokenTransferEvent
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityRow
import eth.serotine.ui.activity.ActivityRows
import eth.serotine.ui.activity.ActivitySources
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.activity.Parties
import eth.serotine.ui.activity.indexRefusalText
import eth.serotine.ui.activity.movesNothing
import eth.serotine.ui.activity.rolesText
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The ViewModel of the detail of one row of the Activity of the account at [address]
 * (docs/wallet-ux.md, screen 26), the row [key] names: a transaction this device sent, read from
 * the account's history ([ObserveAccountHistory]); or a token's transfer or a Safe's event, read
 * from the engine's verified log index as the Activity reads them ([ObserveTokenTransfers],
 * [ObserveSafeHistory]). Its headline and status are its row's ([ActivityRows]); its fields name
 * every address and hash it concerns in full, with the wallet's names for them ([ObserveAccounts])
 * and the tokens' symbols and decimals ([ObserveKnownTokens]); a Safe transaction approved or
 * executed, sent from here or seen on chain, is headed with what it does as its row is, from the
 * request this device holds of it. One received from an address the wallet never sent to, in a
 * transaction it did not send ([ObserveSentTo]), and that is none of its accounts says why to
 * compare it before sending to it, and shows that sender with no Copy. A transfer of a contract the wallet
 * does not list is a claim, as its row is: only the contract is named, and an ERC-20 one can be
 * added. No intents: Copy is the screen's own, Status and Add token are navigation.
 */
class ActivityDetailViewModel(
    private val account: AccountId,
    private val key: ActivityKey,
    observeAccountHistory: ObserveAccountHistory,
    observeAccounts: ObserveAccounts,
    observeSafeHistory: ObserveSafeHistory,
    observeTokenTransfers: ObserveTokenTransfers,
    observeKnownTokens: ObserveKnownTokens,
    observeSentTo: ObserveSentTo,
    /** Whether amounts are hidden (docs/wallet-ux.md, *Hiding amounts*): the eye in the top bar shows it. */
    observeAmountsHidden: ObserveAmountsHidden,
    /** Hides amounts or shows them again: what a tap on the eye asks. */
    private val toggleAmountsHidden: ToggleAmountsHidden,
    /**
     * Checks a row of the addr1 index again, which its detail shows only as the check finds it now (owner ruling,
     * 2026-11-21, #334); null where the host reads no such index, and such a row is not shown.
     */
    private val checkIndexEntry: CheckAddressIndexEntry? = null,
    /** Describes the row's transaction on the user's tap; null where the host offers no such thing. */
    private val describePastTransaction: DescribePastTransaction? = null,
    private val formatTime: (Long) -> String = ::formatDateTime,
) : ViewModel() {
    /** The account this screen is about: its address, on its network. */
    private val address: Address = account.address

    /** How often the user asked a row of the addr1 index to be checked again: each rise checks it once more. */
    private val checkAgain = MutableStateFlow(0)

    /** The account's network: everything here is read and sent there. */
    private val network: Network = account.network


    /**
     * What a row of this account says, with its amounts masked while they are [hidden], a Safe
     * transaction in the units of the [tokens] known and by the requests in [entries], as the
     * Activity says it.
     */
    private fun rowsFor(hidden: Boolean, tokens: Map<Address, KnownToken>?, entries: List<HistoryEntry>) =
        ActivityRows(
            address,
            network,
            formatTime,
            hidden,
            decodingTokens = ActivityRows.decodingTokens(tokens),
            requests = ActivityRows.requestsIn(entries),
        )

    private val sources = ActivitySources(
        account,
        observeAccounts,
        observeSafeHistory,
        observeTokenTransfers,
        observeKnownTokens,
        observeSentTo,
    )

    /**
     * Only what the row is read from is followed: the history for a sent one, the log index for an
     * event; the history beside an event too, for the request of a Safe transaction it may be of.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val shown: Flow<ActivityDetailUiState> = observeAmountsHidden().let { hiddenFlow ->
        val indexed = key.index
        if (indexed != null) {
            val entry = AddressIndexEntry(indexed.block, indexed.slot, indexed.roles)
            // Checked once each time the screen is read, and again on Try again: Loading (null) until it answers.
            val checked = checkAgain.flatMapLatest {
                flow<AddressIndexEntryCheck?> {
                    emit(null)
                    emit(
                        checkIndexEntry?.invoke(account, entry)
                            ?: AddressIndexEntryCheck.Refused(AddressIndexRefusal.NotInThisBuild),
                    )
                }
            }
            combine(checked, sources.parties, combine(sources.tokens, hiddenFlow, ::Pair)) { check, parties, both ->
                val (tokens, hidden) = both
                renderIndexed(check, parties, tokens, rowsFor(hidden, tokens, emptyList())).withHidden(hidden)
            }
        } else if (key.logIndex == null) {
            combine(
                observeAccountHistory(account),
                sources.parties,
                combine(sources.tokens, hiddenFlow, ::Pair),
            ) { history, parties, (tokens, hidden) ->
                val entries = (history as? HistoryList.Loaded)?.entries.orEmpty()
                lastTokens = tokens
                renderSent(history, parties, rowsFor(hidden, tokens, entries)).withHidden(hidden)
            }
        } else {
            combine(
                sources.parties,
                sources.safeHistory,
                sources.transfers,
                combine(sources.tokens, hiddenFlow, ::Pair),
                observeAccountHistory(account),
            ) { parties, safe, transfers, (tokens, hidden), history ->
                val entries = (history as? HistoryList.Loaded)?.entries.orEmpty()
                renderLogged(parties, safe, transfers, tokens, rowsFor(hidden, tokens, entries)).withHidden(hidden)
            }
        }
    }

    /**
     * Checks a row of the addr1 index once more: Try again, after a check that could not be made now (the light
     * client still catching up, a block no peer served). Nothing for any other row.
     */
    fun checkAgain() {
        if (key.index != null) checkAgain.update { it + 1 }
    }

    /**
     * Hides amounts, or shows them again: the eye in the top bar. This screen has no message to say a
     * choice that could not be saved: the eye simply stays as it was.
     */
    fun toggleAmounts() {
        viewModelScope.launch { toggleAmountsHidden() }
    }

    /** [this] with the eye's state, where it is drawn. */
    private fun ActivityDetailUiState.withHidden(hidden: Boolean): ActivityDetailUiState =
        if (this is ActivityDetailUiState.Shown) copy(amountsHidden = hidden) else this

    /** The transaction the row is of, as its render found it: its hash, its block, whether this device sent it. */
    private data class Target(val hash: String, val block: Long?, val recorded: Boolean)

    /** What a tap on Describe came to, for the transaction of [Target.hash]; null before the tap. */
    private data class Described(val hash: String, val working: Boolean, val result: PastDescription?)

    @kotlin.concurrent.Volatile
    private var target: Target? = null

    @kotlin.concurrent.Volatile
    private var lastTokens: Map<Address, KnownToken>? = null

    private val described = MutableStateFlow<Described?>(null)

    /** Reads and describes the row's transaction: what Describe does. Nothing is kept beyond this screen. */
    fun describe() {
        val use = describePastTransaction ?: return
        val at = target ?: return
        if (described.value?.working == true) return
        described.value = Described(at.hash, working = true, result = null)
        viewModelScope.launch {
            val result = try {
                use(account, at.hash, at.block)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                PastDescription.NotRead(PastCallUnavailable.Unavailable(eth.serotine.domain.repository.ReadUnavailable.UNPROVEN))
            }
            described.value = Described(at.hash, working = false, result = result)
        }
    }

    /** [state] with Describe's section, for the transaction its render found. */
    private fun withDescribe(state: ActivityDetailUiState, done: Described?): ActivityDetailUiState {
        if (state !is ActivityDetailUiState.Shown || describePastTransaction == null) return state
        val at = target ?: return state
        val offer = if (at.recorded) {
            "Reads its call from this device's record of it: nothing is downloaded."
        } else {
            val block = at.block?.let { "block ${grouped(it)}'s" } ?: "its block's"
            "Downloads $block transactions to read its call, usually ${blockSize(network)}, verified against the " +
                "block's header."
        }
        val section = PastDescribeSection(offer)
        if (done == null || done.hash != at.hash) return state.copy(describe = section)
        if (done.working) return state.copy(describe = section.copy(working = true))
        return state.copy(describe = pastSection(section, done.result))
    }

    private fun pastSection(section: PastDescribeSection, result: PastDescription?): PastDescribeSection {
        val read = when (result) {
            null -> return section
            is PastDescription.NotRead -> return section.copy(done = true, failed = notReadText(result.reason))
            is PastDescription.Read -> result
        }
        val tokens = ActivityRows.decodingTokens(lastTokens)
        val safeItem = read.safeAction?.let {
            eth.serotine.ui.components.safeTxActionItem(it, null, tokens, network)
        }
        val clear = read.result as? ClearSignResult.ClearSigned
        val headline = when {
            safeItem != null -> "Executed a transaction of the Safe ${Eip55.format(read.safe!!)}: what the Safe ran " +
                "is below."
            clear != null -> null
            else -> DappTransactionViewModel.meaningLine(read.meaning, null, read.call.from)
                ?: "A call Punicus does not read itself: function 0x${read.call.data.take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }}."
        }
        val notes = buildList {
            if (clear != null) {
                add(
                    "Described now, after the fact: the description is the contract's built-in one, checked against " +
                        "the contract's code as it is today.",
                )
            }
            if (read.codeMayHaveChanged) {
                add(
                    "This contract can be upgraded: when the transaction ran, its code may have been other than the " +
                        "code checked now, and the description may not fit what ran.",
                )
            }
            (read.result as? ClearSignResult.NotCovered)?.let { not ->
                add(ClearSignedText.notDescribedLine(not.reason) ?: "No built-in description covers this contract.")
            }
            add(
                if (read.downloaded) {
                    "Its call was read from its block, verified by the light client against the block's header."
                } else {
                    "Its call was read from this device's record of what it sent."
                },
            )
        }
        return section.copy(
            done = true,
            headline = headline,
            safeAction = safeItem?.withDescription(read.result.takeIf { read.safeAction is eth.serotine.domain.model.SafeTxAction.ContractCall }, false),
            described = clear?.takeIf { safeItem == null }?.let(ClearSignedText::describedSection),
            notes = notes,
        )
    }

    private fun notReadText(reason: PastCallUnavailable): String = when (reason) {
        PastCallUnavailable.NoBlock -> "No block is known to hold this transaction, so its call cannot be read."
        PastCallUnavailable.BlockNotServed -> "Its block is not served right now: no peer gave it, or the light " +
            "client has not reached it. Old blocks may no longer be kept by peers."
        PastCallUnavailable.NotInBlock -> "Its block was read and holds no transaction of this hash."
        PastCallUnavailable.Creation -> "It created a contract: there is no call to describe."
        is PastCallUnavailable.Unavailable -> eth.serotine.ui.components.unavailableText(reason.reason)
    }

    val uiState: StateFlow<ActivityDetailUiState> = combine(shown, described) { state, done ->
        withDescribe(state, done)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
        ActivityDetailUiState.Loading(sources.shortTitle),
    )

    private fun renderSent(history: HistoryList, parties: Parties, rows: ActivityRows): ActivityDetailUiState {
        val names = parties.names
        val title = sources.titleOf(names)
        val entries = when (history) {
            HistoryList.Unreadable -> return ActivityDetailUiState.Unavailable(title, UNREADABLE)
            is HistoryList.Loaded -> history.entries
        }
        val entry = entries.firstOrNull { it.transfer.hash == key.hash }
            ?: return ActivityDetailUiState.Unavailable(title, NOT_SENT_HERE)
        // Only a call with data has anything to describe: a payment or a token transfer reads as one already.
        val call = eth.serotine.domain.model.Eip1559Transaction.callOf(entry.transfer.signed.raw)
        target = Target(entry.transfer.hash, entry.outcome.blockNumberOrNull, recorded = true)
            .takeIf { call != null && call.data.isNotEmpty() }
        return shownOf(title, rows.row(entry, names), sentFields(entry, names, rows), opensStatus = entry.transfer.hash)
    }

    private fun renderLogged(
        parties: Parties,
        safe: IndexedHistory<SafeChainEvent>?,
        transfers: IndexedHistory<TokenTransferEvent>?,
        tokens: Map<Address, KnownToken>?,
        rows: ActivityRows,
    ): ActivityDetailUiState {
        val names = parties.names
        val title = sources.titleOf(names)
        if (parties.list !is AccountList.Loaded) return ActivityDetailUiState.Unavailable(title, ACCOUNTS_UNREADABLE)
        // Only an account of the wallet has its logs followed.
        if (transfers == null) return ActivityDetailUiState.Unavailable(title, NOT_IN_WALLET)
        lastTokens = tokens
        safe.found()?.let {
            target = Target(it.transactionHash, it.blockNumber, recorded = false)
            return shownOf(title, rows.eventRow(it, parties), safeFields(it, names, rows))
        }
        val transfer = transfers.found() ?: return missing(title, listOfNotNull(safe, transfers))
        target = Target(transfer.transactionHash, transfer.blockNumber, recorded = false)
        // A transfer of nothing is not listed, and not detailed either: it plants a look-alike address.
        if (transfer.transfer.movesNothing()) return ActivityDetailUiState.Unavailable(title, MOVED_NOTHING)
        // Until the tokens are known, what moved cannot be said.
        if (tokens == null) return ActivityDetailUiState.Loading(title)
        return transferShown(title, transfer, parties, tokens, rows)
    }

    /**
     * A row of the addr1 index as the engine's check finds it now ([check]; null until it answers): shown only when
     * verified, with every field it concerns, the block it was checked against, which roles are proven and which rest
     * on the importer's word, and what it rests on; anything else says why it is not shown.
     */
    private fun renderIndexed(
        check: AddressIndexEntryCheck?,
        parties: Parties,
        tokens: Map<Address, KnownToken>?,
        rows: ActivityRows,
    ): ActivityDetailUiState {
        val names = parties.names
        val title = sources.titleOf(names)
        val verdict = when (check) {
            null -> return ActivityDetailUiState.Loading(title)
            is AddressIndexEntryCheck.Refused -> return ActivityDetailUiState.Unavailable(
                title,
                indexRefusalText(check.reason, network),
                retry = check.reason.retryable,
            )
            is AddressIndexEntryCheck.Checked -> check.verdict
        }
        val indexed = when (verdict) {
            is AddressIndexVerdict.Verified -> verdict.row
            is AddressIndexVerdict.Contradicted -> return ActivityDetailUiState.Unavailable(
                title,
                "The chain now contradicts this entry of the addr1 index (${rolesText(verdict.contradicted)}), so " +
                    "it is not shown. Its importer's index said something the chain proves false.",
            )
            is AddressIndexVerdict.Unverified -> return ActivityDetailUiState.Unavailable(
                title,
                "This entry of the addr1 index cannot be verified" +
                    (verdict.reason?.let { ": $it" } ?: "") + ". It is not shown.",
            )
            is AddressIndexVerdict.Unavailable -> return ActivityDetailUiState.Unavailable(
                title,
                "This entry cannot be checked right now (${verdict.reason}).",
                retry = true,
            )
        }
        // Until the tokens are known, what moved cannot be said.
        if (tokens == null) return ActivityDetailUiState.Loading(title)
        val row = rows.indexRow(indexed, parties, tokens)
            ?: return ActivityDetailUiState.Unavailable(title, MOVED_NOTHING)
        val entry = indexed.entry
        val tx = indexed.transaction
        lastTokens = tokens
        target = tx?.let { Target(it.hash, entry.block, recorded = false) }
        // A transaction this account did not send was made by someone else, who chose every address in it: anyone can
        // name the account in one (a topic, a word of its input), from or to a look-alike planted for the user to copy.
        // So no address of its other side gets Copy here (address poisoning protection, #324).
        val othersChose = tx != null && tx.from != address
        // A token the wallet does not show can log any transfer between any addresses: its contract alone is named,
        // as the log index's detail of the same log names it, never the transaction's parties.
        val unshownToken = tx?.transfer?.takeIf { tokens[it.token]?.shown != true }
        val fields = buildList {
            tx?.let { tx ->
                add(DetailField("Transaction hash", tx.hash, DetailKind.HASH))
                if (unshownToken == null) {
                    add(addressOf("From", tx.from, names).withCopy(!othersChose || tx.from == address))
                    add(
                        tx.to?.let { addressOf("To", it, names).withCopy(!othersChose || it == address) }
                            ?: DetailField("To", "None: it creates a contract"),
                    )
                }
                // A fact of the transaction, not a claim that it moved: a reverted one keeps its value field.
                tx.value.toUInt256OrNull()?.let { add(DetailField("Value", rows.amount(it, TransferAsset.Native))) }
                add(
                    DetailField(
                        "Outcome",
                        when (tx.succeeded) {
                            true -> "Succeeded, as its verified receipt says"
                            false -> "Failed on chain: its call reverted, and its value did not move"
                            null -> "Not checked: whether it succeeded is not reported by the engine yet, so its " +
                                "value may not have moved"
                        },
                    ),
                )
                tx.transfer?.let { add(addressOf("Token contract", it.token, names, tokens[it.token]?.token?.symbol)) }
            }
            // When its block was produced, from the block's verified header (Myotis 0.1.24 on), as the list says it.
            indexed.blockTimestamp?.let { add(DetailField("Time", formatTime(it))) }
            add(DetailField("Block", grouped(entry.block), DetailKind.BLOCK))
            add(DetailField("Block hash", indexed.blockHash, DetailKind.HASH))
            val position = entry.transactionIndex?.let { "Transaction ${it + 1}" } ?: "The block itself"
            add(DetailField("In the block", position))
            add(DetailField("Proven on chain", rolesText(indexed.confirmed).replaceFirstChar { it.uppercase() }))
            if (indexed.unchecked.isNotEmpty()) {
                add(
                    DetailField(
                        "On the importer's word",
                        rolesText(indexed.unchecked).replaceFirstChar { it.uppercase() },
                    ),
                )
            }
        }
        val importer = checkIndexEntry?.importerOf(account.network)?.let(Eip55::format)
        val restsOn = "Checked by the light client against block ${grouped(entry.block)}'s verified header and " +
            "receipts. " + (if (indexed.final) "The beacon chain has finalized that block. " else "That block is " +
            "not final yet: a reorganisation may still replace it. ") + "A block older than the light client's " +
            "window is checked against the checkpoint table bundled with its engine. That the addr1 index lists " +
            "this entry at all, and that none is missing, rests on its importer" +
            (importer?.let { ", $it, pinned in this release." } ?: ".")
        val claim = when {
            unshownToken == null -> null
            unshownToken is TokenTransfer.Nft -> "$CLAIM_NOTE $NFT_NOTE"
            else -> CLAIM_NOTE
        }
        return shownOf(
            title,
            row,
            fields,
            note = listOfNotNull(
                claim,
                SENDER_NOTE.takeIf { row.state == ActivityState.CAUTION },
                OTHERS_CHOSE_NOTE.takeIf { othersChose && unshownToken == null && row.state != ActivityState.CAUTION },
                restsOn,
            ).joinToString("\n\n"),
        )
    }

    /** How much one block's transactions usually are on [network]: what Describe says it downloads. */
    private fun blockSize(network: Network): String = when (network) {
        Network.ETHEREUM -> "about 150 KB, at most about 2 MB"
        Network.GNOSIS -> "about 50 KB, at most about 1 MB"
        Network.SEPOLIA -> "about 100 KB, at most about 2 MB"
    }

    /** This field with Copy only where [copy] says so. */
    private fun DetailField.withCopy(copy: Boolean): DetailField = if (copy) this else copy(copyable = false)

    /** The event [key] names in this history, when it is read with it. */
    private fun <E : IndexedEvent> IndexedHistory<E>?.found(): E? = (this as? IndexedHistory.Read)?.events
        ?.firstOrNull { it.transactionHash == key.hash && it.logIndex == key.logIndex }

    /**
     * Why the event is not shown when no history read has it: gone once every one is checked from
     * its start (a reorganisation replaced its block), and loading otherwise: while the blocks it
     * may be in are being checked, and while a history cannot be read, which is also what one says
     * until its first coverage answers (the strip says when the light client is not running).
     */
    private fun missing(title: String, histories: List<IndexedHistory<*>>): ActivityDetailUiState =
        if (histories.all { it is IndexedHistory.Read && it.complete }) {
            ActivityDetailUiState.Unavailable(title, GONE)
        } else {
            ActivityDetailUiState.Loading(title)
        }

    private fun shownOf(
        title: String,
        row: ActivityRow,
        fields: List<DetailField>,
        note: String? = null,
        addToken: String? = null,
        opensStatus: String? = null,
    ) = ActivityDetailUiState.Shown(
        title = title,
        headline = row.title,
        about = row.about,
        status = row.status,
        simpleStatus = row.simpleStatus,
        state = row.state,
        // The sender a row is cautioned about may be a planted look-alike: shown in full, to compare,
        // with no Copy (address poisoning protection, 2026-11-18, #324). Its row names it as [row.about].
        fields = if (row.state == ActivityState.CAUTION) {
            fields.map { if (it.kind == DetailKind.ADDRESS && it.value == row.about) it.copy(copyable = false) else it }
        } else {
            fields
        },
        // A row cautioned about its sender says why, and why it cannot be copied here.
        note = note ?: SENDER_NOTE.takeIf { row.state == ActivityState.CAUTION },
        addToken = addToken,
        opensStatus = opensStatus,
    )

    /** Each party a sent transaction concerns, then what it moved, then where it is on chain. */
    private fun sentFields(entry: HistoryEntry, names: Map<Address, String>, rows: ActivityRows): List<DetailField> {
        val transfer = entry.transfer
        val fields = mutableListOf(addressOf("From", transfer.account, names))
        when (val action = transfer.action) {
            is SentAction.Transfer -> {
                fields += addressOf("To", action.to, names)
                fields += DetailField("Amount", rows.amount(action.amount, action.asset))
                fields.addContract("Token contract", action.asset, names)
            }
            is SentAction.TokenApproval -> {
                fields += DetailField("Allowance", rows.amount(action.amount, TransferAsset.Erc20Token(action.token)))
                fields.addContract("Token contract", TransferAsset.Erc20Token(action.token), names)
                fields += addressOf("Spender", action.spender, names)
            }
            is SentAction.Swap -> {
                if (action.kind == SwapKind.BUY) {
                    fields += DetailField("Sold at most", rows.amount(action.amountIn, action.sell))
                    fields += DetailField("Bought exactly", rows.amount(action.minimumOut, action.buy))
                } else {
                    fields += DetailField("Sold", rows.amount(action.amountIn, action.sell))
                    fields += DetailField("Bought at least", rows.amount(action.minimumOut, action.buy))
                }
                fields.addContract("Token sold", action.sell, names)
                fields.addContract("Token bought", action.buy, names)
                fields += addressOf("Router", action.router, names)
            }
            is SentAction.Wrap -> {
                fields += DetailField("Wrapped", rows.amount(action.amount, TransferAsset.Native))
                fields += DetailField("Into", rows.amount(action.amount, TransferAsset.Erc20Token(action.weth)))
                fields.addContract("WETH9 contract", TransferAsset.Erc20Token(action.weth), names)
            }
            is SentAction.Unwrap -> {
                fields += DetailField("Unwrapped", rows.amount(action.amount, TransferAsset.Erc20Token(action.weth)))
                fields += DetailField("Into", rows.amount(action.amount, TransferAsset.Native))
                fields.addContract("WETH9 contract", TransferAsset.Erc20Token(action.weth), names)
            }
            is SentAction.SafeApproval -> fields += safeCallFields(action.request, names)
            is SentAction.SafeExecution -> fields += safeCallFields(action.request, names)
            is SentAction.SafeDeployment -> {
                fields += addressOf("Safe", action.safe, names, action.label.ifBlank { null })
                action.owners.forEachIndexed { at, owner -> fields += addressOf("Owner ${at + 1}", owner, names) }
                fields += DetailField("Threshold", "${action.threshold} of ${rows.owners(action.owners.size.toLong())}")
                fields += DetailField("Safe version", action.version)
            }
            is SentAction.CowCancellation -> {
                fields += DetailField("CoW order", action.uid.hex, DetailKind.HASH)
                fields += addressOf("Settlement contract", action.settlement, names)
            }
            is SentAction.DappCall -> {
                fields += addressOf("To", action.to, names)
                fields += DetailField("Value", rows.amount(action.value, TransferAsset.Native))
                fields += DetailField("For the dApp", "${action.dapp} (stated by the dApp, not verified)")
            }
            is SentAction.DelayQueue -> fields += delayFields(action.call, names, rows)
            is SentAction.DelayExecution -> fields += delayFields(action.call, names, rows)
            is SentAction.DelaySkip -> {
                fields += addressOf("Gnosis Pay account", action.safe, names)
                fields += addressOf("Delay module", action.delay, names)
            }
            // A Swarm node's postage batch (docs/swarm.md, *History*).
            is SentAction.BatchPurchase -> {
                fields += addressOf("For the node", action.owner, names)
                fields += DetailField("Batch", "0x" + action.id.hex, DetailKind.HASH)
                fields += DetailField("Depth", action.depth.toString())
                SwarmCalls.total(action.perChunk, action.depth)
                    ?.let { fields += DetailField("Paid", rows.amount(it, TransferAsset.Erc20Token(action.token))) }
                fields += DetailField("Immutable", if (action.immutable) "Yes" else "No")
                fields += addressOf("Postage contract", action.stamp, names)
            }
            is SentAction.BatchTopUp -> {
                fields += addressOf("For the node", action.node, names)
                fields += DetailField("Batch", "0x" + action.id.hex, DetailKind.HASH)
                SwarmCalls.total(action.perChunk, action.depth)
                    ?.let { fields += DetailField("Paid", rows.amount(it, TransferAsset.Erc20Token(action.token))) }
                fields += addressOf("Postage contract", action.stamp, names)
            }
            is SentAction.BatchDilution -> {
                fields += DetailField("Batch", "0x" + action.id.hex, DetailKind.HASH)
                fields += DetailField("New depth", action.depth.toString())
                fields += addressOf("Postage contract", action.stamp, names)
            }
            is SentAction.ChequebookWithdrawal -> {
                fields += addressOf("Chequebook", action.chequebook, names)
                fields += DetailField("Withdrawn", rows.amount(action.amount, TransferAsset.Erc20Token(action.token)))
                fields += DetailField("To", "The node's own address, which sent it")
            }
        }
        fields += DetailField("Transaction hash", transfer.hash, DetailKind.HASH)
        fields += DetailField("Nonce", transfer.nonce.toString())
        fields += DetailField("Sent", formatTime(transfer.sentAt))
        entry.outcome.blockNumberOrNull?.let { fields += DetailField("Block", grouped(it), DetailKind.BLOCK) }
        return fields
    }

    /** What an approval or execution of [request]'s Safe transaction concerns: the Safe, and the transaction. */
    private fun safeCallFields(request: SigningRequest, names: Map<Address, String>): List<DetailField> = listOf(
        addressOf("Safe", request.safe, names),
        DetailField("Safe nonce", request.nonce.toString()),
        DetailField("Safe transaction hash", "0x${request.hash.hex}", DetailKind.HASH),
        addressOf("Safe transaction to", request.tx.to, names),
    )

    /**
     * What a Gnosis Pay account's [call] through its Delay concerns: the account, the Delay, and whom
     * and what for a transfer, or the member added or removed.
     */
    private fun delayFields(call: DelayCall, names: Map<Address, String>, rows: ActivityRows) =
        mutableListOf<DetailField>().apply {
            this += addressOf("Gnosis Pay account", call.safe, names)
            this += addressOf("Delay module", call.delay, names)
            when (call) {
                is DelayTransfer -> {
                    this += addressOf("To", call.recipient, names)
                    this += DetailField("Amount", rows.amount(call.amount, call.asset))
                    addContract("Token contract", call.asset, names)
                }
                is DelayMemberChange -> this += addressOf(
                    if (call.kind == DelayMemberChange.Kind.ADD) "Member added" else "Member removed",
                    call.member,
                    names,
                )
            }
        }

    /** The contract of [asset], when it is a token, named by its symbol. */
    private fun MutableList<DetailField>.addContract(label: String, asset: TransferAsset, names: Map<Address, String>) {
        val token = (asset as? TransferAsset.Erc20Token)?.token ?: return
        this += addressOf(label, token.address, names, displaySymbol(token.symbol))
    }

    /** What a Safe's event concerns, the Safe, then where it is on chain. */
    private fun safeFields(event: SafeChainEvent, names: Map<Address, String>, rows: ActivityRows): List<DetailField> {
        val fields = mutableListOf<DetailField>()
        when (val what = event.event) {
            is SafeEvent.SetUp -> {
                fields += addressOf("Set up by", what.initiator, names)
                what.owners.forEachIndexed { at, owner -> fields += addressOf("Owner ${at + 1}", owner, names) }
                fields += DetailField("Threshold", "${what.threshold} of ${rows.owners(what.owners.size.toLong())}")
            }
            is SafeEvent.Executed -> fields += DetailField("Safe transaction hash", what.safeTxHash, DetailKind.HASH)
            is SafeEvent.Received -> {
                fields += addressOf("From", what.from, names)
                fields += DetailField("Amount", rows.amount(what.amount, TransferAsset.Native))
            }
            is SafeEvent.Approved -> {
                fields += addressOf("Owner", what.owner, names)
                fields += DetailField("Safe transaction hash", what.safeTxHash, DetailKind.HASH)
            }
            is SafeEvent.OwnerAdded -> fields += addressOf("Owner", what.owner, names)
            is SafeEvent.OwnerRemoved -> fields += addressOf("Owner", what.owner, names)
            is SafeEvent.ThresholdChanged ->
                fields += DetailField("Threshold", "${rows.owners(what.threshold)} must sign")
            is SafeEvent.ModuleEnabled -> fields += addressOf("Module", what.module, names)
            is SafeEvent.ModuleDisabled -> fields += addressOf("Module", what.module, names)
            is SafeEvent.ModuleExecuted -> fields += addressOf("Module", what.module, names)
            is SafeEvent.GuardChanged -> fields += if (what.guard == Address.ZERO) {
                DetailField("Guard", "None")
            } else {
                addressOf("Guard", what.guard, names)
            }
            is SafeEvent.FallbackHandlerChanged -> fields += if (what.handler == Address.ZERO) {
                DetailField("Fallback handler", "None")
            } else {
                addressOf("Fallback handler", what.handler, names)
            }
            is SafeEvent.MessageSigned -> fields += DetailField("Message hash", what.messageHash, DetailKind.HASH)
        }
        fields += addressOf("Safe", address, names)
        return fields + loggedAt(event)
    }

    /**
     * A token's transfer: for a token the wallet shows, both sides and what moved; for any other
     * contract only the contract, as its row does, since it can log any transfer between any
     * addresses (a look-alike address planted for the user to copy). An ERC-20 one the wallet does
     * not list can be added; one it hides, or a built-in one its contract contradicts, is listed
     * already. The protocol's log of an ETH transfer (EIP-7708) is no token's: both sides and the
     * ETH that moved, with no contract.
     */
    private fun transferShown(
        title: String,
        event: TokenTransferEvent,
        parties: Parties,
        tokens: Map<Address, KnownToken>,
        rows: ActivityRows,
    ): ActivityDetailUiState {
        val names = parties.names
        val transfer = event.transfer
        val row = rows.transferRow(event, parties, tokens)
        val sides = listOf(addressOf("From", transfer.from, names), addressOf("To", transfer.to, names))
        if (transfer is TokenTransfer.Fungible && transfer.isEth) {
            val moved = DetailField("Amount", rows.amount(transfer.amount, TransferAsset.Native))
            // A cautioned sender says why; otherwise, what this log is.
            val note = if (row.state == ActivityState.CAUTION) "$SENDER_NOTE ${ethNote(network)}" else ethNote(network)
            return shownOf(title, row, sides + moved + loggedAt(event), note = note)
        }
        val listed = tokens[transfer.token]
        val known = listed?.takeIf { it.shown }
        val symbol = listed?.let { displaySymbol(it.token.symbol) }
        val contract = addressOf("Token contract", transfer.token, names, symbol)
        if (known == null) {
            val fields = listOf(contract) + loggedAt(event)
            if (transfer !is TokenTransfer.Fungible) return shownOf(title, row, fields, note = "$CLAIM_NOTE $NFT_NOTE")
            val add = Eip55.format(transfer.token).takeIf { listed == null }
                ?: return shownOf(title, row, fields, note = CLAIM_NOTE)
            // The row's status asks to open it to add it; here, Add token is on screen.
            val shown = shownOf(title, row.copy(status = UNLISTED), fields, note = "$CLAIM_NOTE $ADD_NOTE")
            return shown.copy(addToken = add)
        }
        val moved = when (transfer) {
            is TokenTransfer.Fungible ->
                DetailField(
                    "Amount",
                    rows.figure(transfer.amount, known.token.decimals, displaySymbol(known.token.symbol)),
                )
            is TokenTransfer.Nft -> DetailField("Token id", transfer.tokenId.toDecimal())
        }
        return shownOf(title, row, sides + moved + contract + loggedAt(event))
    }

    /**
     * Where an event is on chain: the transaction that logged it, when its block was produced where
     * the engine reported it from the block's verified header, and its block.
     */
    private fun loggedAt(event: IndexedEvent): List<DetailField> = listOfNotNull(
        DetailField("Transaction hash", event.transactionHash, DetailKind.HASH),
        event.blockTimestamp?.let { DetailField("Time", formatTime(it)) },
        DetailField("Block", grouped(event.blockNumber), DetailKind.BLOCK),
    )

    /**
     * [of] as a field: its EIP-55 address, named as the wallet names it, else as [name] says (a
     * token's symbol, a Safe's planned name), else the zero address as such.
     */
    private fun addressOf(label: String, of: Address, names: Map<Address, String>, name: String? = null) = DetailField(
        label = label,
        value = Eip55.format(of),
        kind = DetailKind.ADDRESS,
        name = names[of] ?: name ?: ZERO_NAME.takeIf { of == Address.ZERO },
    )

    private companion object {
        const val UNREADABLE = "The history of your transactions cannot be read right now. Punicus keeps trying."
        const val NOT_SENT_HERE = "This transaction is not in this account's history."
        const val ACCOUNTS_UNREADABLE = "The wallet's accounts cannot be read right now."
        const val NOT_IN_WALLET = "This account is not in the wallet any more, so its history on chain is not read."
        const val GONE = "This event is not in the account's verified history any more: a reorganisation of the " +
            "chain may have replaced its block."
        const val MOVED_NOTHING = "This transfer moved nothing, so it is not shown: anyone can have a token log " +
            "one from any address, to plant a look-alike address for you to copy."
        const val UNLISTED = "The claim of a contract the wallet does not list"

        /** What the network's own log of a transfer of its currency (EIP-7708) is: no token's. */
        fun ethNote(network: Network): String = "A plain ${network.nativeSymbol} transfer, which the network itself " +
            "logs since its upgrade with EIP-7708: no token is involved, whatever explorers list it under."
        const val SENDER_NOTE = "You have not sent to the sender's address from this device, and it is not one of " +
            "your accounts. Anyone can send you a little from an address made to look like one you use, so that you " +
            "copy it from your history later: compare every character before you send anything to it. Copy is " +
            "off for it here, so that it is not pasted by mistake."
        const val OTHERS_CHOSE_NOTE = "This transaction was not sent by this account: whoever sent it chose its " +
            "addresses, and anyone can name your account in one. Copy is off for them here, so that a look-alike is " +
            "not pasted by mistake."
        const val ZERO_NAME = "The zero address"
        const val CLAIM_NOTE = "A contract the wallet does not show can log any transfer it likes, between any " +
            "addresses, so only the contract is named here: an address it names could be a look-alike planted " +
            "for you to copy."
        const val ADD_NOTE = "Add it as a token to see what moved: its symbol, name and decimals are read from " +
            "the contract first."
        const val NFT_NOTE = "It logged an ERC-721 transfer, and the wallet adds ERC-20 tokens only."
    }
}
