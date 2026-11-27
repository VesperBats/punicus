package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256

/**
 * The port for the transactions in a block, as the embedded light client verifies them: the
 * block's header is the one its verified head chain names, and its body is checked against the
 * header's `transactionsRoot`. What Send from another wallet (issue #255) reads to find the
 * transfer another wallet sent, since nothing comes back through the payment link. :data
 * implements it over the Myotis engine. Never throws.
 */
interface BlockTransactions {

    /**
     * The newest block on [network] whose transactions can be read verified now, or why none can.
     * A [network] no adapter serves is refused ([ReadUnavailable.NO_ENGINE]).
     */
    suspend fun head(network: Network): HeadReading

    /**
     * Every transaction of block [number] on [network], in the block's order, or
     * [BlockReading.NotYet] for a block there is no verified answer about yet.
     */
    suspend fun transactions(network: Network, number: Long): BlockReading

    /**
     * How far back [network]'s blocks can be read here ([BlockReach]): what an offer to read an older block says
     * before it is made. By default the light client's own window of [BlockReach.HEADER_WINDOW] blocks.
     */
    fun reach(network: Network): BlockReach = BlockReach.Window(BlockReach.HEADER_WINDOW)
}

/** How far back a network's blocks can be read verified on this device ([BlockTransactions.reach]). */
sealed interface BlockReach {
    /** None: this device's engine reads no block by number (Myotis's iOS module, for now). */
    data object None : BlockReach

    /** The last [blocks] blocks below the readable head: the light client's own window. */
    data class Window(val blocks: Long) : BlockReach

    /**
     * Any block: deeper than the window through the engine's bundled checkpoint table (Myotis's rule D1), with a walk
     * of up to [CHECKPOINT_SPAN] headers down from the nearest checkpoint, a heavier read.
     */
    data object Deep : BlockReach

    companion object {
        /** The light client's header window, as `getBlockByNumber` serves it from the head. */
        const val HEADER_WINDOW = 512L

        /** Every this many blocks the checkpoint table holds a hash: the most headers walked to reach a deep block. */
        const val CHECKPOINT_SPAN = 1024L
    }
}

/** What [BlockTransactions.head] answers. */
sealed interface HeadReading {

    /** Block [number] is the newest whose transactions can be read verified. */
    data class At(val number: Long) : HeadReading

    /** No block can be read verified now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : HeadReading
}

/** What [BlockTransactions.transactions] answers. */
sealed interface BlockReading {

    /** The block's [transactions], verified against its transactions root. */
    data class Read(val transactions: List<ChainTransaction>) : BlockReading

    /**
     * There is no verified answer about the block yet: it is above the verified head, or the engine
     * does not hold it yet though the head has passed it. Asked again later, it may be read.
     */
    data object NotYet : BlockReading

    /** The block cannot be read verified now, for [reason]. */
    data class Unavailable(val reason: ReadUnavailable) : BlockReading
}

/**
 * One transaction of a verified block: its [hash] (`0x` and 64 lower-case hex digits), its sender
 * [from] (recovered from its signature), its recipient [to] (null for a contract's creation), the
 * [value] it sends in wei, and its call data [input] (`0x` and lower-case hex, `0x` for none).
 */
data class ChainTransaction(
    val hash: String,
    val from: Address,
    val to: Address?,
    val value: UInt256,
    val input: String,
)
