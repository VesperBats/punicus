package eth.serotine.ui.activitydetail

import eth.serotine.ui.activity.ActivityState

/**
 * Everything the detail of one row of an account's Activity renders (docs/wallet-ux.md, screen 26):
 * a transaction this device sent, a token's transfer or a Safe's event, with every address and
 * hash it concerns in full, to copy, but for the sender a cautioned row names, which has no Copy.
 * Display-ready.
 */
sealed interface ActivityDetailUiState {

    /** The account's name, or its short address while it is not in the wallet. */
    val title: String

    /** Nothing read yet, or the part of the history it is in not checked yet. */
    data class Loading(override val title: String) : ActivityDetailUiState

    /**
     * What it did ([headline], as its row says it, with the blockie of [about], an EIP-55 address)
     * and how it ended ([status] and its [state]); then its [fields], each address and hash in full.
     * [note] when something about it needs saying beside its status. [addToken] is the EIP-55
     * contract of an ERC-20 transfer the wallet does not list, which Add token opens Add token by
     * address with (screen 20); null when there is none to add. [opensStatus] is the hash of a
     * transaction sent from this device, whose status (screen 9) Status opens; null for an event.
     * [simpleStatus] is [status] in simple mode's words, with no block ("Confirmed"); null where that
     * is [status] itself. [amountsHidden] while the user hides amounts (docs/wallet-ux.md, *Hiding
     * amounts*): the eye in the top bar is closed, and every amount here already reads "••••".
     */
    data class Shown(
        override val title: String,
        val headline: String,
        val about: String,
        val status: String,
        val state: ActivityState,
        val fields: List<DetailField>,
        val note: String? = null,
        val addToken: String? = null,
        val opensStatus: String? = null,
        val simpleStatus: String? = null,
        val amountsHidden: Boolean = false,
        /**
         * Describing its call on the user's tap (docs/clear-signing.md, *Past transactions*): offered where there is
         * a transaction to read; null where there is none.
         */
        val describe: PastDescribeSection? = null,
        /** The transaction Describe reads, where the row names one. */
        val describeAt: DescribeAt? = null,
    ) : ActivityDetailUiState

    /**
     * It cannot be shown, for the reason [message] gives; [retry] when asking again may answer (a check of the addr1
     * index that could not be made now), which Try again does.
     */
    data class Unavailable(
        override val title: String,
        val message: String,
        val retry: Boolean = false,
    ) : ActivityDetailUiState
}

/**
 * One line of a detail: what it is ([label]: "To", "Transaction hash") and its [value], in full.
 * An [DetailKind.ADDRESS] is an EIP-55 address, drawn with its blockie and the wallet's [name] for
 * it if it has one; a [DetailKind.HASH] is `0x` and 64 hex digits. Both are copied as [value],
 * unless [copyable] is false: the counterparty of a cautioned transfer (address poisoning protection)
 * is shown in full, to compare, but offers no Copy and cannot be selected.
 */
data class DetailField(
    val label: String,
    val value: String,
    val kind: DetailKind = DetailKind.TEXT,
    val name: String? = null,
    val copyable: Boolean = true,
)

/** What a [DetailField]'s value is, which says how it is drawn and whether it can be copied. */
enum class DetailKind {
    /** Words or a number, not copied. */
    TEXT,

    /** An EIP-55 address, with its blockie, and Copy unless the field is not [DetailField.copyable]. */
    ADDRESS,

    /** A transaction's or a message's hash, with Copy. */
    HASH,

    /** A block number: drawn as [TEXT], and kept behind *Details* in simple mode (docs/simple-ui.md). */
    BLOCK,
}

/** The transaction a row is of, for Describe: its [hash] and its [block] where one is known. */
data class DescribeAt(val hash: String, val block: Long?)

/**
 * Describing a past transaction's call on the user's tap (owner's decisions, 2026-11-27; docs/clear-signing.md,
 * *Past transactions*), display-ready. Each state carries what the tap reads and downloads ([offer]; [simpleOffer] in
 * simple mode's words, with no block number).
 */
sealed interface PastDescribeSection {
    val offer: String
    val simpleOffer: String

    /** Before the tap. */
    data class Offer(override val offer: String, override val simpleOffer: String) : PastDescribeSection

    /** Not here, for [reason]: Describe greyed out, a tap saying why (owner ruling, 2026-10-18, #55). */
    data class NotOffered(override val offer: String, override val simpleOffer: String, val reason: String) :
        PastDescribeSection

    /** Reading its call and checking the contract's code. */
    data class Working(override val offer: String, override val simpleOffer: String) : PastDescribeSection

    /**
     * What it found: [headline] (what the call did as Punicus reads it itself), the Safe transaction an execution
     * ran ([safeAction]), the description ([described]), and the [notes] that say what it rests on. [selectable] false
     * for a transaction a third party sent, whose addresses it chose (#324).
     */
    data class Described(
        override val offer: String,
        override val simpleOffer: String,
        val headline: String? = null,
        val safeAction: eth.serotine.ui.components.SafeTxActionItem? = null,
        val described: eth.serotine.ui.components.ClearSignedSection? = null,
        val notes: List<String> = emptyList(),
        val selectable: Boolean = true,
    ) : PastDescribeSection

    /** It could not be read, for [reason]; Describe may be tried again. */
    data class Failed(override val offer: String, override val simpleOffer: String, val reason: String) :
        PastDescribeSection
}
