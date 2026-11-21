package eth.serotine.ui.activity

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.AddressIndexContinuation
import eth.serotine.domain.usecase.AddressIndexLeftOut
import eth.serotine.domain.usecase.AddressIndexLoad
import eth.serotine.domain.usecase.LoadAddressIndexPage
import eth.serotine.domain.usecase.ObserveAddressIndexStatus
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.grouped
import eth.serotine.ui.components.shortAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * What an account's Activity reads of the addr1 index, on the desktop with its experimental setting on (owner
 * ruling, 2026-11-21, #334; the owner's decisions of that day on #335): whether the setting is on ([status]), and its
 * pages of verified rows ([load]). A host that offers no such setting (Android, iOS), or a release that pins no
 * importer key, hands none to [AccountActivity], and the Activity is the log index's alone.
 */
class AddressIndexActivity(
    private val status: ObserveAddressIndexStatus,
    private val load: LoadAddressIndexPage,
) {
    /**
     * The pages of [account]'s addr1 history while the setting is on, null while it is off (and until it is read).
     * The newest page is read at once; each later value of [more] above the one it started with asks for the next
     * page, one at a time, or, after a passing refusal, the same page again. An ask that comes while a page is read
     * is that page's: it asks for nothing more. Nothing is read while it is off.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun of(account: AccountId, more: StateFlow<Int>): Flow<IndexPaging?> = status()
        .map { if (it.on) it.pinned else null }
        .distinctUntilChanged()
        .flatMapLatest { pinned -> if (pinned == null) flowOf(null) else pages(account, pinned, more) }
        .onStart { emit(null) }

    private fun pages(account: AccountId, pinned: Address, more: StateFlow<Int>): Flow<IndexPaging> = flow {
        var asked = more.value
        var paging = IndexPaging(pinned)
        emit(paging)
        paging = paging.after(load(account))
        emit(paging)
        more.collect { value ->
            if (value <= asked) return@collect
            asked = value
            if (!paging.canAsk) return@collect
            paging = paging.copy(loading = true, refused = null)
            emit(paging)
            paging = paging.after(load(account, paging.next))
            // What was asked while it was read was asked of this page.
            asked = maxOf(asked, more.value)
            emit(paging)
        }
    }
}

/**
 * The pages of an account's addr1 history read so far, as one list: its verified [rows] (each entry once, by block
 * and slot), what the index covered at the last page read ([coverage]; null before the first) and the key [pinned]
 * in the release it rests on, the entries every page [leftOut], where it goes on ([next]), whether a page is being
 * read ([loading]), and why the last read was [refused], if it was. Of the last page read, its verified rows
 * ([lastRows]) and what it left out ([lastLeftOut]): whether it added a row to the list decides whether the next page
 * is read by itself.
 */
internal data class IndexPaging(
    val pinned: Address,
    val rows: List<AddressIndexRow> = emptyList(),
    val coverage: AddressIndexCoverage? = null,
    val leftOut: AddressIndexLeftOut = AddressIndexLeftOut(),
    val next: AddressIndexContinuation? = null,
    val loading: Boolean = true,
    val refused: AddressIndexRefusal? = null,
    val lastRows: List<AddressIndexRow> = emptyList(),
    val lastLeftOut: AddressIndexLeftOut = AddressIndexLeftOut(),
) {
    /** Whether a page was read: [coverage] is known, and [next] null means the end of its coverage. */
    val started: Boolean
        get() = coverage != null

    /** Whether the end of what the index covers was reached: no page lies below. */
    val ended: Boolean
        get() = started && next == null

    /** Whether another page may be asked for now: none is being read, more lies below, nothing lasting refused it. */
    val canAsk: Boolean
        get() = !loading && !ended && refused?.retryable != false

    /**
     * This list after [load]: its rows added, newest first, and its counts; or the refusal said, the rows kept but
     * on the checkpoint table's alarm, which withdraws the trust they rest on (`.claude/rules/trust.md`), and on an
     * index signed by another key than the pinned one, which the list may not rest on. With the alarm, what the
     * index said of its coverage and its counts go too. In this part the alarm reaches the list only this way, as a
     * read refused for it; part 4 of the plan feeds the engine's alarm itself (`StatusSnapshot.checkpointAlarm`) in,
     * so that the rows go the moment it is raised, whether or not a page is read.
     */
    fun after(load: AddressIndexLoad): IndexPaging = when (load) {
        is AddressIndexLoad.Loaded -> copy(
            rows = (rows + load.rows)
                .distinctBy { it.entry.block to it.entry.slot }
                .sortedWith(compareByDescending<AddressIndexRow> { it.entry.block }.thenByDescending { it.entry.slot }),
            coverage = load.coverage,
            leftOut = leftOut + load.leftOut,
            next = load.next,
            loading = false,
            refused = null,
            lastRows = load.rows,
            lastLeftOut = load.leftOut,
        )
        is AddressIndexLoad.Refused -> when (load.reason) {
            is AddressIndexRefusal.CheckpointAlarm -> IndexPaging(pinned, loading = false, refused = load.reason)
            is AddressIndexRefusal.WrongImporter -> copy(rows = emptyList(), loading = false, refused = load.reason)
            else -> copy(loading = false, refused = load.reason)
        }
    }
}

/**
 * [paging] as the Activity says it (docs/wallet-ux.md, screen 25): what the index is and what it rests on, the
 * importer named in full; every range it does not cover; how many entries it left out, and apart, how many the chain
 * contradicts; and its footer. [network] names a network in a refusal. [shownFromLast] is how many of the last page's
 * rows the list shows (the rest were listed already, or are not listed); null while the rows cannot be said yet (the
 * tokens not read).
 */
internal fun indexSection(paging: IndexPaging, network: Network, shownFromLast: Int?): IndexSection {
    val key = Eip55.format(paging.pinned)
    val coverage = paging.coverage
    val lines = buildList {
        add(
            "Also from the addr1 index on Swarm, each entry checked against the chain before it is listed. Which " +
                "entries it holds rests on its importer, $key, pinned in this release: entries may be missing.",
        )
        coverage?.let { addAll(coverageLines(it)) }
    }
    val simpleLines = buildList {
        add(
            "Older activity also comes from a shared index, each entry checked against the chain. Whether it is " +
                "complete rests on its publisher, key $key: some activity may be missing.",
        )
        coverage?.let { addAll(simpleCoverageLines(it)) }
    }
    val leftOut = paging.leftOut
    return IndexSection(
        lines = lines,
        simpleLines = simpleLines,
        leftOut = leftOut.notVerified.takeIf { it > 0 }?.let { n ->
            "${entries(n)} of the index ${if (n == 1) "is" else "are"} left out: " +
                "${if (n == 1) "it" else "they"} could not be verified."
        },
        contradicted = leftOut.contradicted.takeIf { it > 0 }?.let { n ->
            "Warning: the importer's index contradicts the chain in ${entries(n)}, left out. An index that says " +
                "something the chain proves false may also leave entries out or be wrong elsewhere; its importer " +
                "is ${shortAddress(key)}."
        },
        simpleLeftOut = leftOut.notVerified.takeIf { it > 0 }?.let { n ->
            val shown = if (n == 1) "it isn't" else "they aren't"
            "${entries(n)} of the shared index couldn't be confirmed, so $shown shown."
        },
        simpleContradicted = leftOut.contradicted.takeIf { it > 0 }?.let { n ->
            "Warning: the shared index's publisher listed ${entries(n)} the chain shows to be wrong. " +
                "${if (n == 1) "It isn't" else "They aren't"} shown, and the index may also miss or get others wrong."
        },
        footer = footerOf(paging, network, shownFromLast),
    )
}

/**
 * The footer below the index's rows: loading, more, the end of its coverage, or why nothing (more) comes. More is read
 * by itself only while the last page added a row to the list ([shownFromLast]): each page sets off up to 50 checks of
 * old blocks against archive peers, so after a page that added none, the next is read only on the user's tap
 * ([IndexFooter.Paused]), which says what that page left out.
 */
private fun footerOf(paging: IndexPaging, network: Network, shownFromLast: Int?): IndexFooter {
    paging.refused?.let { reason ->
        return IndexFooter.Refused(
            indexRefusalText(reason, network),
            retry = reason.retryable,
            warning = reason is AddressIndexRefusal.CheckpointAlarm,
            simpleText = indexRefusalSimpleText(reason, network),
        )
    }
    if (paging.loading) {
        return if (paging.started) {
            IndexFooter.Loading("Reading more of the addr1 index and checking its entries…", CHECKING_MORE)
        } else {
            IndexFooter.Loading(
                "Reading the addr1 index from Swarm and checking its entries…",
                "Checking older activity from the shared index…",
            )
        }
    }
    val covered = paging.coverage?.covered
    return when {
        !paging.ended && shownFromLast == null ->
            IndexFooter.Loading("Reading the tokens its rows are said in…", CHECKING_MORE)
        !paging.ended && shownFromLast == 0 -> IndexFooter.Paused(pausedText(paging), simplePausedText(paging))
        !paging.ended -> IndexFooter.More
        covered == null -> IndexFooter.End(
            "The addr1 index covers no block yet: nothing comes from it.",
            "The shared index holds nothing yet.",
        )
        covered.first == 0L -> IndexFooter.End(
            "The addr1 index starts at the first block: this is everything it holds.",
            "That's everything the shared index holds.",
        )
        else -> IndexFooter.End(
            "The addr1 index starts at block ${grouped(covered.first)}: nothing older comes from it.",
            "The shared index starts here: nothing older comes from it.",
        )
    }
}

/** Why the list waits for a tap: what the last page of [paging] held, none of it a new row. */
private fun pausedText(paging: IndexPaging): String {
    val left = paging.lastLeftOut
    val listed = paging.lastRows.size
    val parts = listOfNotNull(
        left.notVerified.takeIf { it > 0 }?.let { "${entries(it)} could not be verified" },
        left.contradicted.takeIf { it > 0 }?.let {
            "${entries(it)} ${if (it == 1) "contradicts" else "contradict"} the chain"
        },
        listed.takeIf { it > 0 }?.let {
            "${entries(it)} ${if (it == 1) "was listed already or is" else "were listed already or are"} not listed"
        },
    )
    val what = if (parts.isEmpty()) {
        "held no entry to list"
    } else {
        "added no row: " + parts.joinToString(", ")
    }
    return "The last page of the addr1 index $what. The next is read only when you ask, since each page fetches " +
        "old blocks from archive peers."
}

/** [pausedText] in simple mode's words. */
private fun simplePausedText(paging: IndexPaging): String {
    val left = paging.lastLeftOut
    val listed = paging.lastRows.size
    val parts = listOfNotNull(
        left.notVerified.takeIf { it > 0 }?.let { "${entries(it)} couldn't be confirmed" },
        left.contradicted.takeIf { it > 0 }?.let { "${entries(it)} the chain shows to be wrong" },
        listed.takeIf { it > 0 }?.let { "${entries(it)} ${if (it == 1) "is" else "are"} shown already" },
    )
    val what = if (parts.isEmpty()) "held nothing to show" else "added nothing here: " + parts.joinToString(", ")
    return "The last part of the shared index $what. More is checked only when you ask, since each part means a " +
        "lot of downloading."
}

/** Simple mode's words while more of the index is read. */
private const val CHECKING_MORE = "Checking more of the shared index…"

/** Every range [coverage] leaves out, with block numbers: where it starts, the head gap, the gaps inside it. */
private fun coverageLines(coverage: AddressIndexCoverage): List<String> = buildList {
    val covered = coverage.covered
    if (covered == null) {
        add("It covers no block yet" + (coverage.tailGap?.let { ": ${range(it)} are not in it." } ?: "."))
    } else if (covered.first == 0L) {
        add("It covers the blocks from the first to ${grouped(covered.last)}.")
    } else {
        add(
            "It covers blocks ${grouped(covered.first)} to ${grouped(covered.last)}: older blocks are not in it.",
        )
    }
    coverage.headGap?.let { gap ->
        add(
            gap.toBlock?.let { "It has not reached the newest blocks yet, ${range(gap.fromBlock..it)}." }
                ?: "It has not reached the blocks from ${grouped(gap.fromBlock)} on yet.",
        )
    }
    if (coverage.gaps.isNotEmpty()) {
        val shown = coverage.gaps.take(MAX_GAPS_NAMED).joinToString(", ") { range(it) }
        val more = coverage.gaps.size - MAX_GAPS_NAMED
        add(
            "Inside it, ${if (coverage.gaps.size == 1) "a range" else "ranges"} could not be read: $shown" +
                (if (more > 0) " and $more more" else "") + ". Entries there may be missing.",
        )
    }
}

/** [coverageLines] in simple mode's words, with no block number. */
private fun simpleCoverageLines(coverage: AddressIndexCoverage): List<String> = buildList {
    if (coverage.covered == null) add("It holds nothing yet.")
    if (coverage.headGap != null) add("It hasn't reached the newest activity yet.")
    if (coverage.gaps.isNotEmpty()) add("Parts of it couldn't be read, so some activity may be missing.")
}

/** Blocks [range], as a line says them: "block 9", "blocks 9 to 12". */
private fun range(range: LongRange): String = if (range.first == range.last) {
    "block ${grouped(range.first)}"
} else {
    "blocks ${grouped(range.first)} to ${grouped(range.last)}"
}

/** [n] entries, in words: "1 entry", "3 entries". */
private fun entries(n: Int): String = if (n == 1) "1 entry" else "$n entries"

/** At most this many inner gaps are named; the rest are counted. */
private const val MAX_GAPS_NAMED = 3

/**
 * Why the addr1 index was not read or checked, as the Activity and a row's detail say it (every reason stated, never
 * an empty list): [network] is the account's.
 */
internal fun indexRefusalText(reason: AddressIndexRefusal, network: Network): String = when (reason) {
    AddressIndexRefusal.NotInThisBuild ->
        "This build's engine has no Swarm reader, so nothing comes from the addr1 index here."
    AddressIndexRefusal.SettingOff ->
        "The engine reads no addr1 index: the experimental setting is off. Settings → Experimental turns it on."
    AddressIndexRefusal.TorRouting ->
        "Tor routing is on, and the addr1 index is not read over Tor: nothing comes from it while Tor is on."
    is AddressIndexRefusal.NetworkNotServed ->
        "The addr1 index is not read for ${reason.network.displayName} here."
    is AddressIndexRefusal.CheckpointAlarm ->
        "The engine's checkpoint table and the beacon chain disagree, so nothing that rests on the table is shown, " +
            "the index's rows among it. The engine says: ${reason.text}"
    AddressIndexRefusal.StaleAnchor ->
        "${network.displayName}'s light client is parked on a checkpoint too old to catch up from, so no entry " +
            "can be checked. Accounts shows the ways on."
    AddressIndexRefusal.NoEngine ->
        "No light client runs for ${network.displayName} on this device, so no entry of the index can be checked."
    is AddressIndexRefusal.WrongImporter ->
        "The index read is signed by ${Eip55.format(reason.named)}, not by the importer pinned in this release, " +
            "${Eip55.format(reason.pinned)}: none of its entries is shown, and no more is read."
    is AddressIndexRefusal.Rejected ->
        "The engine refused the request (${reason.reason}). That is a bug in Punicus or the engine: nothing more " +
            "comes from the index until it is fixed."
    is AddressIndexRefusal.NotNow -> when (reason.reason) {
        ReadUnavailable.SYNCING ->
            "The light client is still catching up, so the index's entries cannot be checked yet."
        ReadUnavailable.NETWORK_OFF -> "${network.displayName}'s light client is not running yet."
        else -> "Swarm or the peers did not deliver what the index or its check needs right now."
    }
}

/** [indexRefusalText] in simple mode's words: no block number, no quote of the engine. */
internal fun indexRefusalSimpleText(reason: AddressIndexRefusal, network: Network): String = when (reason) {
    AddressIndexRefusal.NotInThisBuild -> "This version can't read the shared index here."
    AddressIndexRefusal.SettingOff -> "The shared index is off. Settings → Experimental turns it on."
    AddressIndexRefusal.TorRouting -> "Tor is on, and the shared index isn't read over Tor."
    is AddressIndexRefusal.NetworkNotServed -> "The shared index isn't read for ${reason.network.displayName} here."
    is AddressIndexRefusal.CheckpointAlarm -> "Older activity from the shared index can't be shown: the checkpoints " +
        "Punicus relies on disagree with the network."
    AddressIndexRefusal.StaleAnchor -> "${network.displayName} can't be checked right now. Accounts shows the ways on."
    AddressIndexRefusal.NoEngine ->
        "${network.displayName} isn't running on this device, so the shared index can't be checked."
    is AddressIndexRefusal.WrongImporter ->
        "The shared index found isn't the one this version trusts, so none of it is shown."
    is AddressIndexRefusal.Rejected -> "Something went wrong reading the shared index. Nothing more comes from it."
    is AddressIndexRefusal.NotNow -> when (reason.reason) {
        ReadUnavailable.SYNCING -> "Still catching up, so the shared index can't be checked yet."
        ReadUnavailable.NETWORK_OFF -> "${network.displayName} isn't running yet."
        else -> "The shared index couldn't be read right now."
    }
}

/** [roles] in words, in the order the index records them: "sender, a log's topic". */
internal fun rolesText(roles: Set<AddressIndexRole>): String =
    AddressIndexRole.entries.filter { it in roles }.joinToString(", ") { role ->
        when (role) {
            AddressIndexRole.FROM -> "sender"
            AddressIndexRole.TO -> "recipient"
            AddressIndexRole.EMITTER -> "emitter of a log"
            AddressIndexRole.TOPIC -> "a log's topic"
            AddressIndexRole.WORD -> "in its input or a log's data"
            AddressIndexRole.TRACE -> "reached by an inner call"
            AddressIndexRole.BLOCK -> "named by the block (fee recipient, withdrawal or uncle)"
            AddressIndexRole.AUTH -> "an EIP-7702 authority"
        }
    }
