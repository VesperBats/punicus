package eth.serotine.domain.usecase

import eth.serotine.domain.model.AbiDecoder
import eth.serotine.domain.model.AbiFunction
import eth.serotine.domain.model.AbiType
import eth.serotine.domain.model.AbiValue
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSigningCodeReads
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.ClearSigningDescriptor
import eth.serotine.domain.model.ClearSigningFormat
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeExecCall
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.SignedTransfer
import eth.serotine.domain.model.TransferAsset
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.hexToBytes
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.BlockReading
import eth.serotine.domain.repository.BlockTransactions
import eth.serotine.domain.repository.ChainTransaction
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.HeadReading
import eth.serotine.domain.repository.HistoryEntry
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.SentAction
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A past transaction described on the user's tap (docs/clear-signing.md, *Past transactions*). */
class DescribePastTransactionTest {

    private val user = Address.of("0x" + "aa".repeat(20))
    private val pool = Address.of("0x" + "01".repeat(20))
    private val proxy = Address.of("0x" + "03".repeat(20))
    private val safe = Address.of("0x" + "5a".repeat(20))
    private val account = AccountId(Network.ETHEREUM, user)
    private val codeHash = "0x" + "11".repeat(32)

    private val deposit = AbiFunction.parse("deposit(uint256 amount)")!!
    private val descriptor = ClearSigningDescriptor(
        source = "test", sha256 = "00", owner = "Pool DAO", kind = ClearSigningDescriptor.Kind.CALLDATA,
        domainName = null, domainVersion = null, enums = emptyMap(),
        formats = listOf(
            ClearSigningFormat(
                key = "deposit(uint256 amount)", function = deposit, selector = deposit.selector,
                encodeType = null, typeHash = null, primaryType = null, intent = "Deposit", interpolatedIntent = null,
                fields = emptyList(),
            ),
        ),
    )
    private val catalog = object : ClearSigningCatalog {
        override fun deployment(network: Network, address: Address) = when (address) {
            pool -> ClearSigningDeployment(Network.ETHEREUM, "Pool", pool, codeHash, CodePin.Fixed, listOf(descriptor))
            proxy -> ClearSigningDeployment(
                Network.ETHEREUM, "Proxy", proxy, codeHash, CodePin.Slot("0x" + "00".repeat(32), pool, codeHash), listOf(descriptor),
            )
            else -> null
        }
    }
    private val codes = object : ContractCodes {
        override suspend fun read(network: Network, address: Address, pin: CodePin) =
            CodeReading.Read(ClearSigningCodeReads(codeHash, if (pin is CodePin.Fixed) null else "0x" + "00".repeat(12) + pool.hex.removePrefix("0x"), codeHash.takeIf { pin !is CodePin.Fixed }))
    }
    private val describe = DescribeClearSigning(
        catalog,
        codes,
        object : TokenCatalog { override fun observeTokens(network: Network) = flowOf(emptyList<eth.serotine.domain.model.Token>()) },
        object : AddressKinds { override fun observeKind(network: Network, address: Address) = flowOf<KindReading>(KindReading.Syncing) },
    )

    private val history = MemoryTransactionHistory()
    private val blockReads = mutableListOf<Long>()
    private var block: List<ChainTransaction> = emptyList()
    private val blocks = object : BlockTransactions {
        override suspend fun head(network: Network) = HeadReading.At(100)
        override suspend fun transactions(network: Network, number: Long): BlockReading {
            blockReads += number
            return if (number > 100) BlockReading.NotYet else BlockReading.Read(block)
        }
    }
    private val describePast = DescribePastTransaction(history, blocks, describe, catalog)

    private fun depositData(amount: Long) = deposit.selector.removePrefix("0x").hexToBytes() +
        AbiDecoder.encode(AbiType.TupleType(deposit.params), AbiValue.TupleValue(listOf("amount" to AbiValue.UintValue(UInt256.of(amount), 256))))

    private val hash = "0x" + "cd".repeat(32)

    @Test
    fun aTransactionReadFromItsBlockIsDescribedAndAProxySaysItsCodeMayHaveChanged() = runTest {
        block = listOf(ChainTransaction(hash, user, pool, UInt256.ZERO, "0x" + depositData(5).toHex()))
        val read = assertIs<PastDescription.Read>(describePast(account, hash, 42))
        assertTrue(read.downloaded)
        assertEquals("Deposit", (read.result as ClearSignResult.ClearSigned).intent)
        assertFalse(read.codeMayHaveChanged)
        assertEquals(listOf(42L), blockReads)

        block = listOf(ChainTransaction(hash, user, proxy, UInt256.ZERO, "0x" + depositData(5).toHex()))
        assertTrue(assertIs<PastDescription.Read>(describePast(account, hash, 42)).codeMayHaveChanged)
    }

    @Test
    fun whatCannotBeReadSaysWhy() = runTest {
        block = emptyList()
        assertEquals(PastDescription.NotRead(PastCallUnavailable.NotInBlock), describePast(account, hash, 42))
        assertEquals(PastDescription.NotRead(PastCallUnavailable.BlockNotServed), describePast(account, hash, 400))
        assertEquals(PastDescription.NotRead(PastCallUnavailable.NoBlock), describePast(account, hash, null))
        block = listOf(ChainTransaction(hash, user, null, UInt256.ZERO, "0x6080"))
        assertEquals(PastDescription.NotRead(PastCallUnavailable.Creation), describePast(account, hash, 42))
    }

    @Test
    fun oneSentFromHereIsReadFromItsRecordWithNothingDownloaded() = runTest {
        val tx = Eip1559Transaction(1, 7, UInt256.ONE, UInt256.of(2), 90_000, pool, UInt256.ZERO, depositData(9))
        val signed = SignedTransfer(tx.encodeSigned(Signature("1".padStart(64, '0'), "2".padStart(64, '0'), 0)))
        history.entries.value = listOf(
            HistoryEntry(
                SentTransfer(Network.ETHEREUM, user, 7, signed, SentAction.Transfer(pool, TransferAsset.Native, UInt256.ZERO), UInt256.ONE, 0),
            ),
        )
        val read = assertIs<PastDescription.Read>(describePast(account, signed.hash, 42))
        assertFalse(read.downloaded)
        assertEquals(depositData(9).toHex(), read.call.data.toHex())
        assertTrue(blockReads.isEmpty())
    }

    @Test
    fun aSafesExecutionIsDescribedAsWhatTheSafeRan() = runTest {
        val inner = SafeTx(pool, UInt256.ZERO, depositData(3), SafeOperation.CALL, UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, Address.ZERO, Address.ZERO, 0)
        block = listOf(ChainTransaction(hash, user, safe, UInt256.ZERO, "0x" + SafeExecCall.data(inner, ByteArray(65)).toHex()))
        val read = assertIs<PastDescription.Read>(describePast(account, hash, 42))
        assertIs<DappCallMeaning.SafeExecution>(read.meaning)
        assertEquals(safe, read.safe)
        assertIs<SafeTxAction.ContractCall>(read.safeAction)
        assertEquals("Deposit", (read.result as ClearSignResult.ClearSigned).intent)

        // A token transfer is read by hand: nothing to describe.
        val transfer = Erc20.transferData(pool, UInt256.ONE)
        block = listOf(ChainTransaction(hash, user, proxy, UInt256.ZERO, "0x" + transfer.toHex()))
        val plain = assertIs<PastDescription.Read>(describePast(account, hash, 42))
        assertIs<DappCallMeaning.TokenTransfer>(plain.meaning)
        assertEquals(null, plain.result)
    }
}
