package eth.serotine.ui

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCoverage
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexHeadGap
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.AddressIndexTransaction
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.Wei
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexPage
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.AddressIndexSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A made-up importer key of the addr1 index, a test fixture: no release pins it. */
internal val FIXTURE_IMPORTER: Address = Address.of("0x" + "1e".repeat(20))

/**
 * An [AddressIndexHistory] as the port's contract says one behaves: [pages] by the cursor asked (null: the newest
 * page), [pageRefusal] in place of any page when set, and every entry checked by [verdictOf] (verified by default:
 * [verifiedRows] holds the row a verified entry is shown as), or [checkRefusal] for the whole check when set. Every
 * page asked is recorded in [asked].
 */
internal class FakeAddressIndexHistory(var reader: Boolean = true) : AddressIndexHistory {
    val pages = mutableMapOf<AddressIndexCursor?, AddressIndexPage>()
    var pageRefusal: AddressIndexRefusal? = null
    var checkRefusal: AddressIndexRefusal? = null
    val verifiedRows = mutableMapOf<AddressIndexEntry, AddressIndexRow>()
    var verdictOf: (AddressIndexEntry) -> AddressIndexVerdict = { entry ->
        verifiedRows[entry]?.let { AddressIndexVerdict.Verified(it) } ?: AddressIndexVerdict.Unverified(entry)
    }
    val asked = mutableListOf<AddressIndexCursor?>()

    override suspend fun hasReader(): Boolean = reader

    override suspend fun page(network: Network, address: Address, query: AddressIndexQuery): AddressIndexReading {
        asked += query.cursor
        pageRefusal?.let { return AddressIndexReading.Refused(it) }
        val page = pages[query.cursor]
            ?: return AddressIndexReading.Refused(AddressIndexRefusal.Rejected("no such cursor"))
        return AddressIndexReading.Page(page)
    }

    override suspend fun verify(
        network: Network,
        address: Address,
        entries: List<AddressIndexEntry>,
    ): AddressIndexCheck {
        require(entries.size in 1..AddressIndexHistory.MAX_CHECKED) { "1 to 50 entries" }
        checkRefusal?.let { return AddressIndexCheck.Refused(it) }
        return AddressIndexCheck.Verdicts(entries.map(verdictOf))
    }

    /**
     * A page signed by [importer], covering [covered] with the head gap above it, of [rows] (each verified) and
     * [others] (left to [verdictOf]), going on at [next].
     */
    fun page(
        cursor: String?,
        rows: List<AddressIndexRow>,
        next: String?,
        others: List<AddressIndexEntry> = emptyList(),
        covered: LongRange = 9_000_000L..9_400_000L,
        importer: Address = FIXTURE_IMPORTER,
    ) {
        rows.forEach { verifiedRows[it.entry] = it }
        val entries = (rows.map { it.entry } + others)
            .sortedWith(compareByDescending<AddressIndexEntry> { it.block }.thenByDescending { it.slot })
        pages[cursor?.let(::AddressIndexCursor)] = AddressIndexPage(
            AddressIndexCoverage(
                importer = importer,
                headUpdate = 3,
                covered = covered,
                headGap = AddressIndexHeadGap(covered.last + 1, covered.last + 120),
                tailGap = if (covered.first > 0) 0L until covered.first else null,
                gaps = emptyList(),
            ),
            entries,
            next?.let(::AddressIndexCursor),
        )
    }
}

/**
 * A verified row of the addr1 index: named in block [block] at [slot], in a transaction from [from]; [succeeded] as a
 * later engine may report its receipt's status (true by default here; null: not reported, as today).
 */
internal fun indexRow(
    block: Long,
    slot: Int,
    from: Address,
    to: Address?,
    wei: String = "0",
    hash: String = "0x" + block.toString(16).padStart(8, '0').repeat(8),
    transfer: TokenTransfer? = null,
    roles: Set<AddressIndexRole> = setOf(AddressIndexRole.FROM),
    final: Boolean = true,
    succeeded: Boolean? = true,
) = AddressIndexRow(
    entry = AddressIndexEntry(block, slot, roles),
    blockHash = "0x" + "bb".repeat(32),
    final = final,
    confirmed = roles - AddressIndexRole.TRACE,
    unchecked = roles intersect setOf(AddressIndexRole.TRACE),
    transaction = AddressIndexTransaction(hash, from, to, Wei.of(wei), transfer, succeeded),
)

/** An [AddressIndexSetting] in memory, as the port's contract says one behaves; [failSaves] makes every save fail. */
internal class MemoryAddressIndexSetting(on: Address? = null) : AddressIndexSetting {
    val choice = MutableStateFlow<AddressIndexChoice>(AddressIndexChoice.Chosen(importer = on))
    var failSaves = false

    override fun observe(): Flow<AddressIndexChoice> = choice

    override suspend fun trust(importer: Address?): Boolean {
        if (failSaves || choice.value is AddressIndexChoice.Unreadable) return false
        choice.value = AddressIndexChoice.Chosen(importer)
        return true
    }
}
