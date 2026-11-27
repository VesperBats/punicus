package eth.serotine.ui.dapptransaction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.ClearSignFormatter
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSignedField
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.TraceClaims
import eth.serotine.domain.model.NotCoveredReason
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SyncPolicy
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.CallRefusal
import eth.serotine.domain.usecase.DappMessageReview
import eth.serotine.domain.usecase.DappSafeProposal
import eth.serotine.domain.usecase.DappSafeProposing
import eth.serotine.domain.usecase.ObserveSyncPolicy
import eth.serotine.domain.usecase.ProposeDappSafeTx
import eth.serotine.domain.usecase.SafeCallSimulation
import eth.serotine.domain.usecase.DappSafeSigning
import eth.serotine.domain.usecase.SafeTxSignCheck
import eth.serotine.domain.usecase.SignDappSafeTx
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.SafeTxRefusal
import eth.serotine.domain.usecase.DappSignContent
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.domain.usecase.DappSignWarning
import eth.serotine.domain.usecase.DappTransactionQuote
import eth.serotine.domain.usecase.DappTransactionQuoting
import eth.serotine.domain.usecase.DappTransactionSending
import eth.serotine.domain.usecase.DescribeClearSigning
import eth.serotine.domain.usecase.TraceCall
import eth.serotine.domain.usecase.TraceOutcome
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.QuoteDappTransaction
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SendDappTransaction
import eth.serotine.domain.usecase.SignedAnswer
import eth.serotine.domain.usecase.SignedState
import eth.serotine.domain.usecase.TransferSending
import eth.serotine.domain.repository.SentTransfer
import eth.serotine.ui.components.DAPP_REQUESTS_STAY_STRICT
import eth.serotine.ui.components.whileSyncing
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NotSentItem
import eth.serotine.ui.components.CHECK_TOOK_THE_TIME
import eth.serotine.ui.components.ClearSignedSection
import eth.serotine.ui.components.TraceSection
import eth.serotine.ui.components.TraceText
import eth.serotine.ui.components.ClearSignedText
import eth.serotine.ui.components.MIN_PROMPT_TIME
import eth.serotine.ui.components.PROMPT_TIMED_OUT
import eth.serotine.ui.components.QUOTE_MAX_AGE
import eth.serotine.ui.components.STALE_QUOTE
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.callRefusalText
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.components.notSentItem
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.ui.components.DappSafeTxSection
import eth.serotine.ui.components.dappSafeTxSection
import eth.serotine.ui.components.TokenCallText
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.SafeReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import eth.serotine.ui.components.promptDeadline
import eth.serotine.ui.dapprequest.DappRequestViewModel
import eth.serotine.ui.dapprequest.SignInField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What a tap on a greyed-out item says. */
data class TransactionExplanation(val title: String, val reason: String)

/**
 * A dApp's transaction (docs/walletconnect.md, screen D6) as display-ready values: who asks (the
 * dApp's claim), the account it is sent from, on its network, the recipient in full, the value, and the
 * call: decoded for the calls this app knows, else its data raw and marked not decoded. Then the quote,
 * worked out here from verified state: the fee at most, the balance left at least, and the call run as
 * a verified call at the head; or why there is none. The [warnings], the [refusal] when it can only be
 * refused, and the unlock. [gone] once the request is no longer waiting; [done] once answered.
 *
 * From a Safe ([fromSafe]) it is proposed as a Safe transaction instead: the quote is the Safe's (its nonce,
 * what it has left, the call run from it, who signs here), the unlock signs it as an owner held here and
 * queues it, and once it is queued ([queued]) the Safe transaction screen collects its signatures and
 * executes it; the dApp is told the hash of that execution.
 */
data class DappTransactionUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    /** No longer waiting, and [notice] says why: Back leaves. */
    val ended: Boolean = false,
    val name: String = "",
    val url: String = "",
    val accountName: String? = null,
    val accountAddress: String = "",
    /** The network the request is on, with its kind of funds ("On Sepolia, a test network"). */
    val network: String = "On Sepolia, a test network",
    /**
     * The Safe it is sent from, on the request's network, which its queued Safe transaction opens on (the same
     * address on another chain is another Safe); null for an account's transaction, or a network not served.
     */
    val safeAccount: AccountId? = null,
    /** The recipient, EIP-55 checksummed, in full. */
    val to: String = "",
    /** The value, in the network's currency, testnet marked. */
    val value: String = "",
    /** What the call does, in a sentence; null when it is not decoded. */
    val meaning: String? = null,
    val meaningFields: List<SignInField> = emptyList(),
    /**
     * The Safe transaction an owner's `execTransaction` runs, decoded as screen 17 decodes one, with its Safe as
     * the light client reads it (docs/walletconnect.md, *A Safe transaction to sign*); null for any other call.
     */
    val safeTx: DappSafeTxSection? = null,
    /**
     * A call the app does not decode, described by a built-in ERC-7730 descriptor bound to its verified code
     * (docs/clear-signing.md, A5); null when none describes it.
     */
    val described: ClearSignedSection? = null,
    /** Being looked up in the built-in descriptors, its code being checked. */
    val describing: Boolean = false,
    /**
     * What it does when it runs at the verified head, its effects and where they disagree with what it claims
     * (docs/clear-signing.md, *Phase B*); null before it is traced.
     */
    val trace: TraceSection? = null,
    /** Why a built-in descriptor that covers the contract does not describe this call, where that says more. */
    val notDescribed: String? = null,
    /** The call data as hex, when there is some, with its size. */
    val dataHex: String? = null,
    val dataSize: String = "",
    val warnings: List<String> = emptyList(),
    val refusal: String? = null,
    /** Being quoted. */
    val quoting: Boolean = false,
    /** The quote's lines (the fee at most, the balance after, the verified run), when quoted. */
    val quote: List<SignInField> = emptyList(),
    /** Why it is not quoted, when it is not: Try again quotes anew. */
    val notQuoted: String? = null,
    val waits: String = "",
    val unlock: UnlockStep = UnlockStep(),
    val busy: Boolean = false,
    val notSent: NotSentItem? = null,
    /** Refuse is greyed out, still tappable: a transaction signed for it may be on its way already. */
    val refuseUnavailable: Boolean = false,
    val notice: String? = null,
    val explaining: TransactionExplanation? = null,
    val done: Boolean = false,
    /** Sent from a Safe: proposed as a Safe transaction, which its owners sign and an account here executes. */
    val fromSafe: Boolean = false,
    /** The Safe transaction queued for it, `0x` and its 64 hex digits: opened from here. */
    val queued: String? = null,
    /** Just signed and queued: its Safe transaction screen opens now. */
    val openQueued: Boolean = false,
) {
    /** Send is greyed out, still tappable, while it cannot be done (owner ruling, 2026-10-18, #55). */
    val sendUnavailable: Boolean get() = refusal != null || quote.isEmpty()

    /** What signing is called: a Safe's transaction is signed and queued, an account's sent. */
    val signLabel: String get() = if (fromSafe) "Sign and queue" else "Send"
}

/**
 * Screen D6's ViewModel: quotes the dApp's transaction from verified state, and signs and sends it with
 * the wallet password or a fingerprint or face, or refuses it. A quote older than a minute, or one made
 * before the screen was last hidden, is made again before anything is signed. The password typed is held
 * while the field is shown, handed to the signer in an [Unlock], and dropped once used and whenever the
 * screen is not shown (CLAUDE.md, *Trust*). From a Safe it is proposed instead ([proposeDappSafeTx]),
 * signed by the first of its owners held here and queued ([signDappSafeTx]), under the same minute: the
 * Safe is checked first, within what is left of it, and leaving the screen stops that check.
 */
class DappTransactionViewModel(
    /** The request, as its route names it: `topic/id`. */
    requestKey: String,
    private val observeDappMessages: ObserveDappMessages,
    /** The built-in descriptors, for a call the app does not decode itself (docs/clear-signing.md). */
    private val describeClearSigning: DescribeClearSigning,
    /** The verified trace at the head, beside the description and the run (docs/clear-signing.md, *Phase B*). */
    private val traceCall: TraceCall,
    private val quoteDappTransaction: QuoteDappTransaction,
    private val sendDappTransaction: SendDappTransaction,
    private val proposeDappSafeTx: ProposeDappSafeTx,
    private val signDappSafeTx: SignDappSafeTx,
    private val refuseDappRequest: RefuseDappRequest,
    private val describeKeyStore: DescribeKeyStore,
    /** Whether the user chose *Sign while syncing*, which a dApp's request does not follow: its refusal says so. */
    observeSyncPolicy: ObserveSyncPolicy,
    /** The Safe an `execTransaction` is called on, read by the light client. */
    private val observeSafe: ObserveSafe,
    private val clock: () -> Long = ::epochSecondsNow,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** When how long the dApp waits is said again: every 15 seconds while the screen is open. */
    ticks: Flow<Unit> = DappRequestViewModel.every15Seconds(),
    /** Where a trace is put in words: its size is the dApp's to choose, so never on the main thread. */
    private val formatOn: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** Whether lenient mode is chosen: a refusal while syncing then says the setting does not reach this. */
    private val lenient = observeSyncPolicy().map { it == SyncPolicy.LENIENT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** In lenient mode, that the setting does not reach a dApp's request, after a [refusal] while syncing. */
    private fun lenientNote(refusal: CallRefusal): String =
        if (refusal.whileSyncing && lenient.value) DAPP_REQUESTS_STAY_STRICT else ""

    /** [lenientNote] of a Safe's refusal: its own syncing, the Safe's, or its quote's. */
    private fun lenientNote(refusal: SafeTxRefusal): String =
        if (refusal.whileSyncing && lenient.value) DAPP_REQUESTS_STAY_STRICT else ""

    private val id: DappRequestId? = requestKey.split('/').takeIf { it.size == 2 }?.let { (topic, number) ->
        number.toLongOrNull()?.let { DappRequestId(DappSessionId(topic), it) }
    }

    private data class Local(
        val quote: DappTransactionQuote? = null,
        val quotedAt: TimeMark? = null,
        val quoting: Boolean = false,
        val notQuoted: String? = null,
        val hidden: Boolean = false,
        val unlock: UnlockStep = UnlockStep(),
        val busy: Boolean = false,
        val notSent: Pair<NotSentItem, SentTransfer>? = null,
        /** The dApp asks for a gas limit below the verified estimate: refused as invalid, not declined. */
        val gasTooLow: Boolean = false,
        /** A transaction signed for this request was sent or mined: the dApp is being told its hash. */
        val signedAndSent: Boolean = false,
        val notice: String? = null,
        val explaining: TransactionExplanation? = null,
        val done: Boolean = false,
        /** From a Safe: proposed, not quoted. Known once the first quote or proposal is asked for. */
        val fromSafe: Boolean = false,
        /** A Safe's proposal, when proposed; made at [quotedAt]. */
        val proposal: DappSafeProposal? = null,
        /** The Safe transaction queued for it: `0x` and its hash. */
        val queued: String? = null,
        val openQueued: Boolean = false,
        /** The Safe is being checked before a signature: leaving the screen stops it. */
        val checking: Boolean = false,
    )

    private var checkJob: Job? = null

    private val local = MutableStateFlow(Local())

    /** What the built-in descriptors say of the call, once asked; null before. Asked once per screen. */
    private val description = MutableStateFlow<ClearSignResult?>(null)
    private val describing = MutableStateFlow(false)

    // Asked first by the quote the screen opens with (requote, below), then again with each new one.
    private var describeJob: Job? = null

    /**
     * Asks the built-in descriptors what the call does, for a call the app does not decode itself: at opening,
     * and again with each quote (a contract can be upgraded while the request waits, and a check that could not
     * be made a moment ago may be made now). The last answer stays on screen while the next is read; one that
     * fails leaves the call not decoded, with no reason claimed.
     */
    private fun describe() {
        if (describeJob?.isActive == true) return
        describeJob = viewModelScope.launch {
            // The reviews as they are now, never a wait for one: a request answered, or not yet there, is no
            // description, and the next showing (or quote) asks again.
            val review = observeDappMessages().first().find { it.request.id == id } ?: return@launch
            val transaction = (review.content as? DappSignContent.Transaction)?.transaction ?: return@launch
            val network = review.request.network ?: return@launch
            // The Safe transaction an execTransaction runs: its call not decoded here, described as the Safe's own.
            val execution = transaction.meaning as? DappCallMeaning.SafeExecution
            val inner = execution?.takeIf { it.call.action(it.safe) is SafeTxAction.ContractCall }?.call
            if (transaction.meaning !is DappCallMeaning.NotDecoded && inner == null) return@launch
            describing.value = true
            description.value = try {
                if (execution != null && inner != null) {
                    describeClearSigning.call(network, execution.safe, inner.to, inner.value, inner.data)
                } else {
                    describeClearSigning.call(network, review.address, transaction.to, transaction.value, transaction.data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                describing.value = false
            }
        }
    }

    /** The call's verified trace, once traced; null before. Traced anew with each quote, after the description. */
    private val traced = MutableStateFlow<TraceSection?>(null)
    private val tracing = MutableStateFlow(false)
    private var traceJob: Job? = null

    /**
     * Traces the call at the verified head, a Safe's as the Safe's own call, and holds it against what the call
     * claims: its hand-written decoder's meaning, or once looked up its description. A trace still running is
     * asked again with the claims as they are now (the use case waits for the engine's run already going), and
     * the last one stays on screen, its warnings with it, until the next is read. A request that can only be
     * refused is not traced.
     */
    fun retrace() {
        // A trace still running is asked again with the claims as they are now: the use case waits for the
        // engine's run already going rather than starting a second one.
        traceJob?.cancel()
        traceJob = viewModelScope.launch {
            val review = observeDappMessages().first().find { it.request.id == id } ?: return@launch
            val transaction = (review.content as? DappSignContent.Transaction)?.transaction ?: return@launch
            val network = review.request.network ?: return@launch
            if (review.refusal != null) return@launch
            tracing.value = true
            val self = coroutineContext[Job]
            try {
                // The claims of a call the app does not decode are its description's: wait for the lookup.
                describeJob?.join()
                val meaning = transaction.meaning
                if (meaning is DappCallMeaning.SafeExecution) {
                    // An Execute is traced as the Safe's own call, as screen 17 traces it: what the Safe runs, held
                    // against what it is decoded as. A delegate call is refused, and not traced.
                    val call = meaning.call
                    val action = call.action(meaning.safe)
                    if (call.operation == eth.serotine.domain.model.SafeOperation.DELEGATECALL) {
                        traced.value = null
                        return@launch
                    }
                    val claims = if (action is SafeTxAction.ContractCall) description.value?.let(TraceClaims::of)
                    else TraceClaims.ofSafeAction(action, meaning.safe)
                    val outcome = traceCall.trace(network, meaning.safe, call.to, call.value, call.data, claims)
                    traced.value = withContext(formatOn) {
                        TraceText.section(outcome, meaning.safe, network).copy(note = TraceText.SAFE_CALL_NOTE)
                    }
                    return@launch
                }
                val claims = if (meaning is DappCallMeaning.NotDecoded) description.value?.let(TraceClaims::of)
                else TraceClaims.ofMeaning(meaning, transaction.to)
                val outcome = traceCall.trace(network, review.address, transaction.to, transaction.value, transaction.data, claims)
                traced.value = withContext(formatOn) { TraceText.section(outcome, review.address, network) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                traced.value = TraceText.section(TraceOutcome.Unavailable(ReadUnavailable.UNPROVEN), review.address, network)
            } finally {
                // A run asked again ends after the next one began: only the current one says it is done.
                if (traceJob === self) tracing.value = false
            }
        }
    }

    /**
     * [section] beside the run quoted: a revert the trace shows while the run did not is warned about, as the
     * head having moved where the two blocks differ, and as not known where they are the same.
     */
    private fun withRun(section: TraceSection, local: Local): TraceSection {
        if (section.revert == null) return section
        // An account's quote always ran; a Safe's proposal only where the engine ran a call from the Safe.
        val quoted = local.quote?.call
        val proposed = local.proposal?.proposal?.simulation as? SafeCallSimulation.Ran
        if (quoted == null && proposed == null) return section
        val runBlock = quoted?.simulationBlock ?: proposed?.blockNumber
        val warning = if (runBlock != null && runBlock == section.block) TraceText.REVERTS_AT_THE_RUNS_BLOCK else TraceText.REVERTS_UNLIKE_RUN
        return section.copy(warnings = section.warnings + warning)
    }

    private data class Reviews(
        val description: ClearSignResult?,
        val describing: Boolean,
        val traced: TraceSection?,
        val tracing: Boolean,
    )

    private val described = combine(description, describing, traced, tracing, ::Reviews)

    private val shownReview = observeDappMessages()
        .map { reviews ->
            reviews.find { it.request.id == id && it.content is DappSignContent.Transaction }
                ?.let { review -> review to shown(review) }
        }
        .distinctUntilChanged()

    /** The Safe an `execTransaction` is called on, as the light client reads it now; null for other calls, or before. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val safeReading: Flow<SafeReading?> = shownReview
        .map { shown ->
            val review = shown?.first ?: return@map null
            val meaning = (review.content as? DappSignContent.Transaction)?.transaction?.meaning
            val safe = (meaning as? DappCallMeaning.SafeExecution)?.safe ?: (meaning as? DappCallMeaning.SafeApproveHash)?.safe
            review.request.network?.let { network -> safe?.let { AccountId(network, it) } }
        }
        .distinctUntilChanged()
        .flatMapLatest { safe ->
            if (safe == null) flowOf(null) else observeSafe(safe).map<SafeReading, SafeReading?> { it }.onStart { emit(null) }
        }

    private val reviewed = combine(described, safeReading, ::Pair)

    val uiState: StateFlow<DappTransactionUiState> = combine(shownReview, local, ticks, reviewed) { shown, local, _, reviewed ->
        val (described, reading) = reviewed
        val (review, plain) = shown
            ?: return@combine if (local.notice != null && !local.done) {
                DappTransactionUiState(loading = false, ended = true, notice = local.notice)
            } else {
                DappTransactionUiState(loading = false, gone = true, done = local.done)
            }
        val base = withDescription(withSafe(plain, review, reading), described.description, described.describing)
        val network = review.request.network
        // A dApp's Safe transaction is traced as the Safe's own call, which leaves out what executing it adds.
        val safe = base.fromSafe || local.fromSafe
        val trace = described.traced?.let { section -> withRun(if (safe) section.copy(note = TraceText.SAFE_CALL_NOTE) else section, local) }
            ?.let { if (described.tracing) it.copy(tracing = true) else it }
            ?: TraceSection(tracing = true).takeIf { described.tracing }
        base.copy(
            trace = trace,
            quoting = local.quoting,
            quote = local.quote?.let { quoteLines(it, review.request.network) }
                ?: local.proposal?.let { proposalLines(it, review.request.network) }.orEmpty(),
            notQuoted = local.notQuoted,
            waits = DappRequestViewModel.waitsLine(review.request.expiresAtSeconds - clock()),
            unlock = local.unlock,
            busy = local.busy,
            notSent = local.notSent?.first,
            refuseUnavailable = local.notSent?.second?.maybeSent == true || local.signedAndSent,
            notice = local.notice,
            explaining = local.explaining,
            done = local.done,
            fromSafe = base.fromSafe || local.fromSafe,
            queued = local.queued,
            openQueued = local.openQueued,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DappTransactionUiState())

    init {
        viewModelScope.launch {
            val offered = try {
                biometricsOn(describeKeyStore())
            } catch (_: Exception) {
                false // the use case promises not to throw; the backstop: the password still works
            }
            local.update { it.copy(unlock = it.unlock.copy(biometrics = offered)) }
        }
        requote(note = null)
    }

    /** Quotes the transaction anew: on opening, on Try again, and before signing a quote too old. */
    fun requote(note: String? = null) {
        val request = id ?: return
        if (local.value.busy || local.value.quoting) return
        describe()
        retrace()
        local.update {
            it.copy(quoting = true, notQuoted = null, gasTooLow = false, unlock = it.unlock.copy(error = note))
        }
        viewModelScope.launch {
            // A Safe's transaction is proposed as a Safe transaction, never quoted as an account's.
            val fromSafe = try {
                shownReview.first()?.first?.account?.kind == AccountKind.SAFE
            } catch (_: Exception) {
                local.value.fromSafe
            }
            if (fromSafe) return@launch propose(request)
            val network = requestNetwork()
            val quoting = try {
                quoteDappTransaction(request)
            } catch (_: Exception) {
                null // the use case promises not to throw
            }
            val mark = timeSource.markNow()
            local.update { current ->
                when (quoting) {
                    is DappTransactionQuoting.Quoted ->
                        current.copy(quoting = false, quote = quoting.quote, quotedAt = mark, hidden = false)
                    is DappTransactionQuoting.NotQuoted ->
                        current.copy(
                            quoting = false,
                            quote = null,
                            notQuoted = callRefusalText(quoting.refusal, network) + lenientNote(quoting.refusal),
                        )
                    is DappTransactionQuoting.GasTooLow -> current.copy(
                        quoting = false,
                        quote = null,
                        gasTooLow = true,
                        notQuoted = "The dApp asks for a gas limit of ${quoting.asked}, below the " +
                            "${quoting.estimate} " +
                            "the verified estimate needs: it would run out of gas. Ask the dApp again, or refuse.",
                    )
                    // The review says why: a refused request shows its refusal, and only Refuse.
                    is DappTransactionQuoting.Refused -> current.copy(quoting = false, quote = null)
                    is DappTransactionQuoting.SafeTxRefused -> current.copy(
                        quoting = false,
                        quote = null,
                        notQuoted = safeTxRefusalText(quoting.refusal, network),
                    )
                    DappTransactionQuoting.Expired -> current.copy(quoting = false, quote = null, notice = EXPIRED)
                    DappTransactionQuoting.Gone -> current.copy(quoting = false, quote = null)
                    is DappTransactionQuoting.AlreadySigned ->
                        signedEarlier(current.copy(quoting = false, quote = null), quoting.answer)
                    null -> current.copy(quoting = false, quote = null, notQuoted = QUOTE_FAILED)
                }
            }
        }
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = local.update {
        if (it.busy) it else it.copy(unlock = it.unlock.copy(password = WalletPassword(text), error = null))
    }

    /** Signs with the typed wallet password and sends. */
    fun send() {
        if (explainUnavailable()) return
        val password = local.value.unlock.password
        if (local.value.busy || password.text.isEmpty()) return
        if (local.value.fromSafe) return signSafe(freshProposal() ?: return, Unlock.WithPassword(password))
        val quote = freshQuote() ?: return
        send(quote, Unlock.WithPassword(password))
    }

    /** Signs with a fingerprint or face and sends. */
    fun sendWithBiometrics() {
        if (explainUnavailable()) return
        if (local.value.busy || !local.value.unlock.biometrics) return
        if (local.value.fromSafe) {
            val proposal = freshProposal() ?: return
            val notAfter = local.value.quotedAt?.let(::promptDeadline) ?: return requote(note = STALE_QUOTE)
            val owner = Eip55.format(proposal.heldOwners.first())
            val reason = "Sign a dApp's Safe transaction from ${uiState.value.accountAddress} " +
                "to ${uiState.value.to}, as the owner $owner"
            return signSafe(proposal, Unlock.WithBiometrics(reason, notAfter))
        }
        val quote = freshQuote() ?: return
        val notAfter = local.value.quotedAt?.let(::promptDeadline) ?: return requote(note = STALE_QUOTE)
        // What is signed, said in the prompt: nothing the dApp states.
        val reason = "Send a dApp's transaction from ${uiState.value.accountAddress} to ${uiState.value.to}"
        send(quote, Unlock.WithBiometrics(reason, notAfter))
    }

    /** Sends the transaction signed but not sent again, byte for byte. */
    fun retry() {
        val request = id ?: return
        val (_, transfer) = local.value.notSent ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val sending = try {
                withContext(NonCancellable) { sendDappTransaction.retry(request, transfer) }
            } catch (_: Exception) {
                null // the use case promises not to throw; the backstop: the retry stays offered
            }
            local.update { if (sending == null) it.copy(busy = false) else after(it, sending) }
        }
    }

    /** Refuses the request: as declined, or with its refusal's error when it cannot be sent. */
    fun refuse() {
        val request = id ?: return
        if (local.value.busy) return
        if (local.value.notSent?.second?.maybeSent == true || local.value.signedAndSent) {
            val reason = if (local.value.signedAndSent) BEING_TOLD else MAYBE_SENT
            local.update { it.copy(explaining = TransactionExplanation("Cannot be refused now", reason)) }
            return
        }
        val why = if (local.value.gasTooLow) DappRequestError.INVALID_PARAMS else null
        local.update { it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY)) }
        viewModelScope.launch {
            val answer = try {
                withContext(NonCancellable) { refuseDappRequest(request, why) }
            } catch (_: Exception) {
                DappAnswer.NOT_SAVED
            }
            val answered = answer == DappAnswer.DELIVERED || answer == DappAnswer.NOT_DELIVERED
            local.update { it.copy(busy = false, done = answered, notice = if (answered) null else answerLine(answer)) }
        }
    }

    fun dismissExplanation() = local.update { it.copy(explaining = null) }

    /** The screen stopped being shown: the password goes, and the quote is made again when it is shown. */
    fun onHidden() {
        // A Safe still being checked before its signature stops: no prompt comes up over another screen.
        if (local.value.checking) checkJob?.cancel()
        local.update {
            it.copy(
                unlock = it.unlock.copy(password = WalletPassword.EMPTY),
                hidden = true,
                busy = it.busy && !it.checking,
                checking = false,
            )
        }
    }

    /** The screen is shown again: a quote made before it was hidden is made again. */
    fun onShown() {
        val shown = local.value.quote != null || local.value.proposal != null
        if (local.value.hidden && shown && !local.value.busy) requote(note = null)
    }

    override fun onCleared() {
        onHidden()
    }

    /** The quote to sign, or null when it is made again first (too old, or made before the screen was hidden). */
    private fun freshQuote(): DappTransactionQuote? {
        val current = local.value
        val quote = current.quote ?: return null
        val age = current.quotedAt?.elapsedNow()
        if (current.hidden || age == null || age > QUOTE_MAX_AGE) {
            requote(note = STALE_QUOTE)
            return null
        }
        return quote
    }

    /** The proposal to sign, or null when it is made again first, as [freshQuote]. */
    private fun freshProposal(): DappSafeProposal? {
        val current = local.value
        val proposal = current.proposal ?: return null
        val age = current.quotedAt?.elapsedNow()
        if (current.hidden || age == null || age > QUOTE_MAX_AGE) {
            requote(note = STALE_QUOTE)
            return null
        }
        return proposal
    }

    /** The Safe transaction for the request: proposed, or the one queued for it already. */
    private suspend fun propose(request: DappRequestId) {
        val network = requestNetwork()
        val proposing = try {
            proposeDappSafeTx(request)
        } catch (_: Exception) {
            null // the use case promises not to throw
        }
        val mark = timeSource.markNow()
        local.update { current ->
            val base = current.copy(quoting = false, fromSafe = true, quote = null, proposal = null)
            when (proposing) {
                is DappSafeProposing.Proposed ->
                    base.copy(proposal = proposing.proposal, quotedAt = mark, hidden = false)
                is DappSafeProposing.AlreadyQueued -> base.copy(queued = "0x" + proposing.request.hash.hex)
                is DappSafeProposing.SafeRefused ->
                    base.copy(
                        notQuoted = safeTxRefusalText(proposing.refusal, network) + lenientNote(proposing.refusal),
                    )
                DappSafeProposing.InsufficientFunds -> base.copy(notQuoted = SAFE_SHORT)
                is DappSafeProposing.Reverts -> base.copy(
                    notQuoted = "The call reverts when run as a verified call from the Safe" +
                        (proposing.blockNumber?.let { " at block $it" } ?: "") +
                        ": it would fail on chain. Ask the dApp again, or refuse.",
                )
                DappSafeProposing.NoOwnerHere -> base.copy(notQuoted = NO_OWNER_HERE)
                is DappSafeProposing.NoLongerQueued -> base.copy(notQuoted = NO_LONGER_QUEUED)
                // The review says why: a refused request shows its refusal, and only Refuse.
                is DappSafeProposing.Refused, DappSafeProposing.Gone -> base
                DappSafeProposing.Expired -> base.copy(notice = EXPIRED)
                null -> base.copy(notQuoted = QUOTE_FAILED)
            }
        }
    }

    /** Signs [proposal] as the first of the Safe's owners held here, with [unlock], and queues it. */
    private fun signSafe(proposal: DappSafeProposal, unlock: Unlock) {
        val proposedAt = local.value.quotedAt ?: return requote(note = STALE_QUOTE)
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        local.update {
            it.copy(
                busy = true,
                checking = true,
                notice = null,
                unlock = it.unlock.copy(password = WalletPassword.EMPTY, error = null),
            )
        }
        // The check has what is left of the proposal's minute, less the prompt's least time when the
        // prompt signs: past that the signature could not be made in time anyway.
        val checkUntil = (unlock as? Unlock.WithBiometrics)?.notAfter?.let { it - MIN_PROMPT_TIME }
            ?: (proposedAt + QUOTE_MAX_AGE)
        checkJob = viewModelScope.launch {
            // The Safe is checked first, which leaving the screen cancels: no key is asked for, and no
            // prompt shown, until it has passed.
            val checked = try {
                signDappSafeTx.check(proposal, budget = -checkUntil.elapsedNow())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // the use case promises not to throw; the backstop
            }
            currentCoroutineContext().ensureActive()
            local.update { it.copy(checking = false) }
            val unproven = (checked as? SafeTxSignCheck.Refused)?.refusal ==
                SafeTxRefusal.SafeUnavailable(ReadUnavailable.UNPROVEN)
            if (checkUntil.hasPassedNow() && (checked is SafeTxSignCheck.Ready || unproven)) {
                // The check took the time the proposal had left: nothing is signed, it is made again.
                local.update { it.copy(busy = false) }
                requote(note = CHECK_TOOK_THE_TIME)
                return@launch
            }
            val signing = when (checked) {
                // Tied to the request, signed and queued as one step, which leaving cannot cut in half.
                is SafeTxSignCheck.Ready -> withContext(NonCancellable) {
                    try {
                        signDappSafeTx.sign(proposal, checked, unlock)
                    } catch (_: Exception) {
                        null // the use case promises not to throw; the backstop
                    }
                }
                is SafeTxSignCheck.Refused -> DappSafeSigning.Refused(checked.refusal)
                null -> null
            }
            local.update { current ->
                when (signing) {
                    is DappSafeSigning.Signed -> if (signing.saved) {
                        current.copy(busy = false, queued = "0x" + signing.request.hash.hex, openQueued = true)
                    } else {
                        current.copy(busy = false, unlock = current.unlock.copy(error = NOT_QUEUED))
                    }
                    is DappSafeSigning.NotSigned -> current.copy(
                        busy = false,
                        unlock = current.unlock.copy(
                            error = notSignedText(signing.signing, SIGN_FAILED),
                            biometrics = current.unlock.biometrics && biometricsStillOffered(signing.signing),
                        ),
                    )
                    is DappSafeSigning.Refused -> current.copy(
                        busy = false,
                        explaining = TransactionExplanation(
                            "Cannot be signed",
                            safeTxRefusalText(signing.refusal, proposal.proposal.request.network),
                        ),
                    )
                    DappSafeSigning.NotRecorded ->
                        current.copy(busy = false, unlock = current.unlock.copy(error = NOT_RECORDED))
                    null -> current.copy(busy = false, unlock = current.unlock.copy(error = SIGN_FAILED))
                }
            }
            if ((signing as? DappSafeSigning.NotSigned)?.signing == Signing.TimedOut) requote(note = PROMPT_TIMED_OUT)
            if (signing is DappSafeSigning.Refused) requote(note = null)
        }
    }

    /** The queued Safe transaction's screen was opened: it is not opened again by itself. */
    fun onQueuedOpened() = local.update { it.copy(openQueued = false) }

    private fun explainUnavailable(): Boolean {
        val state = uiState.value
        val reason = state.refusal ?: if (state.quote.isEmpty()) state.notQuoted ?: NOT_QUOTED_YET else return false
        val title = if (state.fromSafe) "Cannot be signed" else "Cannot be sent"
        local.update { it.copy(explaining = TransactionExplanation(title, reason)) }
        return true
    }

    private fun send(quote: DappTransactionQuote, unlock: Unlock) {
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        local.update {
            it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY, error = null))
        }
        viewModelScope.launch {
            val sending = try {
                withContext(NonCancellable) { sendDappTransaction(quote, unlock) }
            } catch (_: Exception) {
                null
            }
            local.update { after(it, sending) }
            if (((sending as? DappTransactionSending.NotSent)?.sending as? TransferSending.NotSigned)?.signing ==
                Signing.TimedOut
            ) {
                requote(note = PROMPT_TIMED_OUT)
            }
        }
    }

    /** Where [local] goes when [answer] was signed already as this request's answer: never signed again. */
    private fun signedEarlier(local: Local, answer: SignedAnswer): Local = when (answer.state) {
        SignedState.ON_ITS_WAY -> {
            val item = NotSentItem(
                "Signed earlier, but it may not have reached the network. Try again sends the same signed " +
                    "transaction, never a new one.",
                canRetry = true,
            )
            local.copy(busy = false, notSent = item to answer.transfer)
        }
        SignedState.SENT, SignedState.MINED ->
            local.copy(
                busy = false,
                notSent = null,
                signedAndSent = true,
                notice = "Sent already as ${answer.transfer.hash}: the dApp is being told.",
            )
        SignedState.DROPPED -> local.copy(
            busy = false,
            notSent = null,
            notice = "Signed earlier as ${answer.transfer.hash}, but another transaction of this account took its " +
                "nonce, so it can never be mined. Refuse answers the dApp; ask it again for a new one.",
        )
    }

    private fun after(local: Local, sending: DappTransactionSending?): Local = when (sending) {
        is DappTransactionSending.Sent -> when (sending.answer) {
            // No such request right after a send: the app's own answer (AnswerDappRequestsAtOnce) went first.
            DappAnswer.DELIVERED, DappAnswer.NOT_DELIVERED, DappAnswer.NO_SUCH_REQUEST ->
                local.copy(busy = false, notSent = null, done = true)
            // Sent all the same: said, never closed in silence; the record answers it once it can.
            else -> local.copy(
                busy = false,
                notSent = null,
                notice = "Sent as ${sending.hash}, but the dApp could not be told: " +
                    (answerLine(sending.answer) ?: "") + " It is in Activity.",
            )
        }
        is DappTransactionSending.Refused -> local.copy(
            busy = false,
            explaining = TransactionExplanation("Cannot be sent", refusalLine(sending.refusal)),
        )
        is DappTransactionSending.AlreadySigned -> signedEarlier(local, sending.answer)
        is DappTransactionSending.NotSent -> when (val inner = sending.sending) {
            is TransferSending.NotSigned -> local.copy(
                busy = false,
                unlock = local.unlock.copy(
                    error = notSignedText(inner.signing, SEND_FAILED),
                    biometrics = local.unlock.biometrics && biometricsStillOffered(inner.signing),
                ),
            )
            is TransferSending.NotSent ->
                local.copy(busy = false, notSent = notSentItem(inner.broadcast, false, "transaction") to inner.transfer)
            is TransferSending.RejectionUnverified ->
                local.copy(busy = false, notSent = notSentItem(inner.rejection, true, "transaction") to inner.transfer)
            // Sent is answered as DappTransactionSending.Sent.
            is TransferSending.Sent -> local.copy(busy = false, done = true)
        }
        DappTransactionSending.Expired -> local.copy(busy = false, notice = EXPIRED)
        DappTransactionSending.Gone -> local.copy(busy = false, notice = GONE)
        null -> local.copy(busy = false, unlock = local.unlock.copy(error = SEND_FAILED))
    }

    private fun shown(review: DappMessageReview): DappTransactionUiState {
        val content = review.content as DappSignContent.Transaction
        val transaction = content.transaction
        val token = content.tokens[transaction.to]
        return DappTransactionUiState(
            loading = false,
            name = review.request.dapp.name,
            url = review.request.dapp.url,
            accountName = review.account?.label,
            accountAddress = Eip55.format(review.address),
            network = DappRequestViewModel.onLine(review.request.network),
            safeAccount = review.request.network?.takeIf { review.account?.kind == AccountKind.SAFE }
                ?.let { AccountId(it, review.address) },
            to = Eip55.format(transaction.to),
            value = ether(transaction.value, review.request.network),
            meaning = meaningLine(transaction.meaning, token),
            meaningFields = meaningFields(transaction.meaning, token),
            dataHex = transaction.dataHex.takeIf { it.isNotEmpty() },
            dataSize = DappRequestViewModel.bytesLine(transaction.dataSize),
            warnings = buildList {
                if (transaction.meaning is DappCallMeaning.NotDecoded) add(NOT_DECODED)
                // The review's own, D6's not-decoded line aside: an unusual Safe call among them.
                review.warnings.filterNot { it == DappSignWarning.NOT_DECODED }.sorted()
                    .forEach { add(DappRequestViewModel.warningLine(it, review.perApp)) }
                if (transaction.meaning is DappCallMeaning.SafeApproveHash) add(APPROVE_HASH_WARNING)
                (transaction.meaning as? DappCallMeaning.TokenCallOn)?.let { meaning ->
                    TokenCallText(meaning.contract, meaning.call, token, "this account").warning?.let(::add)
                }
                // An address a testing site uses as a bad actor in its demos, said as such.
                review.testAddresses.forEach { (address, known) -> add(testAddressLine(address, known)) }
                // An approval is flagged by what the call is, whatever any description says or whether one does.
                if (transaction.meaning is DappCallMeaning.TokenApproval || ClearSignFormatter.grantsApproval(transaction.data)) {
                    add(APPROVAL_NOTE)
                }
            },
            refusal = review.refusal?.let { refusal ->
                review.safeTxRefusal?.takeIf { refusal == DappSignRefusal.SAFE_TX_REFUSED }
                    ?.let { safeTxRefusalText(it, review.request.network ?: Network.ETHEREUM) }
                    ?: refusalLine(refusal)
            },
            fromSafe = review.account?.kind == AccountKind.SAFE,
        )
    }

    /** [state] with the Safe transaction its `execTransaction` runs, the Safe as [reading] says (null: not read yet). */
    private fun withSafe(state: DappTransactionUiState, review: DappMessageReview, reading: SafeReading?): DappTransactionUiState {
        val content = review.content as? DappSignContent.Transaction ?: return state
        val network = review.request.network ?: return state
        val execution = content.transaction.meaning
        if (execution is DappCallMeaning.SafeApproveHash) {
            // Only the Safe is known: what the hash stands for is not in the call.
            val line = when (reading) {
                is SafeReading.Verified -> "A Safe ${reading.state.version}, verified by the light client: " +
                    "${reading.state.threshold} of ${reading.state.owners.size} owners, at nonce ${reading.state.nonce}."
                SafeReading.NotASafe -> "Not a Safe Punicus recognises, as its verified code says."
                else -> null
            }
            return line?.let { state.copy(meaningFields = state.meaningFields + SignInField("The Safe", it)) } ?: state
        }
        if (execution !is DappCallMeaning.SafeExecution) return state
        val call = execution.call
        return state.copy(
            safeTx = dappSafeTxSection(
                safe = execution.safe,
                action = call.action(execution.safe),
                gasTerms = call.gasTerms,
                network = network,
                tokens = content.tokens.values,
                nonce = null,
                hash = null,
                reading = reading,
                signer = review.address,
                executing = true,
            ),
        )
    }

    /**
     * [state] with what the built-in descriptors say of its call: a description replaces "not decoded"; an
     * approval it finds is flagged loudly whatever the description says; a reason worth saying is said.
     */
    private fun withDescription(state: DappTransactionUiState, result: ClearSignResult?, busy: Boolean): DappTransactionUiState =
        if (state.safeTx != null) {
            // The Safe transaction's call: its description goes on its own card, as screen 17 shows one, and the
            // line saying it is not decoded goes.
            val notDecoded = DappRequestViewModel.warningLine(DappSignWarning.SAFE_CALL_NOT_DECODED, null)
            state.copy(
                safeTx = state.safeTx.copy(action = state.safeTx.action.withDescription(result, busy)),
                warnings = if (result is ClearSignResult.ClearSigned) state.warnings - notDecoded else state.warnings,
            )
        } else when (result) {
            is ClearSignResult.ClearSigned -> {
                val partly = ClearSignedText.partlyNotDecoded(result)
                state.copy(
                    described = ClearSignedText.describedSection(result),
                    describing = busy,
                    // The selector row says nothing the description does not; the call data stays below.
                    meaningFields = emptyList(),
                    warnings = state.warnings.filterNot { it == NOT_DECODED } +
                        listOfNotNull(
                            ClearSignedText.PARTLY_NOT_DECODED.takeIf { partly },
                            APPROVAL_NOTE.takeIf { result.grantsApproval && APPROVAL_NOTE !in state.warnings },
                        ),
                )
            }
            is ClearSignResult.NotCovered -> state.copy(describing = busy, notDescribed = ClearSignedText.notDescribedLine(result.reason))
            null -> state.copy(describing = busy)
        }

    /** The network request [id] is on, as the latest review has it: Sepolia, a test network's, if it cannot be read. */
    private suspend fun requestNetwork(): Network =
        observeDappMessages().first().find { it.request.id == id }?.request?.network ?: Network.SEPOLIA

    private fun proposalLines(safe: DappSafeProposal, network: Network?): List<SignInField> {
        val proposal = safe.proposal
        val state = proposal.safe.state
        val nonce = proposal.request.nonce.toString() +
            if (proposal.queuedAhead > 0) ", behind ${proposal.queuedAhead} queued" else ""
        return listOfNotNull(
            SignInField("Safe transaction", "Nonce $nonce; ${state.threshold} of ${state.owners.size} owners sign it"),
            SignInField("Left in the Safe, at least", ether(proposal.balanceAfter, network)),
            when (val run = proposal.simulation) {
                is SafeCallSimulation.Ran -> SignInField(
                    "Verified run",
                    "Runs from the Safe without reverting" + (run.blockNumber?.let { " at block $it" } ?: ""),
                )
                SafeCallSimulation.NotRun ->
                    SignInField("Verified run", "Not run: the light client cannot run calls from a Safe on this build")
            },
            SignInField("Signs here as", Eip55.format(safe.heldOwners.first())),
            SignInField("Fee", "Paid by the account that executes it, quoted when it is executed"),
        )
    }

    private fun quoteLines(quote: DappTransactionQuote, network: Network?): List<SignInField> {
        val call = quote.call
        return listOfNotNull(
            SignInField("Fee, at most", ether(call.maxFee, network)),
            SignInField("Left in the account, at least", ether(call.nativeAfterAtLeast, network)),
            call.simulationBlock?.let { SignInField("Verified run", "Runs without reverting at block $it") }
                ?: SignInField("Verified run", "Not run: the light client cannot run calls on this build"),
            SignInField("Gas limit", call.transaction.gasLimit.toString()),
            SignInField("Nonce", call.transaction.nonce.toString()),
        )
    }

    companion object {
        const val EXPIRED = "This request has expired: the dApp stopped waiting. Nothing was sent."
        const val GONE = "This request is no longer waiting: it was answered, or its connection ended."
        const val SEND_FAILED = "Sending failed. Nothing was sent to the dApp."
        const val QUOTE_FAILED = "The transaction could not be worked out right now. Try again."
        const val NOT_QUOTED_YET = "The transaction is still being worked out from verified state."
        const val SIGN_FAILED = "Punicus could not finish this signature. Nothing was queued: try again."
        const val NOT_QUEUED = "Signed, but this device's queue cannot be saved right now, so nothing was queued: " +
            "sign it again in a moment."
        const val NOT_RECORDED = "This request could not be tied to its Safe transaction right now, so the signature " +
            "was dropped and nothing was queued: try again in a moment."
        const val NO_LONGER_QUEUED = "The Safe transaction proposed for this request is no longer queued here, " +
            "and it is never replaced by another one. Refuse answers the dApp; ask it again for a new one."
        const val SAFE_SHORT = "The Safe's verified balance, less what its queued transactions may move, cannot " +
            "pay this value. Refuse, or ask the dApp again once the Safe has more."
        const val NO_OWNER_HERE = "No owner of this Safe has its key on this device, so nobody here can sign it. " +
            "Add an owner's key to Punicus, or refuse."
        const val SAFE_NOTE = "From a Safe: this proposes a Safe transaction at the Safe's next nonce. Its owners " +
            "sign it, and an account here executes it from its screen; the dApp is told the hash of that execution."
        const val QUEUED_NOTE = "Queued as a Safe transaction. Collect the owners' signatures and execute it on its " +
            "screen: the dApp is told the hash once it is sent. Refuse tells the dApp no, and leaves it queued."
        const val BEING_TOLD = "The transaction signed for this request was sent, and the dApp is being told its " +
            "hash: it is no longer for refusing."
        const val MAYBE_SENT = "A transaction signed for this request may be on its way already. Try again, " +
            "or wait for it in Activity: refusing now would tell the dApp no while it may still be mined."
        const val NOT_DECODED = "Punicus does not decode this call, so it cannot tell what it does: its data is " +
            "shown as it is. Send it only if you trust this dApp."
        const val APPROVAL_NOTE = "An approval moves nothing itself, but the spender may then take the tokens " +
            "without asking you again."


        /**
         * [wei] in [network]'s native currency, a testnet's marked so (Sepolia's, and one that cannot be named,
         * shown as Sepolia's would be: no real funds are claimed for it).
         */
        fun ether(wei: UInt256, network: Network?): String {
            val shown = network ?: Network.SEPOLIA
            val testnet = if (shown.testnet) " (testnet)" else ""
            return "${wei.formatUnits(18)} ${shown.nativeSymbol}$testnet"
        }

        fun answerLine(answer: DappAnswer): String? = when (answer) {
            DappAnswer.DELIVERED, DappAnswer.NOT_DELIVERED -> null
            DappAnswer.EXPIRED -> EXPIRED
            DappAnswer.NO_SUCH_REQUEST -> GONE
            DappAnswer.NOT_SAVED -> "That could not be saved. Nothing was sent to the dApp."
            DappAnswer.OFF -> "This build has dApp connections switched off."
        }

        fun refusalLine(refusal: DappSignRefusal): String = DappRequestViewModel.refusalLine(refusal)

        /** [address], one a testing site uses as a bad actor in its demos ([known]), in a warning. */
        fun testAddressLine(address: eth.serotine.domain.model.Address, known: eth.serotine.domain.model.KnownTestAddress) =
            "${Eip55.format(address)} is the address ${known.site} uses as ${known.role} in its demos of malicious " +
                "requests. Never send it anything of value."

        /** An on-chain approval of a Safe transaction this call does not hold: what it approves is not known here. */
        const val APPROVE_HASH_WARNING = "This approves, on chain and for good, a Safe transaction Punicus cannot " +
            "see: only its hash is in the call, so neither what it does nor the Safe refusals can be checked. Find " +
            "the transaction by its hash where it was proposed, and approve only if you know it."

        private fun amountOf(amount: UInt256, token: Token?, address: String): String = when {
            amount == UInt256.MAX -> "an unlimited amount of ${token?.symbol ?: "the token"}"
            token != null -> "${amount.formatUnits(token.decimals)} ${token.symbol}"
            else -> "${amount.toDecimal()} units of the token at $address"
        }

        fun meaningLine(meaning: DappCallMeaning, token: Token?): String? = when (meaning) {
            DappCallMeaning.Payment -> "A payment of its value to the recipient."
            is DappCallMeaning.TokenTransfer -> "A transfer of " +
                "${amountOf(meaning.amount, token, Eip55.format(meaning.token))} to ${Eip55.format(meaning.recipient)}."
            is DappCallMeaning.TokenApproval -> "An approval: lets ${Eip55.format(meaning.spender)} take up to " +
                "${amountOf(meaning.amount, token, Eip55.format(meaning.token))} from this account."
            is DappCallMeaning.SafeExecution -> "Executes a transaction of the Safe ${Eip55.format(meaning.safe)} that " +
                "its owners signed: the Safe runs it now, at its nonce then. What it does is decoded below. This " +
                "account pays the fee."
            is DappCallMeaning.TokenCallOn -> TokenCallText(meaning.contract, meaning.call, token, "this account").sentence
            is DappCallMeaning.SafeExecutionNotDecoded -> "Calls execTransaction on ${Eip55.format(meaning.safe)}, " +
                "but its data is not exactly that function's: the Safe would still run a transaction from it, which " +
                "Punicus cannot read."
            is DappCallMeaning.SafeApproveHash -> "Approves, on chain and as one of its owners, the transaction of " +
                "the Safe ${Eip55.format(meaning.safe)} whose hash is 0x${meaning.hash.hex}. What that transaction " +
                "does is not in this call: check it where it was proposed, by its hash."
            is DappCallMeaning.NotDecoded -> null
        }

        fun meaningFields(meaning: DappCallMeaning, token: Token?): List<SignInField> = when (meaning) {
            is DappCallMeaning.TokenTransfer -> listOf(
                SignInField("Token", token?.let { "${it.symbol} · ${Eip55.format(meaning.token)}" }
                    ?: "${Eip55.format(meaning.token)} · not in the built-in token list"),
                SignInField("Recipient", Eip55.format(meaning.recipient)),
                SignInField("Amount", amountOf(meaning.amount, token, Eip55.format(meaning.token))),
            )
            is DappCallMeaning.TokenApproval -> listOf(
                SignInField("Token", token?.let { "${it.symbol} · ${Eip55.format(meaning.token)}" }
                    ?: "${Eip55.format(meaning.token)} · not in the built-in token list"),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amountOf(meaning.amount, token, Eip55.format(meaning.token))),
            )
            is DappCallMeaning.NotDecoded -> listOfNotNull(meaning.selector?.let { SignInField("Function", "0x$it") })
            is DappCallMeaning.SafeExecution -> listOf(
                SignInField("Owners' signatures", "${meaning.call.signaturesSize} bytes, checked by the Safe when it runs"),
            )
            is DappCallMeaning.SafeApproveHash -> listOf(SignInField("Safe transaction hash", "0x${meaning.hash.hex}"))
            is DappCallMeaning.SafeExecutionNotDecoded -> emptyList()
            is DappCallMeaning.TokenCallOn -> TokenCallText(meaning.contract, meaning.call, token, "this account")
                .fields.map { (label, value) -> SignInField(label, value) }
            DappCallMeaning.Payment -> emptyList()
        }
    }
}
