package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BlockReading
import eth.serotine.domain.repository.BlockTransactions
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.HistoryList
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TransactionHistory
import kotlinx.coroutines.flow.first

/**
 * A past transaction's call, read back: from [from] to [to] with [value] wei and [data]. Equal by value, [data]
 * included.
 */
class PastCall(val from: Address, val to: Address, val value: UInt256, data: ByteArray) {
    private val bytes = data.copyOf()

    /** The call data: a copy. */
    val data: ByteArray get() = bytes.copyOf()

    /** How many bytes of call data it has. */
    val dataSize: Int get() = bytes.size

    override fun equals(other: Any?): Boolean = other is PastCall && from == other.from && to == other.to &&
        value == other.value && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = listOf(from, to, value, bytes.contentHashCode()).hashCode()

    override fun toString(): String = "PastCall(${from.hex} → ${to.hex}, $value wei, ${bytes.size} bytes)"
}

/** What [DescribePastTransaction] came to. */
sealed interface PastDescription {

    /**
     * The transaction's [call], read from this device's record of it, or from its block ([downloaded]); what a
     * hand-written decoder reads it as ([meaning]); for a Safe's `execTransaction`, what the Safe ran ([safeAction],
     * at [safe]); and what a built-in description says of the call described (the Safe's inner call, else the call
     * itself), checked against the contract's code now ([result]; null where a hand-written decoder reads it, or
     * there is no call). [codeMayHaveChanged] when that contract is a proxy, whose code then may have been other
     * than the code checked now.
     */
    data class Read(
        val call: PastCall,
        val downloaded: Boolean,
        val meaning: DappCallMeaning,
        val safe: Address? = null,
        val safeAction: SafeTxAction? = null,
        val result: ClearSignResult? = null,
        val codeMayHaveChanged: Boolean = false,
    ) : PastDescription

    /** It could not be read, for [reason]. */
    data class NotRead(val reason: PastCallUnavailable) : PastDescription
}

/** Why a past transaction's call could not be read. */
sealed interface PastCallUnavailable {
    /** No block is known for it: it was never seen in one. */
    data object NoBlock : PastCallUnavailable

    /** Its block is not served now: no peer held it, or the light client has not reached it. */
    data object BlockNotServed : PastCallUnavailable

    /** Its block was read, and holds no transaction of that hash. */
    data object NotInBlock : PastCallUnavailable

    /** It created a contract: it has no recipient to describe a call of. */
    data object Creation : PastCallUnavailable

    /** Nothing could be verified now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : PastCallUnavailable
}

/**
 * Describes a past transaction of an account on the user's tap (owner's decisions, 2026-11-27; docs/clear-signing.md,
 * *Past transactions*): its call is read from this device's record when it sent it, so nothing is downloaded, and
 * otherwise from its block ([BlockTransactions], verified against the block's transactions root), whose size the
 * screen says before the tap. Then the built-in descriptions describe it as they would a call to sign, the contract's
 * code checked as it is now ([DescribeClearSigning]): for a proxy that code may not be the one that ran, which is
 * said ([PastDescription.Read.codeMayHaveChanged]). Nothing is kept.
 */
class DescribePastTransaction(
    private val history: TransactionHistory,
    private val blocks: BlockTransactions,
    private val describe: DescribeClearSigning,
    private val catalog: ClearSigningCatalog,
) {
    /** Describes the transaction [hash] (`0x` and 64 hex digits) of [account], in [block] when known. */
    suspend operator fun invoke(account: AccountId, hash: String, block: Long?): PastDescription {
        val network = account.network
        val wanted = hash.lowercase()
        val sent = (history.observe().first() as? HistoryList.Loaded)?.entries
            ?.firstOrNull { it.transfer.hash.lowercase() == wanted && it.transfer.network == network }?.transfer
        val recorded = sent?.let { transfer ->
            Eip1559Transaction.callOf(transfer.signed.raw)?.let { PastCall(transfer.account, it.to, it.value, it.data) }
        }
        if (recorded != null) return described(network, recorded, downloaded = false)
        return when (val read = fromBlock(network, wanted, block ?: return PastDescription.NotRead(PastCallUnavailable.NoBlock))) {
            is BlockCall.Found -> described(network, read.call, downloaded = true)
            is BlockCall.Missing -> PastDescription.NotRead(read.reason)
        }
    }

    private sealed interface BlockCall {
        data class Found(val call: PastCall) : BlockCall
        data class Missing(val reason: PastCallUnavailable) : BlockCall
    }

    /** The call of transaction [hash] as its block [number] holds it, or why it could not be read. */
    private suspend fun fromBlock(network: Network, hash: String, number: Long): BlockCall =
        when (val reading = blocks.transactions(network, number)) {
            BlockReading.NotYet -> BlockCall.Missing(PastCallUnavailable.BlockNotServed)
            is BlockReading.Unavailable -> BlockCall.Missing(PastCallUnavailable.Unavailable(reading.reason))
            is BlockReading.Read -> {
                val tx = reading.transactions.firstOrNull { it.hash.lowercase() == hash }
                val input = tx?.input?.removePrefix("0x")
                when {
                    tx == null || input == null -> BlockCall.Missing(PastCallUnavailable.NotInBlock)
                    tx.to == null -> BlockCall.Missing(PastCallUnavailable.Creation)
                    input.length % 2 != 0 || input.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' } ->
                        BlockCall.Missing(PastCallUnavailable.Unavailable(ReadUnavailable.UNPROVEN))
                    else -> BlockCall.Found(
                        PastCall(tx.from, tx.to, tx.value, input.chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
                    )
                }
            }
        }

    private suspend fun described(network: Network, call: PastCall, downloaded: Boolean): PastDescription.Read {
        val data = call.data
        // What a dApp's request reads it as; a call too large for one is simply not decoded by hand.
        val meaning = if (data.size <= DappTransactionRequest.MAX_DATA_BYTES) {
            DappTransactionRequest(call.from, call.to, call.value, data, null).meaning
        } else {
            DappCallMeaning.NotDecoded(data.copyOf(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        }
        val execution = meaning as? DappCallMeaning.SafeExecution
        val safeAction = execution?.let { it.call.action(it.safe) }
        // The call described: what the Safe ran, as its own call, or the call itself where nothing reads it by hand.
        val target = when {
            execution != null && safeAction is SafeTxAction.ContractCall ->
                Triple(execution.safe, execution.call.to, execution.call)
            meaning is DappCallMeaning.NotDecoded -> Triple(call.from, call.to, null)
            else -> null
        }
        val result = target?.let { (from, to, inner) ->
            if (inner != null) {
                describe.call(network, from, to, inner.value, inner.data)
            } else {
                describe.call(network, from, to, call.value, data)
            }
        }
        val proxy = target?.let { (_, to, _) -> catalog.deployment(network, to)?.codePin }
            ?.let { it !is CodePin.Fixed } == true
        return PastDescription.Read(
            call = call,
            downloaded = downloaded,
            meaning = meaning,
            safe = execution?.safe,
            safeAction = safeAction,
            result = result,
            codeMayHaveChanged = proxy && result is ClearSignResult.ClearSigned,
        )
    }
}
