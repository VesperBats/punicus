package eth.serotine.domain.model

import kotlin.jvm.JvmInline

/**
 * Why an address is named in a block, as the addr1 address index records it (issue #334; Myotis #899): the roles an
 * [AddressIndexEntry] carries. The engine checks every role against verified chain data but [TRACE], which rests on
 * the importer's word alone (`.claude/rules/data-sources.md`, *The history may also take the addr1 index from
 * Swarm*).
 */
enum class AddressIndexRole {
    /** The transaction's sender. */
    FROM,

    /** The transaction's recipient. */
    TO,

    /** The contract that emitted one of the transaction's logs. */
    EMITTER,

    /** One of the indexed topics of the transaction's logs. */
    TOPIC,

    /** A word of the transaction's input after the selector, an EIP-7702 code address, or a word of a log's data. */
    WORD,

    /** Reached by a call inside the transaction, or a block's reward author, as a trace says: never verified. */
    TRACE,

    /** The block itself names it, at slot 0: its fee recipient, a withdrawal, an uncle. */
    BLOCK,

    /** The authority of one of the transaction's EIP-7702 authorizations. */
    AUTH,
}

/**
 * One entry of an address's history as the addr1 index holds it: a candidate, never shown as such. The address is
 * named in block [block], at [slot] (0 for the block itself, else the transaction's index in the block plus one),
 * in the [roles] given. It says nothing verified; only an [AddressIndexVerdict.Verified] of it does. Its state on
 * the engine's page (a verdict the engine kept) is not part of it: a verdict is only ever taken from the check.
 */
data class AddressIndexEntry(val block: Long, val slot: Int, val roles: Set<AddressIndexRole>) {
    init {
        require(block >= 0) { "a block number is never negative" }
        require(slot >= 0) { "a slot is 0 for the block, else a transaction's index plus one" }
        require(roles.isNotEmpty()) { "an entry names the address in one role at least" }
    }

    /** The transaction's index in its block, or null for an entry about the block itself (slot 0). */
    val transactionIndex: Int?
        get() = if (slot == 0) null else slot - 1

    /**
     * Whether its one role is [AddressIndexRole.TRACE]: the engine never verifies such an entry, so it is never a row
     * of the history.
     */
    val traceOnly: Boolean
        get() = roles == setOf(AddressIndexRole.TRACE)
}

/**
 * Where a page of an address's addr1 history ends, as the engine named it: passed back to read the page below it.
 * Opaque: nothing here reads what it holds; only its engine does.
 */
@JvmInline
value class AddressIndexCursor(val token: String) {
    init {
        require(token.isNotBlank()) { "a cursor names where a page ended" }
    }

    override fun toString(): String = token
}

/**
 * The newest blocks the index has not reached yet: from [fromBlock] (the block after the last it covers) up to
 * [toBlock], the verified head or the top of the range asked, whichever is lower; [toBlock] null while the engine
 * has no verified head to bound it, when the gap is open-ended.
 */
data class AddressIndexHeadGap(val fromBlock: Long, val toBlock: Long?) {
    init {
        require(fromBlock >= 0) { "a block number is never negative" }
        require(toBlock == null || toBlock >= fromBlock) { "a gap from block $fromBlock to $toBlock" }
    }
}

/**
 * What a page of the addr1 index covers, and what it rests on. The index is signed by [importer], the key pinned in
 * the release (owner's decision, 2026-11-21, on #335): that key alone vouches that no entry is missing, so entries
 * may be missing, and a list shown from it names this key and says so. Its index was read at head update
 * [headUpdate] and covers the blocks [covered], null when it covers none yet (an index just started); the page
 * names no range for it that the index does not hold. Of the range asked, these are not covered, each clipped to
 * that range and null or left out when nothing of it remains: [headGap], the newest blocks the index has not
 * reached yet; [tailGap], the blocks below where its coverage starts; and [gaps], the slices inside its coverage the
 * read could not get. A range asked that the index does not reach at all is all gap: the head gap above it, the
 * tail gap below it, or both when it covers nothing. A range not covered is never an empty one: none of them says
 * the address is absent there.
 */
data class AddressIndexCoverage(
    val importer: Address,
    val headUpdate: Long,
    val covered: LongRange?,
    val headGap: AddressIndexHeadGap?,
    val tailGap: LongRange?,
    val gaps: List<LongRange>,
) {
    init {
        require(headUpdate >= 0) { "a head update is counted from 0" }
        require(covered == null || (!covered.isEmpty() && covered.first >= 0)) { "coverage of one block at least" }
        require(tailGap == null || (!tailGap.isEmpty() && tailGap.first >= 0)) { "a tail gap of one block at least" }
        require(headGap == null || tailGap == null || tailGap.last < headGap.fromBlock) {
            "the tail gap lies below the head gap"
        }
        if (covered != null) {
            require(headGap == null || headGap.fromBlock > covered.last) { "the head gap lies above the coverage" }
            require(tailGap == null || tailGap.last < covered.first) { "the tail gap lies below the coverage" }
        }
        require(gaps.all { !it.isEmpty() && covered != null && it.first >= covered.first && it.last <= covered.last }) {
            "an inner gap lies inside the coverage"
        }
    }
}

/**
 * What a verified transaction entry is, as the engine read it from the verified block: the transaction [hash]
 * (`0x` and 64 lower-case hex digits), sent by [from] to [to] (null for a contract creation) with [value], and
 * [transfer], the first ERC-20 or ERC-721 `Transfer` among its logs that the address is a party of (null: none),
 * never the protocol's log of an ETH transfer (EIP-7708, [TokenTransfers.ETH]): the ether it would move is [value].
 * [succeeded] is its verified receipt's status: true when it succeeded, false when it reverted, and null when it is
 * not known. **The engine does not report it yet** (Myotis's per-entry check carries the hash, from, to, value and
 * transfer only), so it is null today: [value] is then a fact of the transaction, never a claim that it moved, since a
 * reverted transaction keeps its value field and moves nothing.
 */
data class AddressIndexTransaction(
    val hash: String,
    val from: Address,
    val to: Address?,
    val value: Wei,
    val transfer: TokenTransfer?,
    val succeeded: Boolean? = null,
) {
    init {
        require(isHash(hash)) { "a transaction hash is 0x and 64 lower-case hex digits" }
        require(transfer?.isEth != true) { "the protocol's log of an ETH transfer (EIP-7708) is no token's transfer" }
    }
}

/**
 * A row of an address's history from the addr1 index: an [entry] the engine verified against the verified block
 * [blockHash] (`0x` and 64 lower-case hex digits), which the beacon chain had finalized when [final] (else a
 * reorganisation may still replace it). Every role of the entry is in [confirmed], proven on chain, at least one,
 * or in [unchecked], which holds [AddressIndexRole.TRACE] alone, the one role resting on the importer's word; none
 * was contradicted, and no other role is left unchecked. [transaction] is what the engine read of its transaction,
 * null for an entry about the block itself (slot 0).
 */
data class AddressIndexRow(
    val entry: AddressIndexEntry,
    val blockHash: String,
    val final: Boolean,
    val confirmed: Set<AddressIndexRole>,
    val unchecked: Set<AddressIndexRole>,
    val transaction: AddressIndexTransaction?,
) {
    init {
        require(isHash(blockHash)) { "a block hash is 0x and 64 lower-case hex digits" }
        require(confirmed.isNotEmpty()) { "a verified entry has one role proven at least" }
        require(AddressIndexRole.TRACE !in confirmed) { "a trace is never proven" }
        require(unchecked.all { it == AddressIndexRole.TRACE }) { "only a trace rests on the importer's word" }
        require(confirmed + unchecked == entry.roles) { "every role of the entry is proven, or is its trace" }
        require((transaction == null) == (entry.slot == 0)) { "a transaction for a transaction entry, only for one" }
    }
}

/** Whether [text] is a 32-byte hash as the engine writes one: `0x` and 64 lower-case hex digits. */
private fun isHash(text: String): Boolean = HASH.matches(text)

private val HASH = Regex("^0x[0-9a-f]{64}$")

/**
 * What the engine's check of one [entry] found against verified chain data. Only [Verified] is ever shown as a row;
 * every other verdict leaves its entry out of the history, counted (`.claude/rules/data-sources.md`).
 */
sealed interface AddressIndexVerdict {

    /** The entry checked. */
    val entry: AddressIndexEntry

    /**
     * Proven on chain: [row], as the history shows it. An engine verdict that leaves a role other than
     * [AddressIndexRole.TRACE] unchecked is not one ([AddressIndexRow]'s rule): the adapter reports it as
     * [Unverified].
     */
    data class Verified(val row: AddressIndexRow) : AddressIndexVerdict {
        override val entry: AddressIndexEntry
            get() = row.entry
    }

    /**
     * The chain says otherwise: in the verified block [blockHash] (`0x` and 64 lower-case hex digits; finalized when
     * [final]), its roles [contradicted] are not what the index claims. The importer's key signed something false: a
     * warning against that key (owner's decision, 2026-11-21, on #335), counted apart from what could not be verified.
     */
    data class Contradicted(
        override val entry: AddressIndexEntry,
        val blockHash: String,
        val final: Boolean,
        val contradicted: Set<AddressIndexRole>,
    ) : AddressIndexVerdict {
        init {
            require(isHash(blockHash)) { "a block hash is 0x and 64 lower-case hex digits" }
            require(contradicted.isNotEmpty()) { "a contradiction names the roles it contradicts" }
            require(entry.roles.containsAll(contradicted)) { "only the entry's own roles are contradicted" }
            require(AddressIndexRole.TRACE !in contradicted) { "a trace is never checked, so never contradicted" }
        }
    }

    /**
     * It cannot be verified, and asking again does not help: no role of it can be checked (a [AddressIndexRole.TRACE]
     * alone), a role other than the trace was left unchecked, or the engine cannot anchor its block (below what its
     * light client's window and checkpoint table reach); [reason] is the engine's words, null when it gave none. The
     * checkpoint table's alarm is never one: it refuses the whole check
     * ([eth.serotine.domain.repository.AddressIndexRefusal.CheckpointAlarm]).
     */
    data class Unverified(override val entry: AddressIndexEntry, val reason: String? = null) : AddressIndexVerdict

    /**
     * Not checked this time: its block or receipts could not be read now, for [reason] (the engine's words). Asking
     * again may verify it.
     */
    data class Unavailable(override val entry: AddressIndexEntry, val reason: String) : AddressIndexVerdict
}
