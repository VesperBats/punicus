package eth.serotine.ui.activity

import eth.serotine.domain.model.AddressIndexRole

/**
 * Everything an account's Activity renders, the Activity tab of its detail (docs/wallet-ux.md,
 * screen 25; issue #85): the transactions this device sent for it and, for a Safe, its own events
 * on chain, by date, newest first unless the user turned the order round ([ordered]). Display-ready.
 */
sealed interface ActivityUiState {

    /** Nothing read yet. */
    data object Loading : ActivityUiState

    /**
     * The [rows], by date, newest first as built (none: nothing listed yet, which [empty] says),
     * and [coverage], what the list covers and what it leaves out; [simpleCoverage], the same in
     * simple mode's words, with no block number (docs/simple-ui.md, H5: "Activity since you added
     * this account"). Above them, the swaps whose next step [waiting] for the user, each with
     * Continue, in the same place whichever way the rows run; and first of all, what the account has
     * [queued] in lenient mode ("Waiting to send", "Needs your signature again";
     * docs/lenient-mode.md, *Where it shows*), each opening its status. [index] is what the addr1 index adds, on the
     * desktop with its experimental setting on (owner ruling, 2026-11-21, #334): its lines, and its footer below the
     * rows; null where it adds nothing (the setting off, or not offered here). Its verified rows are among [rows].
     */
    data class Shown(
        val rows: List<ActivityRow>,
        val coverage: String,
        val empty: String = "Nothing sent from this device yet.",
        val simpleCoverage: String = coverage,
        val waiting: List<WaitingStep> = emptyList(),
        val queued: List<ActivityRow> = emptyList(),
        val index: IndexSection? = null,
    ) : ActivityUiState

    /** The history cannot be read right now, for the reason [message] gives. */
    data class Unavailable(val message: String) : ActivityUiState
}

/**
 * One transaction sent from this device, or one event of a Safe on chain: its [key] in the list,
 * what it did ([title]: "Sent 0.5 ETH", "Received 0.1 ETH"), who or what else it concerns
 * ([detail]: "To Bob · 0x12…5678", "Treasury · Safe nonce 4"), the EIP-55 address its blockie is of
 * ([about]), when it happened ([time]: when a peer took a sent transaction, or when an event's block
 * was produced as its verified header says, and the block alone, "Block 9,415,302", from an engine
 * that does not report the time), and how it ended as this device saw it ([status], "In block
 * 9,412,118", and its [state]). [opens] is what a tap opens the detail of (docs/wallet-ux.md,
 * screen 26): the transaction, and for an event its log. Simple mode says no block number
 * (docs/simple-ui.md, H5): [simpleTime] is when, where that is known (null for an event dated by its
 * block alone), and [simpleStatus] how it ended in words ("Confirmed", "Final", "Failed"), null where
 * that is [status] itself: so a copy that changes [status] (a caution, a warning) never leaves an
 * older simple status behind.
 */
data class ActivityRow(
    val key: String,
    val title: String,
    val detail: String,
    val about: String,
    val time: String,
    val status: String,
    val state: ActivityState,
    val opens: ActivityKey,
    val simpleTime: String? = time,
    val simpleStatus: String? = null,
)

/**
 * Which row of an account's Activity a detail is of: the transaction [hash] (`0x` and 64 lower-case
 * hex digits), and for an event of the log index the [logIndex] of its log in the block; null for a
 * transaction sent from this device. For a CoW order signed here ([cowOrder]), [hash] is its UID,
 * and a tap opens the order's status instead; for a transfer queued in lenient mode ([queued]), its
 * id, and a tap opens its status (docs/wallet-ux.md, screen 9).
 */
data class ActivityKey(
    val hash: String,
    val logIndex: Int? = null,
    val cowOrder: Boolean = false,
    val queued: Boolean = false,
    val index: IndexEntryKey? = null,
)

/**
 * A row of the addr1 index ([ActivityKey.index]), by the entry it shows: its [block], its [slot] there (0 for the block
 * itself, else the transaction's index plus one) and the [roles] the index names the account in, which its detail
 * checks again (docs/wallet-ux.md, screen 26). Its key's [ActivityKey.hash] is the transaction's hash, empty for an
 * entry about the block itself.
 */
data class IndexEntryKey(val block: Long, val slot: Int, val roles: Set<AddressIndexRole>)

/**
 * What the addr1 index adds to an account's Activity (owner ruling, 2026-11-21, #334; the owner's decisions of that
 * day on #335): display-ready, in the words of Detailed view, and in simple mode's ([simpleLines], with no block
 * number).
 * - [lines]: what it is and what it rests on, the importer key named in full, saying that entries may be missing;
 *   then every range it does not cover: where its coverage starts, the newest blocks it has not reached yet (the
 *   head gap), the gaps inside it. Empty while its first page is read.
 * - [leftOut]: how many of its entries were left out because they could not be verified; null for none.
 * - [contradicted]: how many the chain contradicts, apart, as a warning against the importer's key; null for none.
 * - [footer]: what follows its rows, at the end of the list: loading, more to load, the end of its coverage, or why
 *   nothing (more) comes from it.
 * - [footerFirst]: the footer goes above the rows, when they run oldest first, so that what loads next stays where
 *   the older rows are.
 * - [simpleLeftOut], [simpleContradicted]: the two counts in simple mode's words.
 */
data class IndexSection(
    val lines: List<String>,
    val footer: IndexFooter,
    val leftOut: String? = null,
    val contradicted: String? = null,
    val simpleLines: List<String> = lines,
    val footerFirst: Boolean = false,
    val simpleLeftOut: String? = leftOut,
    val simpleContradicted: String? = contradicted,
)

/** What follows the addr1 index's rows in an account's Activity. */
sealed interface IndexFooter {
    /**
     * A page is being read and checked: [text] says so ([simpleText] in simple mode), in a footer that holds its
     * room.
     */
    data class Loading(val text: String, val simpleText: String = text) : IndexFooter

    /** More entries lie below: the next page is read as the user nears the end of the list. */
    data object More : IndexFooter

    /**
     * More entries lie below, but the last page added no row to the list: the next is read only on the user's tap
     * (Load older entries), never by itself; [text] says what that page left out ([simpleText] in simple mode). A tap
     * that adds rows makes the list read on by itself again.
     */
    data class Paused(val text: String, val simpleText: String = text) : IndexFooter

    /** The end of what the index covers: [text], "The addr1 index starts at block …". */
    data class End(val text: String, val simpleText: String = text) : IndexFooter

    /**
     * Nothing (more) is read from it, for the reason [text] gives, never an empty list; [retry] when asking again
     * may answer (Try again), [warning] when the reason is a warning (the checkpoint table's alarm); [simpleText] in
     * simple mode's words, with no block number and no quote of the engine.
     */
    data class Refused(
        val text: String,
        val retry: Boolean = false,
        val warning: Boolean = false,
        val simpleText: String = text,
    ) : IndexFooter
}

/**
 * A swap whose next step waits for the user (docs/simple-ui.md, H5): what it swaps ([title], "Swap 40
 * USDC → ETH"), where it stands ([message], "Step 1 done. Step 2 is waiting for you.", said from the
 * approval's verified receipt and never claimed without one), and the token it sells ([sell], its
 * address), which Continue opens Swap with and Dismiss lets go of. In words, with no block number, in
 * both views.
 */
data class WaitingStep(val title: String, val message: String, val sell: String)

/** How a sent transaction ended, as an [ActivityRow] marks it. */
enum class ActivityState {
    /** In a block, its receipt verified, and it succeeded (finalized or not yet). */
    DONE,

    /** No block seen to include it yet. */
    WAITING,

    /** In a block, and it reverted: its fee paid, nothing else done. */
    FAILED,

    /** No peer confirmed its broadcast, and no block seen to include it. */
    UNCONFIRMED,

    /** Its nonce was used while the app was not watching: by it, unless another took its place. */
    UNKNOWN,

    /** Done on chain, and what it did can move a Safe's funds without its owners. */
    WARNING,

    /**
     * Done on chain, received from an address the user has not sent to and that is none of their
     * accounts: it may be a look-alike, planted for them to copy.
     */
    CAUTION,
}

/**
 * Which way an Activity's rows run (docs/wallet-ux.md, screen 25): the top bar's menu offers the
 * other one while the tab is shown.
 */
enum class ActivityOrder {
    /** The newest row on top: the default. */
    NEWEST_FIRST,

    /** The oldest row on top. */
    OLDEST_FIRST,
}

/**
 * This Activity with its rows running the way [order] says: as built for [ActivityOrder.NEWEST_FIRST],
 * turned round for [ActivityOrder.OLDEST_FIRST]. The cards of the swaps waiting for a step stay on top.
 */
fun ActivityUiState.ordered(order: ActivityOrder): ActivityUiState = when {
    order == ActivityOrder.NEWEST_FIRST || this !is ActivityUiState.Shown -> this
    else -> copy(rows = rows.asReversed(), index = index?.copy(footerFirst = true))
}

/**
 * This Activity in simple mode's words (docs/simple-ui.md, H5): each row's [ActivityRow.simpleTime]
 * (none for an event dated only by its block) and [ActivityRow.simpleStatus] in place of its time
 * and status, and [ActivityUiState.Shown.simpleCoverage] in place of the coverage.
 */
internal fun ActivityUiState.simple(): ActivityUiState = when (this) {
    is ActivityUiState.Shown -> copy(
        rows = rows.map { it.copy(time = it.simpleTime.orEmpty(), status = it.simpleStatus ?: it.status) },
        queued = queued.map { it.copy(time = it.simpleTime.orEmpty(), status = it.simpleStatus ?: it.status) },
        coverage = simpleCoverage,
        index = index?.let { section ->
            section.copy(
                lines = section.simpleLines,
                leftOut = section.simpleLeftOut,
                contradicted = section.simpleContradicted,
                footer = when (val footer = section.footer) {
                    is IndexFooter.End -> IndexFooter.End(footer.simpleText)
                    is IndexFooter.Loading -> IndexFooter.Loading(footer.simpleText)
                    is IndexFooter.Paused -> IndexFooter.Paused(footer.simpleText)
                    is IndexFooter.Refused -> footer.copy(text = footer.simpleText)
                    IndexFooter.More -> footer
                },
            )
        },
    )
    else -> this
}
