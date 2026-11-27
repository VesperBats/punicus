package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.Eip1559Transaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BlockReach
import eth.serotine.domain.repository.BlockReading
import eth.serotine.domain.repository.HeadReading
import eth.serotine.domain.repository.NetworkSettings
import eth.serotine.domain.model.ClearSignedField
import eth.serotine.domain.model.hexToBytes
import eth.serotine.domain.model.toHex
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

/** Whether a past transaction can be described here ([DescribePastTransaction.availability]), before the tap. */
sealed interface PastAvailability {
    /** From this device's record of what it sent: no block is downloaded, only the contract's code is checked. */
    data object FromRecord : PastAvailability

    /** From its block, downloaded; [deep] when that block lies below the light client's window, a heavier read. */
    data class FromBlock(val deep: Boolean) : PastAvailability

    /** Not here, for [reason]: the offer is greyed out, saying so. */
    data class NotOffered(val reason: PastNotOffered) : PastAvailability
}

/** Why a past transaction cannot be described here. */
sealed interface PastNotOffered {
    /** Its network is off: turned on, it can be. */
    data object NetworkOff : PastNotOffered

    /** No block is known for it. */
    data object NoBlock : PastNotOffered

    /** This device's engine reads no block by number (Myotis's iOS module, for now). */
    data object NoBlockReads : PastNotOffered

    /** Its block lies below the last [blocks] blocks, all this network's light client reads (Sepolia's). */
    data class OutsideWindow(val blocks: Long) : PastNotOffered
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
 * *Past transactions*): its call is read from this device's record when it sent it, so no block is downloaded (the
 * contract's code is still checked), and otherwise from its block ([BlockTransactions], verified against the block's transactions root), whose size the
 * screen says before the tap. Then the built-in descriptions describe it as they would a call to sign, the contract's
 * code checked as it is now ([DescribeClearSigning]): for a proxy that code may not be the one that ran, which is
 * said ([PastDescription.Read.codeMayHaveChanged]). Nothing is kept.
 */
class DescribePastTransaction(
    private val history: TransactionHistory,
    private val blocks: BlockTransactions,
    private val describe: DescribeClearSigning,
    private val catalog: ClearSigningCatalog,
    /** The networks that are on: one that is off checks no code and reads no block. */
    private val networks: NetworkSettings,
) {
    /**
     * Whether the transaction [hash] on [network], in [block] when known, can be described here, before the tap: from
     * this device's record of it, from its block (and whether that block lies deeper than the light client's window,
     * a heavier read), or not, and why.
     */
    suspend fun availability(network: Network, hash: String, block: Long?): PastAvailability {
        if (network !in networks.observe().first()) return PastAvailability.NotOffered(PastNotOffered.NetworkOff)
        if (recorded(network, hash) != null) return PastAvailability.FromRecord
        block ?: return PastAvailability.NotOffered(PastNotOffered.NoBlock)
        val head = (blocks.head(network) as? HeadReading.At)?.number
        return when (val reach = blocks.reach(network)) {
            BlockReach.None -> PastAvailability.NotOffered(PastNotOffered.NoBlockReads)
            is BlockReach.Window -> if (head != null && block < head - reach.blocks) {
                PastAvailability.NotOffered(PastNotOffered.OutsideWindow(reach.blocks))
            } else {
                PastAvailability.FromBlock(deep = false)
            }
            BlockReach.Deep -> PastAvailability.FromBlock(deep = head != null && block < head - BlockReach.HEADER_WINDOW)
        }
    }

    /** The call this device's record holds of transaction [hash] on [network], when it sent it. */
    private suspend fun recorded(network: Network, hash: String): PastCall? {
        val wanted = hash.lowercase()
        val sent = (history.observe().first() as? HistoryList.Loaded)?.entries
            ?.firstOrNull { it.transfer.hash.lowercase() == wanted && it.transfer.network == network }?.transfer
            ?: return null
        return Eip1559Transaction.callOf(sent.signed.raw)?.let { PastCall(sent.account, it.to, it.value, it.data) }
    }

    /** Describes the transaction [hash] (`0x` and 64 hex digits) on [network], in [block] when known. */
    suspend operator fun invoke(network: Network, hash: String, block: Long?): PastDescription {
        val wanted = hash.lowercase()
        val recorded = recorded(network, wanted)
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
                // The adapter checked the input's shape already; read once more, straight into bytes.
                val input = tx?.input?.removePrefix("0x")?.lowercase()?.let { runCatching { it.hexToBytes() }.getOrNull() }
                when {
                    tx == null -> BlockCall.Missing(PastCallUnavailable.NotInBlock)
                    tx.to == null -> BlockCall.Missing(PastCallUnavailable.Creation)
                    input == null -> BlockCall.Missing(PastCallUnavailable.Unavailable(ReadUnavailable.UNPROVEN))
                    else -> BlockCall.Found(PastCall(tx.from, tx.to, tx.value, input))
                }
            }
        }

    /** The callees of [result]'s nested calls that are described themselves, at every depth. */
    private fun callees(result: ClearSignResult.ClearSigned): List<Address> = result.fields.flatMap { field ->
        val nested = field as? ClearSignedField.Nested ?: return@flatMap emptyList()
        val inner = nested.result as? ClearSignResult.ClearSigned ?: return@flatMap emptyList()
        listOf(nested.callee) + callees(inner)
    }

    private suspend fun described(network: Network, call: PastCall, downloaded: Boolean): PastDescription.Read {
        val data = call.data
        // What a dApp's request reads it as; a call too large for one is simply not decoded by hand.
        val meaning = if (data.size <= DappTransactionRequest.MAX_DATA_BYTES) {
            DappTransactionRequest(call.from, call.to, call.value, data, null).meaning
        } else {
            DappCallMeaning.NotDecoded(data.copyOf(4).toHex())
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
        // Every contract the description was bound to, its nested calls' callees too: a proxy among them may have run
        // other code then.
        val bound = target?.let { (_, to, _) -> listOf(to) + ((result as? ClearSignResult.ClearSigned)?.let(::callees) ?: emptyList()) }
            .orEmpty()
        val proxy = bound.any { address -> catalog.deployment(network, address)?.codePin?.let { it !is CodePin.Fixed } == true }
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
