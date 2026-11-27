package eth.serotine.ui.dapprequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.ClearSignFormatter
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NotCoveredReason
import eth.serotine.domain.model.SignInMessage
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DappMessageReview
import eth.serotine.domain.usecase.DappMessageSigning
import eth.serotine.domain.usecase.DappSignContent
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.domain.usecase.DappSignWarning
import eth.serotine.domain.usecase.DescribeClearSigning
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.PerAppSigner
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SignDappMessage
import eth.serotine.ui.components.ClearSignedSection
import eth.serotine.ui.components.DappSafeTxSection
import eth.serotine.ui.components.dappSafeTxSection
import eth.serotine.ui.components.safeTxRefusalText
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.usecase.ObserveSafe
import eth.serotine.domain.usecase.SafeReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import eth.serotine.domain.model.TraceClaims
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.TraceCall
import eth.serotine.domain.usecase.TraceOutcome
import eth.serotine.ui.components.TraceSection
import eth.serotine.ui.components.TraceText
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import eth.serotine.ui.components.ClearSignedText
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.components.biometricsOn
import eth.serotine.ui.components.biometricsStillOffered
import eth.serotine.ui.components.notSignedText
import eth.serotine.ui.components.epochSecondsNow
import eth.serotine.ui.platform.formatDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One field of a sign-in, display-ready. */
data class SignInField(val label: String, val value: String)

/** What a tap on a greyed-out item says. */
data class RequestExplanation(val title: String, val reason: String)

/**
 * A dApp's request to sign (docs/walletconnect.md, screens D4 and D5) as display-ready values: who
 * asks (the dApp's claim), the account asked to sign, on its network, and what: a message, field by field
 * when it is a sign-in, else as text, or as hex marked "not text"; or typed data, with what it lets
 * someone do when this app knows its struct, its domain and every field. The [warnings] to read
 * before signing, the [refusal] when it can only be refused, and the unlock. [gone] once the request
 * is no longer waiting; [done] once answered.
 */
data class DappRequestUiState(
    val loading: Boolean = true,
    val gone: Boolean = false,
    /** No longer waiting, and [notice] says why (it expired as Sign was tapped): Back leaves. */
    val ended: Boolean = false,
    /** "Sign-in request", "Message to sign", "Approval request", "Swap order to sign" or "Data to sign". */
    val title: String = "",
    val name: String = "",
    val url: String = "",
    val accountName: String? = null,
    /** The account asked to sign, EIP-55 checksummed, in full. */
    val accountAddress: String = "",
    /**
     * For a per-app address, the site it was made for, and a sign-in to a site the user linked to it said as such
     * ("Per-app address for uniswap.org"; issue #252, docs/walletconnect.md, *Signing in*); null otherwise.
     */
    val perAppLine: String? = null,
    /** The network the request is on, with its kind of funds ("On Sepolia, a test network"). */
    val network: String = "On Sepolia, a test network",
    /** The sign-in's fields, in the order EIP-4361 gives them; empty when the message is not a sign-in. */
    val signIn: List<SignInField> = emptyList(),
    /** The message as text, when it is text and not a sign-in shown field by field. */
    val messageText: String? = null,
    /** The message as hex, when it is not text. */
    val messageHex: String? = null,
    val messageSize: String = "",
    /** What the system's biometric prompt says is signed ("Sign in to a dApp"): nothing the dApp states. */
    val promptSubject: String = "",
    /** Typed data: whether the request is typed data, drawn as screen D5. */
    val typed: Boolean = false,
    /** What signing the typed data lets someone do; null when its struct is not one this app knows. */
    val meaning: String? = null,
    /** What to know first about what it lets someone do: each unlimited power a session key would get. */
    val meaningWarnings: List<String> = emptyList(),
    val meaningFields: List<SignInField> = emptyList(),
    /**
     * A Safe transaction to sign (`SafeTx`), as screen 17 decodes one: its Safe, what the light client says of it,
     * what it does, its nonce and hash (docs/walletconnect.md, *A Safe transaction to sign*); null for other data.
     */
    val safeTx: DappSafeTxSection? = null,
    /** The typed data's domain: who verifies the signature, on which chain. */
    val domainFields: List<SignInField> = emptyList(),
    /** Every field of the typed data's message. */
    val typedRows: List<TypedRow> = emptyList(),
    /**
     * Typed data no hand-written decoder knows, described by a built-in ERC-7730 descriptor bound to its verifying
     * contract's verified code (docs/clear-signing.md, A5); null when none describes it.
     */
    val described: ClearSignedSection? = null,
    /** Being looked up in the built-in descriptors, the verifying contract's code being checked. */
    val describing: Boolean = false,
    /** Why a built-in descriptor that covers the verifying contract does not describe it, where that says more. */
    val notDescribed: String? = null,
    val warnings: List<String> = emptyList(),
    /**
     * The public wallet-testing site's request it is, byte for byte, in a line ("A known test request: …"); null
     * when it is none (docs/walletconnect.md, *Testing with public test dApps*).
     */
    val knownTest: String? = null,
    val refusal: String? = null,
    /** How long the dApp waits for an answer. */
    val waits: String = "",
    val unlock: UnlockStep = UnlockStep(),
    val busy: Boolean = false,
    val notice: String? = null,
    val explaining: RequestExplanation? = null,
    val done: Boolean = false,
) {
    /** Sign is greyed out, still tappable, while it cannot be done (owner ruling, 2026-10-18, #55). */
    val signUnavailable: Boolean get() = refusal != null

    /** "Sign in" for a sign-in, "Sign" otherwise. */
    val signLabel: String get() = if (signIn.isNotEmpty()) "Sign in" else "Sign"
}

/**
 * The request-to-sign's ViewModel (D4 and D5): signs the message or the typed data with the wallet
 * password or a fingerprint or face, or refuses it. The password typed is held in the state while the
 * field is shown, handed to the signer in an [Unlock], never logged, and dropped once used and whenever
 * the screen is not shown (CLAUDE.md, *Trust*).
 */
class DappRequestViewModel(
    /** The request, as its route names it: `topic/id`. */
    requestKey: String,
    private val observeDappMessages: ObserveDappMessages,
    /** The built-in descriptors, for typed data no hand-written decoder knows (docs/clear-signing.md). */
    private val describeClearSigning: DescribeClearSigning,
    private val signDappMessage: SignDappMessage,
    private val refuseDappRequest: RefuseDappRequest,
    private val describeKeyStore: DescribeKeyStore,
    /** The Safe a `SafeTx` or a `SafeMessage` is for, read by the light client. */
    private val observeSafe: ObserveSafe,
    /** A `SafeTx`'s call traced as the Safe's own at the verified head (docs/clear-signing.md, *Phase B*). */
    private val traceCall: TraceCall,
    private val clock: () -> Long = ::epochSecondsNow,
    private val formatTime: (Long) -> String = ::formatDateTime,
    /** Where a trace is put in words: its size is the dApp's to choose, so never on the main thread. */
    private val formatOn: CoroutineDispatcher = Dispatchers.Default,
    /** When how long the dApp waits is said again: every 15 seconds while the screen is open. */
    ticks: Flow<Unit> = every15Seconds(),
) : ViewModel() {

    private val id: DappRequestId? = requestKey.split('/').takeIf { it.size == 2 }?.let { (topic, number) ->
        number.toLongOrNull()?.let { DappRequestId(DappSessionId(topic), it) }
    }

    private data class Local(
        val unlock: UnlockStep = UnlockStep(),
        val busy: Boolean = false,
        val notice: String? = null,
        val explaining: RequestExplanation? = null,
        val done: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    /** What the built-in descriptors say of the typed data, once asked; null before, or when asking failed. */
    private val description = MutableStateFlow<ClearSignResult?>(null)
    private val describing = MutableStateFlow(false)
    private var describeJob: Job? = null

    /**
     * Asks the built-in descriptors what the typed data lets someone do, for a struct no hand-written decoder knows:
     * at opening and each time the screen is shown again, so that a verifying contract upgraded while the request
     * waits is caught, and a check that could not be made is made again. The last answer stays meanwhile; one that
     * fails leaves the data not decoded, with no reason claimed.
     */
    private fun describe() {
        if (describeJob?.isActive == true) return
        describeJob = viewModelScope.launch {
            // The reviews as they are now, never a wait for one: a request answered, or not yet there, is no
            // description, and the next showing (or quote) asks again.
            val review = observeDappMessages().first().find { it.request.id == id } ?: return@launch
            val content = review.content as? DappSignContent.Typed ?: return@launch
            val network = review.request.network ?: return@launch
            // A Safe transaction's call not decoded here is described as the Safe's own call, as screen 17 does.
            val safeTx = content.meaning as? TypedDataMeaning.SafeTransaction
            val call = safeTx?.let { SafeTxAction.of(it.safe, it.tx) as? SafeTxAction.ContractCall }
            if (content.meaning != null && call == null) return@launch
            describing.value = true
            description.value = try {
                if (safeTx != null) {
                    describeClearSigning.call(network, safeTx.safe, safeTx.tx.to, safeTx.tx.value, safeTx.tx.data)
                } else {
                    describeClearSigning.typedData(network, review.address, content.typed.data)
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

    /** A `SafeTx`'s call as traced, once traced; null before, or for other data. */
    private val traced = MutableStateFlow<TraceSection?>(null)
    private var traceJob: Job? = null

    /**
     * Traces a `SafeTx`'s call at the verified head as the Safe's own call, as screen 17 does, once the description
     * is looked up, and holds it against what the call claims: its description, or what it is decoded as. A
     * refused one (a delegate call among them) is not traced. The last trace stays shown while the next is read.
     */
    fun retrace() {
        traceJob?.cancel()
        traceJob = viewModelScope.launch {
            val review = observeDappMessages().first().find { it.request.id == id } ?: return@launch
            val safeTx = (review.content as? DappSignContent.Typed)?.meaning as? TypedDataMeaning.SafeTransaction
                ?: return@launch
            val network = review.request.network ?: return@launch
            if (review.refusal != null) return@launch
            val tx = safeTx.tx
            val action = SafeTxAction.of(safeTx.safe, tx)
            traced.update { it?.copy(tracing = true) ?: TraceSection(tracing = true) }
            traced.value = try {
                describeJob?.join()
                val claims = if (action is SafeTxAction.ContractCall) description.value?.let(TraceClaims::of)
                else TraceClaims.ofSafeAction(action, safeTx.safe)
                val tracedAt = clock()
                val outcome = traceCall.trace(network, safeTx.safe, tx.to, tx.value, tx.data, claims)
                withContext(formatOn) {
                    TraceText.section(outcome, safeTx.safe, network, tracedAt).copy(note = TraceText.SAFE_CALL_NOTE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TraceText.section(TraceOutcome.Unavailable(ReadUnavailable.UNPROVEN), safeTx.safe, network)
            }
        }
    }

    init {
        describe()
        retrace()
        // A check that could not be made a moment ago (the light client catching up) is made again, every tick,
        // for as long as that is the answer: a refused read costs nothing.
        viewModelScope.launch {
            ticks.collect {
                if ((description.value as? ClearSignResult.NotCovered)?.reason == NotCoveredReason.CodeNotProven) describe()
            }
        }
    }

    init {
        viewModelScope.launch {
            val offered = try {
                biometricsOn(describeKeyStore())
            } catch (_: Exception) {
                false // the use case promises not to throw; the backstop: the password still works
            }
            local.update { it.copy(unlock = it.unlock.copy(biometrics = offered)) }
        }
    }

    /** The request's review as the screen shows it, worked out once per review, not per keystroke or tick. */
    private val shownReview = observeDappMessages()
        .map { reviews ->
            // A transaction is screen D6's, never this one's.
            reviews.find { it.request.id == id && it.content !is DappSignContent.Transaction }
                ?.let { review -> review to shown(review) }
        }
        .distinctUntilChanged()

    private val described = combine(description, describing, traced) { result, busy, trace -> Triple(result, busy, trace) }

    /** The Safe the typed data is for, as the light client reads it now; null for other data, or before. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val safeReading: Flow<SafeReading?> = shownReview
        .map { shown -> shown?.first?.let { review -> review.request.network?.let { it to safeOf(review) } } }
        .distinctUntilChanged()
        .flatMapLatest { at ->
            val safe = at?.second ?: return@flatMapLatest flowOf(null)
            observeSafe(AccountId(at.first, safe)).map<SafeReading, SafeReading?> { it }.onStart { emit(null) }
        }

    val uiState: StateFlow<DappRequestUiState> = combine(shownReview, local, ticks, described, safeReading) { shown, local, _, described, reading ->
        val (review, plain) = shown
            // No longer waiting: the screen leaves, unless an answer that did not take says why first.
            ?: return@combine if (local.notice != null && !local.done) {
                DappRequestUiState(loading = false, ended = true, notice = local.notice)
            } else {
                DappRequestUiState(loading = false, gone = true, done = local.done)
            }
        val base = withDescription(withSafe(plain, review, reading, described.third), review, described.first, described.second)
        base.copy(
            waits = waitsLine(review.request.expiresAtSeconds - clock()),
            unlock = local.unlock,
            busy = local.busy,
            notice = local.notice,
            explaining = local.explaining,
            done = local.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DappRequestUiState())

    private fun shown(review: DappMessageReview): DappRequestUiState {
        val common = DappRequestUiState(
            loading = false,
            name = review.request.dapp.name,
            url = review.request.dapp.url,
            accountName = review.account?.label,
            accountAddress = Eip55.format(review.address),
            network = onLine(review.request.network),
            perAppLine = review.perApp?.let(::perAppLine),
            warnings = review.warnings.sorted().map { warningLine(it, review.perApp) },
            knownTest = review.knownTest?.let(::knownTestLine),
            refusal = review.refusal?.let { refusal ->
                review.safeTxRefusal?.takeIf { refusal == DappSignRefusal.SAFE_TX_REFUSED }
                    ?.let { safeTxRefusalText(it, review.request.network ?: Network.ETHEREUM) }
                    ?: refusalLine(refusal)
            },
        )
        return when (val content = review.content) {
            is DappSignContent.Message -> {
                val signIn = content.signIn
                val text = content.message.text
                common.copy(
                    title = if (signIn != null) "Sign-in request" else "Message to sign",
                    promptSubject = if (signIn != null) "Sign in to a dApp" else "Sign a dApp's message",
                    signIn = signIn?.let(::fields).orEmpty(),
                    messageText = text.takeIf { signIn == null },
                    messageHex = content.message.hex.takeIf { text == null },
                    messageSize = bytesLine(content.message.size),
                )
            }
            // Filtered out above: a transaction is screen D6's.
            is DappSignContent.Transaction -> common
            is DappSignContent.Typed -> {
                val typed = TypedDataText(content, formatTime)
                common.copy(
                    // A permit is flagged by its type, whether or not anything describes it.
                    warnings = common.warnings + listOfNotNull(
                        TYPED_APPROVAL.takeIf { content.meaning == null && ClearSignFormatter.grantsApproval(content.typed.data) },
                    ),
                    title = typed.title,
                    promptSubject = typed.promptSubject,
                    typed = true,
                    meaning = typed.meaning,
                    meaningWarnings = typed.meaningWarnings,
                    meaningFields = typed.meaningFields,
                    domainFields = typed.domainFields,
                    typedRows = typed.rows,
                )
            }
        }
    }

    /** The Safe [review]'s typed data is for: a `SafeTx`'s or a `SafeMessage`'s verifying contract; null for other data. */
    private fun safeOf(review: DappMessageReview): Address? =
        when (val meaning = (review.content as? DappSignContent.Typed)?.meaning) {
            is TypedDataMeaning.SafeTransaction -> meaning.safe
            is TypedDataMeaning.SafeMessage -> meaning.safe
            else -> null
        }

    /** [state] with its Safe transaction's block, the Safe as [reading] says (null: not read yet). */
    private fun withSafe(
        state: DappRequestUiState,
        review: DappMessageReview,
        reading: SafeReading?,
        trace: TraceSection?,
    ): DappRequestUiState {
        val content = review.content as? DappSignContent.Typed ?: return state
        val meaning = content.meaning as? TypedDataMeaning.SafeTransaction ?: return state
        val network = review.request.network ?: return state
        val tx = meaning.tx
        return state.copy(
            safeTx = dappSafeTxSection(
                safe = meaning.safe,
                action = SafeTxAction.of(meaning.safe, tx),
                gasTerms = SafeTxGasTerms.of(tx),
                network = network,
                tokens = content.tokens.values,
                nonce = tx.nonce,
                hash = "0x" + content.typed.digest.hex,
                reading = reading,
                signer = review.address,
            ).let { section -> section.copy(action = section.action.copy(trace = trace)) },
        )
    }

    private fun fields(signIn: SignInMessage): List<SignInField> = buildList {
        add(SignInField("Site", signIn.scheme?.let { "$it://${signIn.domain}" } ?: signIn.domain))
        add(SignInField("Account", Eip55.format(signIn.address)))
        signIn.statement?.let { add(SignInField("Statement", it)) }
        add(SignInField("URI", signIn.uri))
        val network = Network.ofChainId(signIn.chainId)
        val networkName = network?.let { "${it.displayName} (${signIn.chainId})" } ?: "Chain ${signIn.chainId}"
        add(SignInField("Network", networkName))
        add(SignInField("Nonce", signIn.nonce))
        add(SignInField("Issued", formatTime(signIn.issuedAtSeconds)))
        signIn.expiresAtSeconds?.let { add(SignInField("Expires", formatTime(it))) }
        signIn.notBeforeSeconds?.let { add(SignInField("Not before", formatTime(it))) }
        signIn.requestId?.let { add(SignInField("Request ID", it)) }
        if (signIn.resources.isNotEmpty()) add(SignInField("Resources", signIn.resources.joinToString("\n")))
    }

    /**
     * [state] with what the built-in descriptors say of its typed data: a description replaces "not decoded";
     * an approval it grants is flagged whatever the description says; a reason worth saying is said.
     */
    private fun withDescription(
        state: DappRequestUiState,
        review: DappMessageReview,
        result: ClearSignResult?,
        busy: Boolean,
    ): DappRequestUiState {
        if (!state.typed) return state
        state.safeTx?.let { section ->
            // A Safe transaction's call: its description goes on its own card, as screen 17 shows one, and the
            // line saying it is not decoded goes.
            val notDecodedCall = warningLine(DappSignWarning.SAFE_CALL_NOT_DECODED, review.perApp)
            return state.copy(
                safeTx = section.copy(action = section.action.withDescription(result, busy)),
                warnings = if (result is ClearSignResult.ClearSigned) state.warnings - notDecodedCall else state.warnings,
            )
        }
        val notDecoded = warningLine(DappSignWarning.NOT_DECODED, review.perApp)
        return when (result) {
            is ClearSignResult.ClearSigned -> state.copy(
                described = ClearSignedText.describedSection(result),
                describing = busy,
                warnings = state.warnings.filterNot { it == notDecoded } + listOfNotNull(
                    ClearSignedText.PARTLY_NOT_DECODED.takeIf { ClearSignedText.partlyNotDecoded(result) },
                    TYPED_APPROVAL.takeIf { result.grantsApproval && TYPED_APPROVAL !in state.warnings },
                ),
            )
            is ClearSignResult.NotCovered -> state.copy(describing = busy, notDescribed = ClearSignedText.notDescribedLine(result.reason))
            null -> state.copy(describing = busy)
        }
    }

    /** The screen is shown again: the typed data is described, and a Safe's call traced, again, from fresh state. */
    fun onShown() {
        describe()
        retrace()
    }

    /** The password field now reads [text]. */
    fun onPasswordChange(text: String) = local.update {
        if (it.busy) it else it.copy(unlock = it.unlock.copy(password = WalletPassword(text), error = null))
    }

    /** Signs with the typed wallet password. */
    fun sign() {
        if (explainRefusal()) return
        val password = local.value.unlock.password
        if (local.value.busy || password.text.isEmpty()) return
        sign(Unlock.WithPassword(password))
    }

    /** Signs with a fingerprint or face. */
    fun signWithBiometrics() {
        if (explainRefusal()) return
        val state = uiState.value
        if (local.value.busy || !local.value.unlock.biometrics) return
        // What is signed, said in the prompt: the last thing read before the key signs. Nothing the dApp
        // states goes in it, since the system's prompt would show the dApp's words as the app's own.
        sign(Unlock.WithBiometrics("${state.promptSubject} with ${state.accountAddress}"))
    }

    /** Refuses the request: as declined, or with its refusal's error when it cannot be signed. */
    fun refuse() {
        val request = id ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY)) }
        viewModelScope.launch {
            val answer = try {
                withContext(NonCancellable) { refuseDappRequest(request) }
            } catch (_: Exception) {
                DappAnswer.NOT_SAVED // the use case promises not to throw; the backstop: the request still waits
            }
            local.update { it.copy(busy = false, done = answered(answer), notice = answerLine(answer)) }
        }
    }

    fun dismissExplanation() = local.update { it.copy(explaining = null) }

    /** The screen stopped being shown: the password goes. */
    fun onHidden() = local.update { it.copy(unlock = it.unlock.copy(password = WalletPassword.EMPTY)) }

    override fun onCleared() {
        // The screen is gone, and the password with it.
        onHidden()
    }

    private fun explainRefusal(): Boolean {
        val reason = uiState.value.refusal ?: return false
        local.update { it.copy(explaining = RequestExplanation("Cannot be signed", reason)) }
        return true
    }

    private fun sign(unlock: Unlock) {
        val request = id ?: return
        // The password leaves the state now: it is in the unlock, which the signer zeroes after use.
        local.update {
            it.copy(busy = true, notice = null, unlock = it.unlock.copy(password = WalletPassword.EMPTY, error = null))
        }
        viewModelScope.launch {
            val signing = try {
                withContext(NonCancellable) { signDappMessage(request, unlock) }
            } catch (_: Exception) {
                null // the use case promises not to throw; the backstop: nothing is said to be signed
            }
            local.update { after(it, signing) }
        }
    }

    private fun after(local: Local, signing: DappMessageSigning?): Local = when (signing) {
        is DappMessageSigning.Signed -> local.copy(busy = false, done = true)
        is DappMessageSigning.NotSigned -> local.copy(
            busy = false,
            unlock = local.unlock.copy(
                error = notSignedText(signing.signing, SIGN_FAILED),
                biometrics = local.unlock.biometrics && biometricsStillOffered(signing.signing),
            ),
        )
        is DappMessageSigning.Refused ->
            local.copy(busy = false, explaining = RequestExplanation("Cannot be signed", refusalLine(signing.refusal)))
        is DappMessageSigning.SafeTxRefused -> local.copy(
            busy = false,
            explaining = RequestExplanation("Cannot be signed", safeTxRefusalText(signing.refusal, Network.ETHEREUM)),
        )
        DappMessageSigning.Expired -> local.copy(busy = false, notice = EXPIRED)
        DappMessageSigning.Gone -> local.copy(busy = false, notice = GONE)
        DappMessageSigning.NotSaved -> local.copy(busy = false, notice = NOT_SAVED)
        null -> local.copy(busy = false, unlock = local.unlock.copy(error = SIGN_FAILED))
    }

    companion object {
        const val SIGN_FAILED = "Signing failed. Nothing was sent to the dApp."
        const val EXPIRED = "This request has expired: the dApp stopped waiting. Nothing was sent to it."
        const val GONE = "This request is no longer waiting: it was answered, or its connection ended."
        const val NOT_SAVED = "That could not be saved. Nothing was sent to the dApp."

        private fun answered(answer: DappAnswer): Boolean =
            answer == DappAnswer.DELIVERED || answer == DappAnswer.NOT_DELIVERED

        private fun answerLine(answer: DappAnswer): String? = when (answer) {
            DappAnswer.DELIVERED, DappAnswer.NOT_DELIVERED -> null
            DappAnswer.EXPIRED -> EXPIRED
            DappAnswer.NO_SUCH_REQUEST -> GONE
            DappAnswer.NOT_SAVED -> NOT_SAVED
            DappAnswer.OFF -> "This build has dApp connections switched off."
        }

        /** A tick now and every 15 seconds after. */
        fun every15Seconds(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(15_000)
            }
        }

        fun bytesLine(size: Int): String = if (size == 1) "1 byte" else "$size bytes"

        fun waitsLine(seconds: Long): String = when {
            seconds <= 0 -> "The dApp has stopped waiting for an answer."
            seconds < 120 -> "The dApp waits for an answer for about a minute more."
            seconds < 3_600 -> "The dApp waits for an answer for ${seconds / 60} more minutes."
            seconds < 2 * 86_400 -> "The dApp waits for an answer for ${seconds / 3_600} more hours."
            else -> "The dApp waits for an answer for ${seconds / 86_400} more days."
        }

        /**
         * A per-app address as D4 names it: the site it was made for; signing in to a site the user linked to it,
         * that site "linked by you" to it. A sign-in to a site of its own adds nothing: one site has one address.
         */
        fun perAppLine(signer: PerAppSigner): String {
            val made = "Per-app address for ${signer.site.name}"
            val linked = signer.signInSite?.takeIf { signer.linked }
            return if (linked == null) {
                made
            } else {
                "$made. Signing in to ${linked.name}, linked by you to ${signer.site.name}."
            }
        }

        /**
         * A known test request in a line: which site's test it is, and that its content alone says so, not who asks.
         */
        fun knownTestLine(known: eth.serotine.domain.model.KnownTestRequest): String =
            "A known test request: ${known.site}'s \"${known.name}\", byte for byte. Any site can send the same, so " +
                "this says what it is, not who is asking."

        /** Typed data that grants an approval, flagged whatever its description says. */
        const val TYPED_APPROVAL = "Signing this grants an approval: the spender may then take the tokens without " +
            "asking you again. It moves nothing itself."

        /**
         * What [warning] says; [perApp], the review's per-app signer, names the two sites of [DappSignWarning
         * .PER_APP_OTHER_SITE]. Required, so that no caller drops them.
         */
        fun warningLine(warning: DappSignWarning, perApp: PerAppSigner?): String = when (warning) {
            DappSignWarning.PER_APP_OTHER_SITE -> {
                val made = perApp?.site?.name ?: "another site"
                val domain = perApp?.signInSite?.name ?: "this site"
                "This address was made for $made. Signing in to $domain with it links the two sites: each can " +
                    "tell your activity on the other is yours."
            }
            DappSignWarning.DOMAIN_NOT_DAPP_URL ->
                "This sign-in is for another site than the one the dApp named when it connected. It may be a " +
                    "site posing as another. Both names are the dApp's own claim, so Punicus cannot tell which " +
                    "site is asking."
            DappSignWarning.NO_CHAIN_ID ->
                "This data names no network, so the signature may be taken on any chain where the same " +
                    "contract is."
            DappSignWarning.NOT_DECODED ->
                "Punicus does not know this kind of data, so it cannot tell what signing it lets someone do: " +
                    "it is shown field by field. Sign only if you trust this dApp and understand every field."
            DappSignWarning.SAFE_MESSAGE_HASH_ONLY ->
                "This asks you to sign, for the Safe, a message Punicus cannot show: the request holds only its " +
                    "hash. Signed by enough owners, it counts as the Safe's own signature, and a signed permit or " +
                    "order can let others take the Safe's tokens. Sign it only if you saw the message in the app " +
                    "that asked and trust it."
            DappSignWarning.SAFE_CALL_NOT_DECODED ->
                "Punicus does not decode this Safe transaction's call. Ordinary Safe{Wallet} transactions (a " +
                    "payment, a token or NFT sent, an approval, a change of owners, a batch of them) are decoded, " +
                    "so this one is unusual: check it with whoever proposed it before you sign."
            DappSignWarning.MODULE_CALL_NOT_DECODED ->
                "Punicus does not read the call this asks the Delay module to queue. Gnosis Pay's own changes (the " +
                    "card's limit) are read, so this one is unusual: once queued and executed, the Safe makes it as " +
                    "its own. Sign it only if you know what it does."
            DappSignWarning.SESSION_KEY_GRANT ->
                "This grants a session key power over a smart account: whoever holds the key may act for it " +
                    "without asking you again. Its data names no contract, so Punicus cannot check it against " +
                    "verified code: it is read from the data alone."
            DappSignWarning.NOT_A_SIGN_IN ->
                "This is not a sign-in. Punicus cannot tell what signing it commits you to: sign only if you " +
                    "trust this dApp and understand the message."
        }

        /**
         * The network a request is on, with its kind of funds: Sepolia keeps its testnet warning. One Punicus does
         * not serve (refused, whatever it asks) is said as such.
         */
        fun onLine(network: Network?): String = when {
            network == null -> "On a network Punicus does not connect dApps on"
            network.testnet -> "On ${network.displayName}, a test network"
            else -> "On ${network.displayName}, real funds"
        }

        fun refusalLine(refusal: DappSignRefusal): String = when (refusal) {
            DappSignRefusal.NOT_SESSION_ACCOUNT -> "It names an account this dApp was not given on this network."
            DappSignRefusal.OTHER_CHAIN -> "It names a network this connection does not hold."
            DappSignRefusal.NOT_IN_WALLET -> "The account is no longer in this wallet."
            DappSignRefusal.ACCOUNTS_UNREADABLE ->
                "Your accounts cannot be read right now, so the account cannot be checked."
            DappSignRefusal.NO_KEY -> "This account is watched: there is no key for it on this device."
            DappSignRefusal.NETWORK_OFF -> "Its network is off. Turn it on in Settings, under Networks."
            DappSignRefusal.SAFE -> "A Safe cannot sign messages here yet: that comes with Safe messages (EIP-1271)."
            DappSignRefusal.SIGN_IN_OTHER_ADDRESS -> "The sign-in names another account than the one asked to sign it."
            DappSignRefusal.SIGN_IN_OTHER_CHAIN -> "The sign-in names another network than the one it is asked on."
            DappSignRefusal.SIGN_IN_EXPIRED -> "The sign-in has expired."
            DappSignRefusal.SIGN_IN_NOT_YET_VALID -> "The sign-in is not valid yet."
            DappSignRefusal.TYPED_OTHER_CHAIN -> "The data names another network than the one it is asked on."
            DappSignRefusal.PERMIT_OTHER_OWNER -> "The approval is for another account than the one asked to sign it."
            DappSignRefusal.SAFE_GAS -> "It sets a gas limit, which a Safe's transaction cannot take: the account " +
                "that executes it sets the gas. Ask the dApp again without one, or refuse."
            DappSignRefusal.SAFE_SELF_CALL -> "It calls the Safe itself, which changes its owners, threshold, guard, " +
                "modules or fallback handler. A dApp cannot do that here: change the Safe's settings on its own screen."
            DappSignRefusal.TX_TO_ZERO_ADDRESS ->
                "It sends to the zero address: nobody holds its key, so what it sends would be lost for good."
            DappSignRefusal.SAFE_TX_REFUSED -> "Punicus does not sign, approve or execute this Safe transaction."
            DappSignRefusal.TYPED_TOO_LARGE ->
                "The data has more fields than this screen can show, and Punicus signs nothing it cannot show in full."
        }
    }
}
