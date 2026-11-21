package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexPage
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.AddressIndexSetting
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Loads the next page of an account's history from the addr1 index, for its *Activity* list alone (issue #334; owner
 * ruling, 2026-11-21, and the owner's decisions of that day on #335, `.claude/rules/data-sources.md`, *The history
 * may also take the addr1 index from Swarm*): it reads one page of candidates from [history], has the engine check
 * them, and gives back the verified ones only, as rows, newest first, with what the index covers, the importer key
 * the list rests on, and how many entries it left out.
 *
 * - **The pinned key only.** A page whose index is signed by any key but [pinnedImporter], the key pinned in the
 *   release, is refused ([AddressIndexRefusal.WrongImporter]) before anything of it is checked.
 * - **Verified rows only.** An entry becomes a row only on an [AddressIndexVerdict.Verified] of it. Every other
 *   entry is left out and counted ([AddressIndexLeftOut]); a contradicted one apart, a warning against the key.
 * - **TRACE-only entries are not sent to the check**: the engine never verifies one, so asking would only spend a
 *   slot of the check. They are counted apart ([AddressIndexLeftOut.traceOnly]), among the entries that could not
 *   be verified.
 * - **The check runs in chunks** of at most [AddressIndexHistory.MAX_CHECKED] entries, one after the other, in page
 *   order. A verdict is matched to the entry asked by block and slot; one whose roles are not the roles asked, or
 *   two different verdicts for one entry, count as no verdict. An entry that comes back
 *   [AddressIndexVerdict.Unavailable], or with no verdict, is asked again, up to [attempts] checks in all,
 *   [retryPause] apart. One still unavailable after that is left out and counted apart
 *   ([AddressIndexLeftOut.unavailable]), since a later load may verify it.
 * - **A passing refusal of a check** ([AddressIndexRefusal.NotNow]) is asked again like an unavailable entry; still
 *   refused at the last attempt, that chunk's entries count as unavailable, and the rows already verified and the
 *   page's continuation are kept.
 * - **A lasting refusal is said, never an empty list.** A page refused for any reason refuses the load with it
 *   ([AddressIndexLoad.Refused]). So does a check refused for a lasting reason (not in this build, the setting off,
 *   Tor, a network not served, the checkpoint table's alarm, a stale checkpoint, no engine), since it holds for
 *   every chunk, and the alarm withdraws the trust the rows rest on. [AddressIndexRefusal.Rejected] refuses it too:
 *   the engine refusing an argument this port checked is a bug, and no part of the page is shown over it.
 * - **One load takes at most [budget]**, the page's read and retry pauses included. A page not read by then refuses
 *   the load as passing ([AddressIndexRefusal.NotNow], [ReadUnavailable.UNPROVEN]: Swarm did not deliver it); past
 *   the read, the entries no check answered by then count as unavailable, the rows verified so far are kept, and so
 *   is the page's continuation.
 *
 * Deterministic: everything it knows comes from [history], called from the caller's coroutine only, one check at a
 * time (the engine bounds each at 90 seconds).
 */
class LoadAddressIndexPage(
    private val history: AddressIndexHistory,
    /** The importer key pinned in the release: the one key whose index the list may rest on. */
    private val pinnedImporter: Address,
    /** How many checks an entry gets at most, the first one included: 1 or more. */
    private val attempts: Int = DEFAULT_ATTEMPTS,
    /** How long to wait before asking again for the entries no check answered. */
    private val retryPause: Duration = DEFAULT_RETRY_PAUSE,
    /** The longest one load may take: the page's read, its checks and the retry pauses between them. */
    private val budget: Duration = DEFAULT_BUDGET,
) {
    init {
        require(attempts >= 1) { "an entry is checked once at least" }
        require(!retryPause.isNegative()) { "a pause is never negative" }
        require(budget.isPositive()) { "a load has time to check" }
    }

    /**
     * The page of [account]'s addr1 history below [after] (the [AddressIndexLoad.Loaded.next] of the page before;
     * null: the newest page), of at most [limit] entries (1 to [AddressIndexHistory.MAX_PAGE]), read on
     * [account]'s network and checked there. [after] of another account is the caller's error and throws.
     */
    suspend operator fun invoke(
        account: AccountId,
        after: AddressIndexContinuation? = null,
        limit: Int = AddressIndexHistory.DEFAULT_PAGE,
    ): AddressIndexLoad {
        require(after == null || after.account == account) { "a continuation of another account's history" }
        val query = AddressIndexQuery(cursor = after?.cursor, limit = limit)
        var readPage: AddressIndexPage? = null
        var refused: AddressIndexRefusal? = null
        val verdicts = mutableMapOf<AddressIndexEntry, AddressIndexVerdict>()
        // One budget for the read and the checks. Past it the check stops where it is: what it had verified stays,
        // the rest is unavailable. A page not read by then is a passing refusal.
        withTimeoutOrNull(budget) {
            val read = when (val reading = history.page(account.network, account.address, query)) {
                is AddressIndexReading.Refused -> {
                    refused = reading.reason
                    return@withTimeoutOrNull
                }
                is AddressIndexReading.Page -> reading.page
            }
            readPage = read
            val importer = read.coverage.importer
            if (importer != pinnedImporter) {
                refused = AddressIndexRefusal.WrongImporter(pinnedImporter, importer)
                return@withTimeoutOrNull
            }
            val outcome = checkAll(account, read.entries.filterNot { it.traceOnly }, verdicts)
            if (outcome is Lasting) refused = outcome.reason
        }
        refused?.let { return AddressIndexLoad.Refused(it) }
        val page = readPage ?: return AddressIndexLoad.Refused(AddressIndexRefusal.NotNow(ReadUnavailable.UNPROVEN))
        val checked = page.entries.filterNot { it.traceOnly }
        val rows = page.entries.mapNotNull { (verdicts[it] as? AddressIndexVerdict.Verified)?.row }
        val leftOut = AddressIndexLeftOut(
            unverified = verdicts.values.count { it is AddressIndexVerdict.Unverified },
            contradicted = verdicts.values.count { it is AddressIndexVerdict.Contradicted },
            traceOnly = page.entries.size - checked.size,
            unavailable = checked.count { it !in verdicts },
        )
        return AddressIndexLoad.Loaded(
            account = account,
            rows = rows,
            coverage = page.coverage,
            leftOut = leftOut,
            next = page.next?.let { AddressIndexContinuation(account, it) },
        )
    }

    /**
     * Checks [entries], putting into [verdicts] the lasting verdict of each that gets one: all but those still
     * unavailable after [attempts] checks. [Lasting] when a check is refused for a lasting reason, else [Done].
     */
    private suspend fun checkAll(
        account: AccountId,
        entries: List<AddressIndexEntry>,
        verdicts: MutableMap<AddressIndexEntry, AddressIndexVerdict>,
    ): Outcome {
        var pending = entries
        for (attempt in 1..attempts) {
            if (pending.isEmpty()) break
            if (attempt > 1) delay(retryPause)
            for (chunk in pending.chunked(AddressIndexHistory.MAX_CHECKED)) {
                when (val answer = history.verify(account.network, account.address, chunk)) {
                    // A passing refusal leaves the chunk pending: asked again, or unavailable after the last attempt.
                    is AddressIndexCheck.Refused -> if (!answer.reason.retryable) return Lasting(answer.reason)
                    is AddressIndexCheck.Verdicts -> chunk.forEach { entry ->
                        verdictOf(entry, answer.verdicts)?.let { verdicts[entry] = it }
                    }
                }
            }
            pending = pending.filterNot { it in verdicts }
        }
        return Done
    }

    /**
     * The one lasting verdict [answer] gives [entry]: matched by block and slot, with the roles asked; null for none,
     * for one whose roles differ, for two that differ, and for [AddressIndexVerdict.Unavailable].
     */
    private fun verdictOf(entry: AddressIndexEntry, answer: List<AddressIndexVerdict>): AddressIndexVerdict? =
        answer.filter { it.entry.block == entry.block && it.entry.slot == entry.slot }
            .distinct()
            .singleOrNull()
            ?.takeIf { it.entry.roles == entry.roles && it !is AddressIndexVerdict.Unavailable }

    /** How [checkAll] ended: [Done], or refused for a [Lasting] reason. */
    private sealed interface Outcome
    private data object Done : Outcome
    private data class Lasting(val reason: AddressIndexRefusal) : Outcome

    private companion object {
        /** A first check and two more for what it could not read. */
        const val DEFAULT_ATTEMPTS = 3

        /** Long enough for the engine to try other peers for a block or its receipts. */
        val DEFAULT_RETRY_PAUSE = 2.seconds

        /** About three of the engine's longest checks, with their pauses: a page of 150 entries at its slowest. */
        val DEFAULT_BUDGET = 5.minutes
    }
}

/**
 * Where [account]'s addr1 history goes on: below [cursor]. Made by [LoadAddressIndexPage] only, so it is always a
 * page's own.
 */
@ConsistentCopyVisibility
data class AddressIndexContinuation internal constructor(val account: AccountId, val cursor: AddressIndexCursor)

/**
 * The entries of a page left out of the history, by why (`.claude/rules/data-sources.md`: the list says how many it
 * could not verify, and, separately, how many the chain contradicts). The counts are disjoint: [contradicted], the
 * chain says otherwise, a warning against the importer's key; and those that could not be verified ([notVerified]):
 * [unverified], the engine cannot verify them and asking again does not help; [traceOnly], their one role is
 * `TRACE`, which the engine never verifies, so they were not sent to the check; [unavailable], no check read their
 * block this time (no peer served it, the light client not ready, the load's time spent), and a later load may
 * verify them.
 */
data class AddressIndexLeftOut(
    val unverified: Int = 0,
    val contradicted: Int = 0,
    val traceOnly: Int = 0,
    val unavailable: Int = 0,
) {
    init {
        require(unverified >= 0 && contradicted >= 0 && traceOnly >= 0 && unavailable >= 0) { "a count of entries" }
    }

    /** The entries that could not be verified: every one left out but the contradicted. */
    val notVerified: Int
        get() = unverified + traceOnly + unavailable

    /** Every entry left out, whatever the reason. */
    val total: Int
        get() = notVerified + contradicted

    /** These and [other] together: what a list of several pages left out. */
    operator fun plus(other: AddressIndexLeftOut): AddressIndexLeftOut = AddressIndexLeftOut(
        unverified + other.unverified,
        contradicted + other.contradicted,
        traceOnly + other.traceOnly,
        unavailable + other.unavailable,
    )
}

/** What [LoadAddressIndexPage] gives back. */
sealed interface AddressIndexLoad {

    /**
     * One page of [account]'s history: its verified [rows], newest first, and nothing else of it; what the index
     * covers and the importer key it rests on ([coverage]), which the list names, saying that entries may be
     * missing; the entries it [leftOut]; and [next], where the history goes on, null at the end of what the index
     * covers.
     */
    data class Loaded(
        val account: AccountId,
        val rows: List<AddressIndexRow>,
        val coverage: AddressIndexCoverage,
        val leftOut: AddressIndexLeftOut,
        val next: AddressIndexContinuation?,
    ) : AddressIndexLoad {
        /** Whether this page reaches the end of what the index covers: no page lies below it. */
        val endOfCoverage: Boolean
            get() = next == null
    }

    /** Nothing loaded, for [reason], which the screen states. */
    data class Refused(val reason: AddressIndexRefusal) : AddressIndexLoad
}

/**
 * Checks one entry of an account's addr1 history again, for the detail of its row (docs/wallet-ux.md, screen 26):
 * the detail never shows what the list once found, only what the engine's check finds now, against verified chain
 * data. [importer] is the key pinned in the release that the list rests on, which the detail names. Only while the
 * experimental setting is on for that key, with a reader ([setting], [history]): otherwise it is refused
 * ([AddressIndexRefusal.SettingOff]) and nothing is fetched, as the privacy policy promises. A
 * `TRACE`-only entry is never sent to the check, since the engine never verifies one: it is unverified. A verdict is
 * matched to the entry asked by block, slot and roles, as [LoadAddressIndexPage] matches it; none matched is
 * [AddressIndexVerdict.Unavailable]. A refused check is [AddressIndexEntryCheck.Refused], never a verdict.
 */
class CheckAddressIndexEntry(
    private val history: AddressIndexHistory,
    /** The user's choice: the check runs only while it is on for [importer]. */
    private val setting: AddressIndexSetting,
    /** The importer key pinned in the release: what the list's completeness rests on. */
    val importer: Address,
) {
    /** [entry] of [account]'s history, checked on [account]'s network, while the setting is on. */
    suspend operator fun invoke(account: AccountId, entry: AddressIndexEntry): AddressIndexEntryCheck {
        if (!ObserveAddressIndexStatus(history, setting, importer)().first().on) {
            return AddressIndexEntryCheck.Refused(AddressIndexRefusal.SettingOff)
        }
        if (entry.traceOnly) {
            return AddressIndexEntryCheck.Checked(AddressIndexVerdict.Unverified(entry, TRACE_ONLY))
        }
        return when (val answer = history.verify(account.network, account.address, listOf(entry))) {
            is AddressIndexCheck.Refused -> AddressIndexEntryCheck.Refused(answer.reason)
            is AddressIndexCheck.Verdicts -> AddressIndexEntryCheck.Checked(
                answer.verdicts
                    .filter { it.entry.block == entry.block && it.entry.slot == entry.slot }
                    .distinct()
                    .singleOrNull()
                    ?.takeIf { it.entry.roles == entry.roles }
                    ?: AddressIndexVerdict.Unavailable(entry, NO_VERDICT),
            )
        }
    }

    private companion object {
        const val TRACE_ONLY = "its one role is a trace, which the engine never verifies"
        const val NO_VERDICT = "the check gave no verdict for it"
    }
}

/** What [CheckAddressIndexEntry] answers. */
sealed interface AddressIndexEntryCheck {
    /** The engine's [verdict] of the entry now. */
    data class Checked(val verdict: AddressIndexVerdict) : AddressIndexEntryCheck

    /** Nothing checked, for [reason], which the screen states. */
    data class Refused(val reason: AddressIndexRefusal) : AddressIndexEntryCheck
}
