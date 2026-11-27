package eth.serotine.ui.activitydetail

import eth.serotine.domain.model.AddressIndexImporters
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.AddressIndexRow
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.SigningRequest
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TokenTransfer
import eth.serotine.domain.model.TokenTransfers
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexRefusal
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.HistoryStart
import eth.serotine.domain.repository.IndexedLog
import eth.serotine.domain.repository.LogCoverage
import eth.serotine.domain.repository.LogCoverageReading
import eth.serotine.domain.repository.LogWatchKey
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.ReceiptLog
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentOutcome
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.usecase.CheckAddressIndexEntry
import eth.serotine.domain.usecase.ObserveAccountHistory
import eth.serotine.domain.usecase.ObserveAccounts
import eth.serotine.domain.usecase.ObserveAmountsHidden
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveSafeHistory
import eth.serotine.domain.usecase.ObserveSentTo
import eth.serotine.domain.usecase.ObserveTokenTransfers
import eth.serotine.domain.usecase.ToggleAmountsHidden
import eth.serotine.ui.FIXTURE_IMPORTER
import eth.serotine.ui.onEvery
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeAddressIndexHistory
import eth.serotine.ui.FakeLogIndex
import eth.serotine.ui.FakeMetadata
import eth.serotine.ui.ListCatalog
import eth.serotine.ui.MemoryAddressIndexSetting
import eth.serotine.ui.MemoryAmountsHidden
import eth.serotine.ui.MemoryHistoryStarts
import eth.serotine.ui.MemoryTransactionHistory
import eth.serotine.ui.MemoryUserTokens
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.activity.IndexEntryKey
import eth.serotine.ui.components.AMOUNT_MASK
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.indexRow
import eth.serotine.ui.topicOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDetailViewModelTest {

    private val alice = Account(
        Network.SEPOLIA,
        Address.of("0x8f3a2c71e5b9d04a6f1e3c2ba7d90e5f4c6b91c2"),
        AccountKind.EOA,
        "Alice",
        AccountCustody.InKeyStore,
    )
    private val treasury = Account(
        Network.SEPOLIA,
        Address.of("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        AccountKind.SAFE,
        "Treasury",
        AccountCustody.Watched,
    )
    private val bob = Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
    private val spam = Address.of("0x8617e340b3d01fa5f11f306f4090fd50e238070d")
    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USDC", 6, "usdc")
    private val history = MemoryTransactionHistory()
    private val accounts = FakeAccounts(alice, treasury)
    private val starts = MemoryHistoryStarts()
    private val index = FakeLogIndex()
    private val userTokens = MemoryUserTokens()

    private val amountsHidden = MemoryAmountsHidden()

    private val sent = SentTransfer(
        network = Network.SEPOLIA,
        account = alice.address,
        nonce = 7,
        signed = SignedTransfer(byteArrayOf(2, 7)),
        action = SentAction.Transfer(bob, TransferAsset.Erc20Token(usdc), UInt256.of(40_500_000)),
        maxCost = UInt256.of(1_000_000),
        sentAt = 300,
    )

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        key: ActivityKey,
        address: Address = alice.address,
        check: CheckAddressIndexEntry? = null,
        describe: eth.serotine.domain.usecase.DescribePastTransaction? = null,
    ): ActivityDetailViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ActivityDetailViewModel(
            AccountId(Network.SEPOLIA, address),
            key,
            ObserveAccountHistory(history),
            ObserveAccounts(accounts),
            ObserveSafeHistory(index, starts),
            ObserveTokenTransfers(index, starts),
            ObserveKnownTokens(ListCatalog(usdc), userTokens, FakeMetadata()),
            ObserveSentTo(history),
            ObserveAmountsHidden(amountsHidden),
            ToggleAmountsHidden(amountsHidden),
            check,
            describe,
        ) { "at $it" }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    /** [address] followed from block 9,400,000, its logs indexed over [covered]. */
    private fun indexed(address: Address, covered: LongRange = 9_400_000L..9_420_000L) {
        starts.starts.value = mapOf(address to HistoryStart.Fixed(9_400_000))
        index.coverage.value = LogCoverageReading.Read(
            mapOf(
                LogWatchKey.Emitter(address) to LogCoverage(9_400_000, covered),
                LogWatchKey.Mention(address) to LogCoverage(9_400_000, covered),
            ),
        )
    }

    private fun tokenLog(
        token: Address,
        from: Address,
        to: Address,
        data: String,
        tx: String,
        tokenId: String? = null,
    ) = IndexedLog(
        ReceiptLog(token, listOfNotNull(TokenTransfers.TRANSFER, topicOf(from), topicOf(to), tokenId), data),
        9_402_000,
        "0x" + "bb".repeat(32),
        tx,
        3,
    )

    private fun word(digits: String) = "0x" + digits.padStart(64, '0')

    @Test
    fun aSentTransferNamesEveryPartyInFullAndOpensItsStatus() = runTest {
        history.entries.value = listOf(HistoryEntry(sent, SentOutcome.Finalized(9_412_118, succeeded = true)))

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(ActivityKey(sent.hash)).uiState.value)

        assertEquals("Alice", shown.title)
        assertEquals("Sent 40.5 USDC", shown.headline)
        assertEquals("Finalized in block 9,412,118", shown.status)
        assertEquals("Final", shown.simpleStatus, "simple mode's words, no block")
        assertEquals(ActivityState.DONE, shown.state)
        assertEquals(
            listOf(
                DetailField("From", Eip55.format(alice.address), DetailKind.ADDRESS, "Alice"),
                DetailField("To", Eip55.format(bob), DetailKind.ADDRESS),
                DetailField("Amount", "40.5 USDC"),
                DetailField("Token contract", Eip55.format(usdc.address), DetailKind.ADDRESS, "USDC"),
                DetailField("Transaction hash", sent.hash, DetailKind.HASH),
                DetailField("Nonce", "7"),
                DetailField("Sent", "at 300"),
                DetailField("Block", "9,412,118", DetailKind.BLOCK),
            ),
            shown.fields,
        )
        assertEquals(sent.hash, shown.opensStatus)
        assertNull(shown.addToken)
    }

    @Test
    fun aCowOrdersCancellationNamesTheOrderAndTheSettlement() = runTest {
        val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
        val uid = CowOrderUid.of("0x" + "ab".repeat(32) + alice.address.hex.removePrefix("0x") + "6ad9e7f4")
        val cancellation = sent.copy(action = SentAction.CowCancellation(settlement, uid))
        history.entries.value = listOf(HistoryEntry(cancellation, SentOutcome.Finalized(9_412_118, succeeded = true)))

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(ActivityKey(cancellation.hash)).uiState.value)

        assertEquals("Cancelled a CoW order on chain", shown.headline)
        val fields = shown.fields.associate { it.label to it.value }
        assertEquals(uid.hex, fields["CoW order"])
        assertEquals(Eip55.format(settlement), fields["Settlement contract"])
        assertEquals(DetailKind.HASH, shown.fields.single { it.label == "CoW order" }.kind)
    }

    @Test
    fun aTransactionNotInTheAccountsHistoryIsSaidToBeSo() = runTest {
        history.entries.value = listOf(HistoryEntry(sent))

        val state = viewModel(ActivityKey("0x" + "99".repeat(32))).uiState.value

        val unavailable = assertIs<ActivityDetailUiState.Unavailable>(state)
        assertEquals("This transaction is not in this account's history.", unavailable.message)
    }

    @Test
    fun anUnlistedErc20TransferNamesOnlyItsContractAndCanBeAdded() = runTest {
        indexed(alice.address)
        val tx = "0x" + "e2".repeat(32)
        index.logs = listOf(tokenLog(spam, bob, alice.address, word("1"), tx))

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(ActivityKey(tx, 3)).uiState.value)

        assertEquals("An unlisted token logged a transfer to it", shown.headline)
        assertEquals("The claim of a contract the wallet does not list", shown.status, "Add token is on screen")
        assertEquals(
            listOf(
                DetailField("Token contract", Eip55.format(spam), DetailKind.ADDRESS),
                DetailField("Transaction hash", tx, DetailKind.HASH),
                DetailField("Block", "9,402,000", DetailKind.BLOCK),
            ),
            shown.fields,
            "the other side an unlisted contract names, not",
        )
        assertEquals(Eip55.format(spam), shown.addToken)
        assertTrue(shown.note.orEmpty().startsWith("A contract the wallet does not show"))
        assertNull(shown.opensStatus)
    }

    @Test
    fun anUnlistedNftTransferCannotBeAddedAsAToken() = runTest {
        indexed(alice.address)
        val tx = "0x" + "e3".repeat(32)
        index.logs = listOf(tokenLog(spam, alice.address, bob, "0x", tx, tokenId = word("7")))

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(ActivityKey(tx, 3)).uiState.value)

        assertNull(shown.addToken)
        assertTrue("ERC-721" in shown.note.orEmpty())
    }

    @Test
    fun hiddenAmountsMaskTheHeadlineAndWhatMovedAndTheEyeTogglesThem() = runTest {
        amountsHidden.hidden.value = true
        history.entries.value = listOf(HistoryEntry(sent, SentOutcome.Finalized(9_412_118, succeeded = true)))
        val viewModel = viewModel(ActivityKey(sent.hash))

        val hidden = assertIs<ActivityDetailUiState.Shown>(viewModel.uiState.value)
        assertTrue(hidden.amountsHidden)
        assertEquals("Sent $AMOUNT_MASK USDC", hidden.headline)
        assertEquals(DetailField("Amount", "$AMOUNT_MASK USDC"), hidden.fields.single { it.label == "Amount" })
        // Who and where stay: only the amount is hidden.
        assertEquals(DetailField("To", Eip55.format(bob), DetailKind.ADDRESS), hidden.fields.single { it.label == "To" })

        viewModel.toggleAmounts()
        runCurrent()
        assertFalse(amountsHidden.hidden.value)
        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel.uiState.value)
        assertFalse(shown.amountsHidden)
        assertEquals("Sent 40.5 USDC", shown.headline)
        assertEquals(DetailField("Amount", "40.5 USDC"), shown.fields.single { it.label == "Amount" })
    }

    @Test
    fun aListedTokensTransferNamesBothSidesAndWhatMoved() = runTest {
        indexed(alice.address)
        val tx = "0x" + "e1".repeat(32)
        index.logs = listOf(tokenLog(usdc.address, bob, alice.address, word("2625a00"), tx))

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(ActivityKey(tx, 3)).uiState.value)

        assertEquals("Received 40 USDC", shown.headline)
        assertEquals(ActivityState.CAUTION, shown.state, "Bob was never sent to")
        assertTrue(shown.note.orEmpty().startsWith("You have not sent to the sender's address"))
        assertEquals(
            listOf(
                // Never sent to: a possible look-alike, shown in full and with no Copy.
                DetailField("From", Eip55.format(bob), DetailKind.ADDRESS, copyable = false),
                DetailField("To", Eip55.format(alice.address), DetailKind.ADDRESS, "Alice"),
                DetailField("Amount", "40 USDC"),
                DetailField("Token contract", Eip55.format(usdc.address), DetailKind.ADDRESS, "USDC"),
                DetailField("Transaction hash", tx, DetailKind.HASH),
                DetailField("Block", "9,402,000", DetailKind.BLOCK),
            ),
            shown.fields,
        )
        assertNull(shown.addToken)

        history.entries.value = listOf(HistoryEntry(sent))
        val familiar = assertIs<ActivityDetailUiState.Shown>(viewModel(ActivityKey(tx, 3)).uiState.value)
        assertEquals(ActivityState.DONE, familiar.state, "once sent to, Bob is familiar")
        assertNull(familiar.note)
    }

    @Test
    fun describeReadsTheTransactionsBlockOnTheTapAndSaysWhatItDid() = runTest {
        indexed(alice.address)
        val tx = "0x" + "e1".repeat(32)
        index.logs = listOf(tokenLog(usdc.address, bob, alice.address, word("2625a00"), tx))
        val read = mutableListOf<Long>()
        val blocks = object : eth.serotine.domain.repository.BlockTransactions {
            override suspend fun head(network: Network) = eth.serotine.domain.repository.HeadReading.At(9_500_000)
            override fun reach(network: Network) = eth.serotine.domain.repository.BlockReach.Deep
            override suspend fun transactions(network: Network, number: Long): eth.serotine.domain.repository.BlockReading {
                read += number
                val data = eth.serotine.domain.model.Erc20.transferData(alice.address, eth.serotine.domain.model.UInt256.of(40_000_000))
                return eth.serotine.domain.repository.BlockReading.Read(
                    listOf(
                        eth.serotine.domain.repository.ChainTransaction(
                            tx, bob, usdc.address, eth.serotine.domain.model.UInt256.ZERO,
                            "0x" + data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
                        ),
                    ),
                )
            }
        }
        val catalog = object : eth.serotine.domain.repository.ClearSigningCatalog {
            override fun deployment(network: Network, address: Address) = null
        }
        val describe = eth.serotine.domain.usecase.DescribePastTransaction(
            history, blocks, eth.serotine.ui.dapptransaction.describing(ListCatalog(usdc)), catalog,
            eth.serotine.ui.MemoryNetworkSettings(setOf(Network.SEPOLIA)),
        )
        val vm = viewModel(ActivityKey(tx, 3), describe = describe)
        val offered = assertIs<PastDescribeSection.Offer>(assertIs<ActivityDetailUiState.Shown>(vm.uiState.value).describe)
        assertTrue(offered.offer.startsWith("Downloads block 9,402,000's transactions"), offered.offer)
        assertTrue(offered.offer.contains("for a block this old"), "below the window: the walk down is said")
        assertFalse(offered.simpleOffer.contains("9,402,000"), "no block number in simple mode")
        assertTrue(read.isEmpty(), "nothing is read before the tap")

        vm.describe()
        runCurrent()
        val done = assertIs<PastDescribeSection.Described>(assertIs<ActivityDetailUiState.Shown>(vm.uiState.value).describe)
        assertEquals(listOf(9_402_000L), read)
        assertEquals("${Eip55.format(bob)} sent 40000000 units of the token at ${Eip55.format(usdc.address)} to ${Eip55.format(alice.address)}.", done.headline)
        assertTrue(done.notes.last().startsWith("Its call was read from its block"))
        // Bob sent it, choosing every address in it: none is selectable (#324).
        assertFalse(done.selectable)

        // While amounts are hidden, nothing of what it moved is said.
        vm.toggleAmounts()
        runCurrent()
        val hidden = assertIs<PastDescribeSection.Described>(assertIs<ActivityDetailUiState.Shown>(vm.uiState.value).describe)
        assertEquals("Amounts are hidden: open the eye to see what it did.", hidden.headline)
    }

    @Test
    fun aSafesEventNamesWhatItConcernsAndTheSafe() = runTest {
        indexed(treasury.address)
        val tx = "0x" + "cc".repeat(32)
        val received = "0x3d0ce9bfc3ed7d6862dbb28b2dea94561fe714a1b4d019aa8af39730d1ad7c3d"
        index.logs = listOf(
            IndexedLog(
                ReceiptLog(treasury.address, listOf(received, topicOf(bob)), word("0de0b6b3a7640000")),
                9_405_000,
                "0x" + "bb".repeat(32),
                tx,
                0,
                blockTimestamp = 1_761_686_208,
            ),
        )

        val state = viewModel(ActivityKey(tx, 0), treasury.address).uiState.value

        val shown = assertIs<ActivityDetailUiState.Shown>(state)

        assertEquals("Treasury", shown.title)
        assertEquals("Received 1 ETH", shown.headline)
        assertEquals(
            listOf(
                // Bob was never sent to: the ETH the Safe received from him is cautioned, his address with no Copy.
                DetailField("From", Eip55.format(bob), DetailKind.ADDRESS, copyable = false),
                DetailField("Amount", "1 ETH"),
                DetailField("Safe", Eip55.format(treasury.address), DetailKind.ADDRESS, "Treasury"),
                DetailField("Transaction hash", tx, DetailKind.HASH),
                DetailField("Time", "at 1761686208"),
                DetailField("Block", "9,405,000", DetailKind.BLOCK),
            ),
            shown.fields,
        )
    }

    @Test
    fun anEventNotFoundLoadsWhileItsBlocksAreCheckedAndIsGoneOnceAllAre() = runTest {
        val key = ActivityKey("0x" + "e4".repeat(32), 1)
        indexed(alice.address, covered = 9_410_000L..9_420_000L)

        assertIs<ActivityDetailUiState.Loading>(viewModel(key).uiState.value)

        indexed(alice.address)
        val gone = assertIs<ActivityDetailUiState.Unavailable>(viewModel(key).uiState.value)

        assertFalse(gone.message.isBlank())
        assertTrue("reorganisation" in gone.message)
    }

    @Test
    fun aTransferOfNothingIsNotDetailedAndAnUnreadableHistoryLoads() = runTest {
        val tx = "0x" + "e5".repeat(32)
        index.logs = listOf(tokenLog(usdc.address, bob, alice.address, word("0"), tx))

        // What every history says until its first coverage answers.
        assertIs<ActivityDetailUiState.Loading>(viewModel(ActivityKey(tx, 3)).uiState.value)

        indexed(alice.address)
        val withheld = assertIs<ActivityDetailUiState.Unavailable>(viewModel(ActivityKey(tx, 3)).uiState.value)
        assertTrue(withheld.message.startsWith("This transfer moved nothing"), "not said to be gone")
    }

    @Test
    fun aSafeExecutionSentFromHereNamesTheSafeAndItsTransaction() = runTest {
        val request =
            SigningRequest.of(Network.SEPOLIA, treasury.address, SafeTx.call(bob, UInt256.ONE, ByteArray(0), 4))
        val execution =
            sent.copy(nonce = 9, signed = SignedTransfer(byteArrayOf(2, 9)), action = SentAction.SafeExecution(request))
        history.entries.value = listOf(HistoryEntry(execution))

        val state = viewModel(ActivityKey(execution.hash), treasury.address).uiState.value

        val shown = assertIs<ActivityDetailUiState.Shown>(state)
        assertEquals("Executed transaction 4: Send 0.000000000000000001 ETH", shown.headline, "as its row")
        assertEquals(
            listOf(
                DetailField("From", Eip55.format(alice.address), DetailKind.ADDRESS, "Alice"),
                DetailField("Safe", Eip55.format(treasury.address), DetailKind.ADDRESS, "Treasury"),
                DetailField("Safe nonce", "4"),
                DetailField("Safe transaction hash", "0x${request.hash.hex}", DetailKind.HASH),
                DetailField("Safe transaction to", Eip55.format(bob), DetailKind.ADDRESS),
                DetailField("Transaction hash", execution.hash, DetailKind.HASH),
                DetailField("Nonce", "9"),
                DetailField("Sent", "at 300"),
            ),
            shown.fields,
        )
        assertEquals(execution.hash, shown.opensStatus)
    }

    @Test
    fun theNetworksLogOfAnEthTransferIsDetailedAsEthWithNoContract() = runTest {
        indexed(alice.address)
        val tx = "0x" + "e6".repeat(32)
        index.logs = listOf(tokenLog(TokenTransfers.ETH, alice.address, bob, word("6f05b59d3b20000"), tx))

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(ActivityKey(tx, 3)).uiState.value)

        assertEquals("Sent 0.5 ETH", shown.headline)
        assertEquals(
            listOf(
                DetailField("From", Eip55.format(alice.address), DetailKind.ADDRESS, "Alice"),
                DetailField("To", Eip55.format(bob), DetailKind.ADDRESS),
                DetailField("Amount", "0.5 ETH"),
                DetailField("Transaction hash", tx, DetailKind.HASH),
                DetailField("Block", "9,402,000", DetailKind.BLOCK),
            ),
            shown.fields,
        )
        assertNull(shown.addToken)
        assertTrue(shown.note.orEmpty().startsWith("A plain ETH transfer"))
    }

    private val addressIndex = FakeAddressIndexHistory()
    private val addressIndexOn = MemoryAddressIndexSetting(on = FIXTURE_IMPORTER)

    /** The detail's check, with the setting on for the fixture's key. */
    private val checkOn = CheckAddressIndexEntry(addressIndex, addressIndexOn, onEvery(FIXTURE_IMPORTER))

    private fun indexKey(row: AddressIndexRow) = ActivityKey(
        row.transaction?.hash.orEmpty(),
        index = IndexEntryKey(row.entry.block, row.entry.slot, row.entry.roles),
    )

    @Test
    fun anIndexRowIsCheckedAgainAndShownWithWhatItRestsOn() = runTest {
        val roles = setOf(AddressIndexRole.TO, AddressIndexRole.TRACE)
        val row = indexRow(
            9_300_000,
            3,
            bob,
            alice.address,
            wei = "1000000000000000000",
            roles = roles,
            blockTimestamp = 1_795_600_000,
        )
        addressIndex.verifiedRows[row.entry] = row

        val shown = assertIs<ActivityDetailUiState.Shown>(
            viewModel(indexKey(row), check = checkOn).uiState.value,
        )

        assertEquals("Received 1 ETH", shown.headline)
        assertEquals(ActivityState.CAUTION, shown.state, "from an address never sent to")
        val fields = shown.fields.associate { it.label to it.value }
        assertEquals(row.transaction?.hash, fields["Transaction hash"])
        assertEquals("at 1795600000", fields["Time"], "when its block was produced, as the list says it")
        assertEquals("9,300,000", fields["Block"])
        assertEquals("Transaction 3", fields["In the block"])
        assertEquals("Recipient", fields["Proven on chain"])
        assertEquals("Reached by an inner call", fields["On the importer's word"])
        assertEquals(false, shown.fields.single { it.label == "From" }.copyable, "a cautioned sender has no Copy")
        val note = checkNotNull(shown.note)
        assertTrue(note.contains(Eip55.format(FIXTURE_IMPORTER)), note)
        assertTrue(note.contains("finalized"), note)
    }

    @Test
    fun theDetailNamesTheKeyOfTheAccountsOwnNetwork() = runTest {
        val row = indexRow(9_300_000, 3, bob, alice.address, wei = "1000000000000000000")
        addressIndex.verifiedRows[row.entry] = row
        val otherKey = Address.of("0x" + "9e".repeat(20))
        // The detail's account is on Sepolia here: its key is the fixture's, another network's is another key.
        val keys = AddressIndexImporters(mapOf(Network.ETHEREUM to otherKey, Network.SEPOLIA to FIXTURE_IMPORTER))
        val check = CheckAddressIndexEntry(addressIndex, addressIndexOn, keys)

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(indexKey(row), check = check).uiState.value)

        val note = checkNotNull(shown.note)
        assertTrue(note.contains(Eip55.format(FIXTURE_IMPORTER)), note)
        assertFalse(note.contains(Eip55.format(otherKey)), "never another network's key")
    }

    @Test
    fun anIndexRowTheAccountDidNotSendGivesNoAddressOfItsOtherSideCopy() = runTest {
        val stranger = Address.of("0x" + "5a".repeat(20))
        val lookAlike = Address.of("0x" + "5b".repeat(20))
        // Named in a stranger's transaction to a look-alike (the account's address in its input), and a transfer a
        // stranger sent from another address than the transaction's sender (a transferFrom).
        val named = indexRow(9_300_000, 3, stranger, lookAlike, roles = setOf(AddressIndexRole.WORD))
        val pulled = indexRow(
            9_200_000,
            2,
            stranger,
            usdc.address,
            transfer = TokenTransfer.Fungible(usdc.address, lookAlike, alice.address, UInt256.of(1_000_000)),
            roles = setOf(AddressIndexRole.TOPIC),
        )
        listOf(named, pulled).forEach { addressIndex.verifiedRows[it.entry] = it }

        for (row in listOf(named, pulled)) {
            val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(indexKey(row), check = checkOn).uiState.value)
            val addresses = shown.fields.filter { it.kind == DetailKind.ADDRESS && it.label in setOf("From", "To") }
            assertTrue(addresses.isNotEmpty())
            assertTrue(addresses.none { it.copyable }, "block ${row.entry.block}: ${shown.fields}")
        }
    }

    @Test
    fun anIndexRowOfATokenTheWalletDoesNotShowNamesItsContractAlone() = runTest {
        val unlisted = Address.of("0x" + "7e".repeat(20))
        val stranger = Address.of("0x" + "5a".repeat(20))
        val row = indexRow(
            9_300_000,
            3,
            stranger,
            unlisted,
            transfer = TokenTransfer.Fungible(unlisted, stranger, alice.address, UInt256.of(1)),
            roles = setOf(AddressIndexRole.TOPIC),
        )
        addressIndex.verifiedRows[row.entry] = row

        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(indexKey(row), check = checkOn).uiState.value)

        assertEquals(listOf("Token contract"), shown.fields.filter { it.kind == DetailKind.ADDRESS }.map { it.label })
        assertTrue(checkNotNull(shown.note).startsWith("A contract the wallet does not show"), shown.note)
    }

    @Test
    fun anIndexRowNoLongerVerifiedIsNotShown() = runTest {
        val row = indexRow(9_300_000, 3, alice.address, bob)

        val check = checkOn

        val state = viewModel(indexKey(row), check = check).uiState.value

        val unavailable = assertIs<ActivityDetailUiState.Unavailable>(state)
        assertTrue(
            unavailable.message.startsWith("This entry of the addr1 index cannot be verified"),
            unavailable.message,
        )
    }

    @Test
    fun aRefusedCheckSaysWhyAndAHostWithoutTheIndexShowsNone() = runTest {
        val row = indexRow(9_300_000, 3, alice.address, bob)
        addressIndex.checkRefusal = AddressIndexRefusal.TorRouting

        val refused = viewModel(indexKey(row), check = checkOn)
        val none = viewModel(indexKey(row))

        assertTrue(assertIs<ActivityDetailUiState.Unavailable>(refused.uiState.value).message.startsWith("Tor routing"))
        assertTrue(
            assertIs<ActivityDetailUiState.Unavailable>(none.uiState.value).message.startsWith("This build's engine"),
        )
    }

    @Test
    fun anIndexRowWhoseOutcomeIsNotReportedSaysSoBesideItsValue() = runTest {
        val row = indexRow(9_300_000, 3, alice.address, bob, wei = "250000000000000000", succeeded = null)
        addressIndex.verifiedRows[row.entry] = row

        val check = checkOn
        val shown = assertIs<ActivityDetailUiState.Shown>(viewModel(indexKey(row), check = check).uiState.value)

        assertEquals("Named in a transaction · sender", shown.headline, "no value claimed as moved")
        val fields = shown.fields.associate { it.label to it.value }
        assertEquals("0.25 ETH", fields["Value"])
        assertTrue(checkNotNull(fields["Outcome"]).startsWith("Not checked"), fields["Outcome"])
    }

    @Test
    fun aCheckThatCouldNotBeMadeNowOffersTryAgainWhichChecksOnceMore() = runTest {
        val row = indexRow(9_300_000, 3, alice.address, bob)
        addressIndex.verifiedRows[row.entry] = row
        addressIndex.checkRefusal = AddressIndexRefusal.NotNow(ReadUnavailable.SYNCING)
        val viewModel = viewModel(indexKey(row), check = checkOn)

        val waiting = assertIs<ActivityDetailUiState.Unavailable>(viewModel.uiState.value)
        assertTrue(waiting.retry)
        assertTrue(waiting.message.contains("cannot be checked yet"), waiting.message)

        addressIndex.checkRefusal = null
        viewModel.checkAgain()
        runCurrent()

        assertIs<ActivityDetailUiState.Shown>(viewModel.uiState.value)
    }

    @Test
    fun theDetailChecksNothingOnceTheSettingIsOff() = runTest {
        val row = indexRow(9_300_000, 3, alice.address, bob)
        addressIndex.verifiedRows[row.entry] = row
        val off = MemoryAddressIndexSetting().apply { choice.value = AddressIndexChoice.Chosen(null) }

        val state = viewModel(indexKey(row), check = CheckAddressIndexEntry(addressIndex, off, onEvery(FIXTURE_IMPORTER)))

        val refused = assertIs<ActivityDetailUiState.Unavailable>(state.uiState.value)
        assertTrue(refused.message.contains("experimental setting is off"), refused.message)
        assertEquals(false, refused.retry)
    }
}
