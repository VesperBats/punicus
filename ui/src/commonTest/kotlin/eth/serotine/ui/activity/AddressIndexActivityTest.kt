package eth.serotine.ui.activity

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexCursor
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexVerdict
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.usecase.LoadAddressIndexPage
import eth.serotine.domain.usecase.ObserveAccountCowOrders
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAddressIndexStatus
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveOutbox
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentTo
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.ObserveWaitingSwaps
import eth.serotine.ui.FIXTURE_IMPORTER
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeAddressIndexHistory
import eth.serotine.ui.FakeLogIndex
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryAddressIndexSetting
import eth.serotine.ui.MemoryCowOrders
import eth.serotine.ui.MemoryHistoryStarts
import eth.serotine.ui.MemoryOutbox
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.MemoryWaitingSwaps
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.indexRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An account's Activity with the addr1 index's verified rows (owner ruling, 2026-11-21, #334). */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressIndexActivityTest {

    private val alice = Account(
        Network.ETHEREUM,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Alice",
        AccountCustody.InKeyStore,
    )
    private val bob = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val stranger = Address.of("0x" + "5a".repeat(20))
    private val usdc = Token(Address.of("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"), "USDC", "USDC", 6, "usdc")
    private val history = MemoryTransactionHistory()
    private val accounts = FakeAccounts(alice)
    private val index = FakeAddressIndexHistory()
    private val setting = MemoryAddressIndexSetting(on = FIXTURE_IMPORTER)
    private val more = MutableStateFlow(0)

    private fun TestScope.activity(pinned: Address? = FIXTURE_IMPORTER): StateFlow<ActivityUiState> =
        AccountActivity(
            ObserveAccountHistory(history),
            ObserveAccounts(accounts),
            ObserveSafeHistory(FakeLogIndex(), MemoryHistoryStarts()),
            ObserveTokenTransfers(FakeLogIndex(), MemoryHistoryStarts()),
            ObserveKnownTokens(ListCatalog(usdc, network = Network.ETHEREUM), MemoryUserTokens(), FakeMetadata()),
            ObserveSentTo(history),
            ObserveAccountCowOrders(MemoryCowOrders()),
            ObserveWaitingSwaps(MemoryWaitingSwaps()),
            ObserveOutbox(MemoryOutbox()),
            addressIndex = pinned?.let {
                AddressIndexActivity(ObserveAddressIndexStatus(index, setting, it), LoadAddressIndexPage(index, it))
            },
            now = { 600 },
        ) { "at $it" }
            .of(AccountId(Network.ETHEREUM, alice.address), flowOf(false), more)
            .stateIn(
                backgroundScope + UnconfinedTestDispatcher(testScheduler),
                SharingStarted.Eagerly,
                ActivityUiState.Loading,
            )
            .also { runCurrent() }

    private fun TestScope.shown(activity: StateFlow<ActivityUiState>): ActivityUiState.Shown {
        runCurrent()
        return assertIs<ActivityUiState.Shown>(activity.value)
    }

    @Test
    fun offItAddsNothingAndReadsNothing() = runTest {
        setting.choice.value = AddressIndexChoice.Chosen(importer = null)

        val shown = shown(activity())

        assertNull(shown.index)
        assertTrue(index.asked.isEmpty(), "nothing read while it is off")
    }

    @Test
    fun itsVerifiedRowsAreListedNewestFirstNamingTheImporterAndTheNextPageLoadsAsAsked() = runTest {
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000")), next = "p2")
        index.page(
            "p2",
            listOf(indexRow(9_100_000, 1, bob, alice.address, wei = "1000000000000000000")),
            next = null,
            covered = 9_000_000L..9_400_000L,
        )
        val activity = activity()

        val first = shown(activity)
        assertEquals(listOf("Sent 0.25 ETH"), first.rows.map { it.title })
        val section = checkNotNull(first.index)
        assertEquals(IndexFooter.More, section.footer)
        assertTrue(section.lines.first().contains(Eip55.format(FIXTURE_IMPORTER)), "the importer named in full")
        assertTrue(section.lines.first().contains("entries may be missing"))
        assertTrue(section.lines.any { it.contains("blocks 9,000,000 to 9,400,000") }, section.lines.toString())
        assertTrue(section.lines.any { it.contains("has not reached the newest blocks yet, blocks 9,400,001") })
        assertEquals("Finalized in block 9,300,000 · checked against the chain", first.rows.single().status)

        more.value = 1
        val second = shown(activity)

        assertEquals(listOf("Sent 0.25 ETH", "Received 1 ETH"), second.rows.map { it.title })
        assertEquals(listOf(null, AddressIndexCursor("p2")), index.asked)
        val end = assertIs<IndexFooter.End>(second.index?.footer)
        assertEquals("The addr1 index starts at block 9,000,000: nothing older comes from it.", end.text)
        assertEquals(ActivityState.CAUTION, second.rows[1].state, "from an address never sent to")

        more.value = 2
        runCurrent()
        assertEquals(2, index.asked.size, "nothing is asked past the end of its coverage")
    }

    @Test
    fun aPageThatAddsNoRowStopsTheListAndATapReadsExactlyOneMore() = runTest {
        val unverified = { block: Long -> AddressIndexEntry(block, 1, setOf(AddressIndexRole.TOPIC)) }
        index.page(null, emptyList(), next = "p2", others = listOf(unverified(9_390_000), unverified(9_380_000)))
        index.page("p2", emptyList(), next = "p3", others = listOf(unverified(9_370_000)))
        index.page("p3", listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p4")
        index.page("p4", listOf(indexRow(9_200_000, 3, alice.address, bob)), next = null)
        val activity = activity()

        val paused = assertIs<IndexFooter.Paused>(shown(activity).index?.footer, "no row added: no page by itself")
        assertEquals(
            "The last page of the addr1 index added no row: 2 entries could not be verified. The next is read " +
                "only when you ask, since each page fetches old blocks from archive peers.",
            paused.text,
        )
        assertEquals(1, index.asked.size)

        more.value = 1
        val stillPaused = shown(activity)
        assertEquals(2, index.asked.size, "a tap reads exactly one page")
        assertIs<IndexFooter.Paused>(stillPaused.index?.footer)
        assertTrue(stillPaused.rows.isEmpty())

        more.value = 2
        val resumed = shown(activity)
        assertEquals(3, index.asked.size)
        assertEquals(listOf("Sent a transaction"), resumed.rows.map { it.title })
        assertEquals(IndexFooter.More, resumed.index?.footer, "a page that added a row reads on by itself again")
    }

    @Test
    fun aPageWhoseRowsAreAllListedAlreadyAddsNoRowEither() = runTest {
        val sent = SentTransfer(
            network = Network.ETHEREUM,
            account = alice.address,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 7)),
            action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of("250000000000000000")),
            maxCost = UInt256.of(1_000_000),
            sentAt = 300,
        )
        history.entries.value = listOf(HistoryEntry(sent, SentOutcome.Finalized(9_300_000, succeeded = true)))
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob, hash = sent.hash)), next = "p2")

        val footer = assertIs<IndexFooter.Paused>(shown(activity()).index?.footer)

        assertTrue(footer.text.contains("1 entry was listed already or is not listed"), footer.text)
    }

    @Test
    fun aTransactionSentFromHereShowsOnce() = runTest {
        val sent = SentTransfer(
            network = Network.ETHEREUM,
            account = alice.address,
            nonce = 7,
            signed = SignedTransfer(byteArrayOf(2, 7)),
            action = SentAction.Transfer(bob, TransferAsset.Native, UInt256.of("250000000000000000")),
            maxCost = UInt256.of(1_000_000),
            sentAt = 300,
        )
        history.entries.value = listOf(HistoryEntry(sent, SentOutcome.Finalized(9_300_000, succeeded = true)))
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob, hash = sent.hash)), next = null)

        val rows = shown(activity()).rows

        assertEquals(listOf("Sent 0.25 ETH"), rows.map { it.title })
        assertEquals(null, rows.single().opens.index, "the row sent from here, not the index's")
    }

    @Test
    fun whatItLeftOutIsCountedAndAContradictionWarnsAgainstTheImporter() = runTest {
        val contradicted = AddressIndexEntry(9_200_000, 2, setOf(AddressIndexRole.FROM))
        val unverified = AddressIndexEntry(9_150_000, 4, setOf(AddressIndexRole.TOPIC))
        val trace = AddressIndexEntry(9_140_000, 5, setOf(AddressIndexRole.TRACE))
        index.page(null, emptyList(), next = null, others = listOf(contradicted, unverified, trace))
        index.verdictOf = { entry ->
            if (entry == contradicted) {
                AddressIndexVerdict.Contradicted(entry, "0x" + "bb".repeat(32), true, setOf(AddressIndexRole.FROM))
            } else {
                AddressIndexVerdict.Unverified(entry)
            }
        }

        val shown = shown(activity())

        assertTrue(shown.rows.isEmpty(), "verified rows only")
        val section = checkNotNull(shown.index)
        assertEquals("2 entries of the index are left out: they could not be verified.", section.leftOut)
        val warning = checkNotNull(section.contradicted)
        assertTrue(warning.startsWith("Warning: the importer's index contradicts the chain in 1 entry"), warning)
    }

    @Test
    fun aRefusalIsSaidNeverAnEmptyListAndAPassingOneIsAskedAgain() = runTest {
        index.pageRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        val activity = activity()

        val refused = assertIs<IndexFooter.Refused>(shown(activity).index?.footer)
        assertEquals(true, refused.retry)
        assertTrue(refused.text.contains("still catching up"))

        index.pageRefusal = null
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = null)
        more.value = 1

        assertEquals(listOf("Sent a transaction"), shown(activity).rows.map { it.title })
    }

    @Test
    fun aLastingRefusalSaysWhyAndOffersNoRetry() = runTest {
        index.pageRefusal = AddressIndexRefusal.NotInThisBuild

        val refused = assertIs<IndexFooter.Refused>(shown(activity()).index?.footer)

        assertEquals(false, refused.retry)
        assertEquals(
            "This build's engine has no Swarm reader, so nothing comes from the addr1 index here.",
            refused.text,
        )
    }

    @Test
    fun theCheckpointAlarmWithdrawsTheRowsAlreadyShown() = runTest {
        index.page(null, listOf(indexRow(9_300_000, 3, alice.address, bob)), next = "p2")
        val older = AddressIndexEntry(9_100_000, 1, setOf(AddressIndexRole.TO))
        index.page("p2", emptyList(), next = null, others = listOf(older))
        val activity = activity()
        assertEquals(1, shown(activity).rows.size)

        index.checkRefusal = AddressIndexRefusal.CheckpointAlarm("checkpoint table alarm: root mismatch at 9,000,960")
        more.value = 1
        val shown = shown(activity)

        assertTrue(shown.rows.isEmpty(), "nothing resting on the table stays")
        assertEquals(1, shown.index?.lines?.size, "nor what the index said of its coverage")
        val footer = assertIs<IndexFooter.Refused>(shown.index?.footer)
        assertTrue(footer.warning)
    }

    @Test
    fun aTransferOfNothingFromAStrangerIsNotListedAndAnUnlistedTokensIsItsClaim() = runTest {
        val spam = Address.of("0x8617e340b3d01fa5f11f306f4090fd50e238070d")
        index.page(
            null,
            listOf(
                indexRow(
                    9_300_000,
                    3,
                    stranger,
                    usdc.address,
                    transfer = TokenTransfer.Fungible(usdc.address, alice.address, stranger, UInt256.ZERO),
                    roles = setOf(AddressIndexRole.TOPIC),
                ),
                indexRow(
                    9_200_000,
                    2,
                    stranger,
                    spam,
                    transfer = TokenTransfer.Fungible(spam, stranger, alice.address, UInt256.of(5)),
                    roles = setOf(AddressIndexRole.TOPIC),
                ),
                indexRow(
                    9_100_000,
                    1,
                    bob,
                    usdc.address,
                    transfer = TokenTransfer.Fungible(usdc.address, bob, alice.address, UInt256.of(1_000_000)),
                    roles = setOf(AddressIndexRole.TOPIC),
                ),
            ),
            next = null,
        )

        val rows = shown(activity()).rows

        assertEquals(
            listOf("An unlisted token logged a transfer to it", "Received 1 USDC"),
            rows.map { it.title },
            "a transfer of nothing plants a look-alike: it is left out",
        )
        assertEquals(ActivityState.UNKNOWN, rows[0].state)
        assertEquals(IndexEntryKey(9_100_000, 1, setOf(AddressIndexRole.TOPIC)), rows[1].opens.index)
    }

    @Test
    fun withNoKeyPinnedNothingIsRead() = runTest {
        val shown = shown(activity(pinned = null))

        assertNull(shown.index)
        assertTrue(index.asked.isEmpty())
    }

    @Test
    fun aTransactionWhoseOutcomeIsNotReportedClaimsNoValueAndARevertedOneFailed() = runTest {
        index.page(
            null,
            listOf(
                indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000", succeeded = null),
                indexRow(9_200_000, 2, alice.address, bob, wei = "250000000000000000", succeeded = false),
                indexRow(9_100_000, 1, alice.address, null, succeeded = null),
            ),
            next = null,
        )

        val rows = shown(activity()).rows

        assertEquals(
            listOf("Named in a transaction · sender", "Failed on chain", "Named in a transaction · sender"),
            rows.map { it.title },
        )
        assertEquals(ActivityState.FAILED, rows[1].state)
        assertEquals("Reverted in block 9,200,000 · checked against the chain", rows[1].status)
        assertEquals("It creates a contract", rows[2].detail)
    }

    @Test
    fun aTransferToItselfReadsAsTheLogIndexSaysIt() = runTest {
        index.page(
            null,
            listOf(
                indexRow(
                    9_300_000,
                    3,
                    alice.address,
                    usdc.address,
                    transfer = TokenTransfer.Fungible(usdc.address, alice.address, alice.address, UInt256.ONE),
                    roles = setOf(AddressIndexRole.FROM, AddressIndexRole.TOPIC),
                ),
            ),
            next = null,
        )

        assertEquals(listOf("Moved 0.000001 USDC to itself"), shown(activity()).rows.map { it.title })
    }
}
