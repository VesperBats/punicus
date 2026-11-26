package eth.serotine.ui.dapprequest

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchedCall
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeMultiSend
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.SafeState
import eth.serotine.domain.usecase.SafeReading
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.dappSafeTxSection
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.usecase.DappSignRefusal
import eth.serotine.domain.usecase.DappSignWarning
import eth.serotine.domain.usecase.PerAppSigner
import eth.serotine.ui.components.ClearSignedParameter
import eth.serotine.ui.components.ClearSignedRow
import eth.serotine.ui.components.ClearSignedSection
import eth.serotine.ui.components.ClearSignedText
import eth.serotine.ui.components.UnlockStep
import eth.serotine.ui.ScreenPreview
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// A dApp's message request in Android Studio's preview pane, one preview per state it draws
// differently. DappRequestScreenPreviewsTest renders each one in dappRequestScreenPreviews, at the end.

private const val ACCOUNT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

private val signIn = DappRequestUiState(
    loading = false,
    title = "Sign-in request",
    name = "Example dApp",
    url = "https://app.example.org",
    accountName = "Main",
    accountAddress = ACCOUNT,
    signIn = listOf(
        SignInField("Site", "app.example.org"),
        SignInField("Account", ACCOUNT),
        SignInField("Statement", "Sign in to Example dApp. This request will not trigger a transaction."),
        SignInField("URI", "https://app.example.org/login"),
        SignInField("Network", "Sepolia (11155111)"),
        SignInField("Nonce", "k3T9qL2mVx"),
        SignInField("Issued", "Nov 3, 2026, 10:00 AM"),
        SignInField("Expires", "Nov 3, 2026, 10:10 AM"),
    ),
    messageSize = "412 bytes",
    waits = DappRequestViewModel.waitsLine(240),
)

private val message = signIn.copy(
    title = "Message to sign",
    signIn = emptyList(),
    messageText = "Welcome to Example dApp!\n\nClick to accept the terms of service.\n\nNonce: 81726354",
    messageSize = "86 bytes",
    warnings = listOf(DappRequestViewModel.warningLine(DappSignWarning.NOT_A_SIGN_IN, null)),
)

private const val SPENDER = "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"
private const val USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

private val approval = signIn.copy(
    title = "Approval request",
    signIn = emptyList(),
    typed = true,
    meaning = "Lets 0x3fC9…7FAD take up to 250 USDC from this account, until Nov 3, 2026, 11:00 AM. " +
        TypedDataText.APPROVAL_NOTE,
    meaningFields = listOf(
        SignInField("Token", "USDC · $USDC"),
        SignInField("Spender", SPENDER),
        SignInField("Amount", "250 USDC"),
        SignInField("Until", "Nov 3, 2026, 11:00 AM"),
    ),
    domainFields = listOf(
        SignInField("Name", "USDC"),
        SignInField("Version", "2"),
        SignInField("Network", "Sepolia (11155111)"),
        SignInField("Contract", USDC),
    ),
    typedRows = listOf(
        TypedRow(0, "owner", ACCOUNT),
        TypedRow(0, "spender", SPENDER),
        TypedRow(0, "value", "250000000"),
        TypedRow(0, "nonce", "0"),
        TypedRow(0, "deadline", "1793703600"),
    ),
)

private const val PERMIT2 = "0x000000000022D473030F116dDEE9F6B43aC78BA3"
private const val WETH = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
private const val MAINNET_USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

/** A Permit2 batch, which no hand-written decoder knows: described by Permit2's built-in descriptor. */
private val permitBatch = approval.copy(
    title = "Data to sign",
    meaning = null,
    meaningFields = emptyList(),
    network = "On Ethereum, real funds",
    domainFields = listOf(
        SignInField("Name", "Permit2"),
        SignInField("Network", "Ethereum (1)"),
        SignInField("Contract", PERMIT2),
    ),
    typedRows = listOf(
        TypedRow(0, "details", "2 items"),
        TypedRow(1, "[0]", null),
        TypedRow(2, "token", MAINNET_USDC),
        TypedRow(2, "amount", "2500000000"),
        TypedRow(2, "expiration", "1782864000"),
        TypedRow(2, "nonce", "0"),
        TypedRow(1, "[1]", null),
        TypedRow(2, "token", WETH),
        TypedRow(2, "amount", "750000000000000000"),
        TypedRow(2, "expiration", "1782864000"),
        TypedRow(2, "nonce", "0"),
        TypedRow(0, "spender", SPENDER),
        TypedRow(0, "sigDeadline", "1774915200"),
    ),
    warnings = listOf(DappRequestViewModel.TYPED_APPROVAL),
)

private val typedDescribed = permitBatch.copy(
    described = ClearSignedSection(
        intent = "Authorize spending of tokens",
        describedBy = "Described by Uniswap Labs, ${ClearSignedText.DESCRIBED_BY}",
        rows = listOf(
            ClearSignedRow(0, "Spender", SPENDER, raw = null, note = null),
            ClearSignedRow(0, "Amount allowance", "2500 USDC", raw = "2500000000", note = null),
            ClearSignedRow(0, "Amount allowance", "0.75 WETH", raw = "750000000000000000", note = null),
            ClearSignedRow(0, "Approval expires", "2026-07-01 00:00:00Z", raw = "1782864000", note = null),
            ClearSignedRow(0, "Approval expires", "2026-07-01 00:00:00Z", raw = "1782864000", note = null),
        ),
        otherParameters = listOf(
            ClearSignedParameter("details.[0].token", MAINNET_USDC),
            ClearSignedParameter("details.[0].nonce", "0"),
            ClearSignedParameter("details.[1].token", WETH),
            ClearSignedParameter("details.[1].nonce", "0"),
            ClearSignedParameter("sigDeadline", "1774915200"),
        ),
    ),
)

private val typedDescribing = permitBatch.copy(
    describing = true,
    warnings = permitBatch.warnings + DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED, null),
)

private val typedContractChanged = permitBatch.copy(
    notDescribed = ClearSignedText.CONTRACT_CHANGED,
    warnings = permitBatch.warnings + DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED, null),
)

private val notDecoded = approval.copy(
    title = "Data to sign",
    meaning = null,
    meaningFields = emptyList(),
    domainFields = listOf(
        SignInField("Name", "Example dApp"),
        SignInField("Version", "1"),
        SignInField("Network", "Sepolia (11155111)"),
        SignInField("Contract", "0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"),
    ),
    typedRows = listOf(
        TypedRow(0, "from", null),
        TypedRow(1, "name", "Cow"),
        TypedRow(1, "wallets", "2 items"),
        TypedRow(2, "[0]", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"),
        TypedRow(2, "[1]", "0xDeaDbeefdEAdbeefdEadbEEFdeadbeEFdEaDbeeF"),
        TypedRow(0, "contents", "Hello, Bob!"),
    ),
    warnings = listOf(DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED, null)),
)

@Composable
private fun DappRequestPreview(state: DappRequestUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        DappRequestScreen(
            state,
            onPasswordChange = {},
            onSign = {},
            onSignWithBiometrics = {},
            onRefuse = {},
            onDismissExplanation = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun DappRequestSignInPreview() = DappRequestPreview(signIn)

@Preview
@Composable
private fun DappRequestSignInDarkPreview() = DappRequestPreview(signIn, darkTheme = true)

@Preview
@Composable
private fun DappRequestBiometricsPreview() =
    DappRequestPreview(signIn.copy(unlock = UnlockStep(password = WalletPassword("secret"), biometrics = true)))

@Preview
@Composable
private fun DappRequestOtherSitePreview() = DappRequestPreview(
    signIn.copy(
        signIn = signIn.signIn.map { if (it.label == "Site") it.copy(value = "app.example.org.evil.test") else it },
        warnings = listOf(DappRequestViewModel.warningLine(DappSignWarning.DOMAIN_NOT_DAPP_URL, null)),
    ),
)

@Preview
@Composable
private fun DappRequestMessagePreview() = DappRequestPreview(message)

@Preview
@Composable
private fun DappRequestHexPreview() = DappRequestPreview(
    message.copy(messageText = null, messageHex = "4f2a91c3e8b07d5566d0a1f2c3b4e5d6deadbeef", messageSize = "20 bytes"),
)

@Preview
@Composable
private fun DappRequestRefusedPreview() = DappRequestPreview(
    signIn.copy(refusal = DappRequestViewModel.refusalLine(DappSignRefusal.SIGN_IN_EXPIRED)),
)

@Preview
@Composable
private fun DappRequestWrongPasswordPreview() = DappRequestPreview(
    signIn.copy(unlock = UnlockStep(error = "That is not the wallet password. Nothing was signed.")),
)

@Preview
@Composable
private fun DappRequestSigningPreview() = DappRequestPreview(signIn.copy(busy = true))

@Preview
@Composable
private fun DappRequestExplainingPreview() = DappRequestPreview(
    signIn.copy(
        refusal = DappRequestViewModel.refusalLine(DappSignRefusal.SAFE),
        explaining = RequestExplanation("Cannot be signed", DappRequestViewModel.refusalLine(DappSignRefusal.SAFE)),
    ),
)

@Preview
@Composable
private fun DappRequestEndedPreview() = DappRequestPreview(
    DappRequestUiState(loading = false, ended = true, notice = DappRequestViewModel.EXPIRED),
)

@Preview
@Composable
private fun DappRequestLoadingPreview() = DappRequestPreview(DappRequestUiState())

@Preview
@Composable
private fun DappRequestApprovalPreview() = DappRequestPreview(approval)

@Preview
@Composable
private fun DappRequestApprovalDarkPreview() = DappRequestPreview(approval, darkTheme = true)

@Preview
@Composable
private fun DappRequestUnlimitedPreview() = DappRequestPreview(
    approval.copy(
        meaning = "Lets 0x3fC9…7FAD take up to an unlimited amount of USDC from this account through Permit2, " +
            "until Dec 3, 2026, 11:00 AM. " + TypedDataText.APPROVAL_NOTE,
        meaningFields = listOf(
            SignInField("Token", "USDC · $USDC"),
            SignInField("Spender", SPENDER),
            SignInField("Amount", "an unlimited amount of USDC"),
            SignInField("Allowance", "until Dec 3, 2026, 11:00 AM"),
            SignInField("Signature good until", "Nov 3, 2026, 11:30 AM"),
        ),
    ),
)

@Preview
@Composable
private fun DappRequestSwapOrderPreview() = DappRequestPreview(
    approval.copy(
        title = "Swap order to sign",
        meaning = "An order on CoW Protocol to sell 1 WETH for at least 2,500 USDC, until Nov 3, 2026, 11:00 AM. " +
            TypedDataText.ORDER_NOTE,
        meaningFields = listOf(
            SignInField("Sell", "1 WETH (WETH · 0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14)"),
            SignInField("Buy", "2500 USDC (USDC · $USDC)"),
            SignInField("Paid to", "This account"),
            SignInField("Valid until", "Nov 3, 2026, 11:00 AM"),
            SignInField("Partly filled", "No: all at once or not"),
        ),
    ),
)

@Preview
@Composable
private fun DappRequestNotDecodedPreview() = DappRequestPreview(notDecoded)

@Preview
@Composable
private fun DappRequestTypedDescribedPreview() = DappRequestPreview(typedDescribed)

@Preview
@Composable
private fun DappRequestTypedDescribedDarkPreview() = DappRequestPreview(typedDescribed, darkTheme = true)

@Preview
@Composable
private fun DappRequestTypedDescribingPreview() = DappRequestPreview(typedDescribing)

@Preview
@Composable
private fun DappRequestTypedContractChangedPreview() = DappRequestPreview(typedContractChanged)

@Preview
@Composable
private fun DappRequestOtherNetworkPreview() = DappRequestPreview(
    notDecoded.copy(
        domainFields = notDecoded.domainFields.map {
            if (it.label == "Network") it.copy(value = "Ethereum (1)") else it
        },
        refusal = DappRequestViewModel.refusalLine(DappSignRefusal.TYPED_OTHER_CHAIN),
    ),
)

// A sign-in on Ethereum, in a connection holding real funds.
private val signInOnEthereum = signIn.copy(
    network = DappRequestViewModel.onLine(eth.serotine.domain.model.Network.ETHEREUM),
    signIn = signIn.signIn.map { if (it.label == "Network") it.copy(value = "Ethereum (1)") else it },
)

@Preview
@Composable
private fun DappRequestRealFundsPreview() = DappRequestPreview(signInOnEthereum)

@Preview
@Composable
private fun DappRequestRealFundsDarkPreview() = DappRequestPreview(signInOnEthereum, darkTheme = true)

// Per-app addresses (issue #252): the address of example.org signing in, named by its site.
private val perAppAccount = signIn.copy(accountName = "example.org", perAppLine = "Per-app address for example.org")

@Preview
@Composable
private fun DappRequestPerAppPreview() = DappRequestPreview(perAppAccount)

// Signing in to another site with it: warned that the two sites are linked.
@Preview
@Composable
private fun DappRequestPerAppOtherSitePreview() = DappRequestPreview(
    perAppAccount.copy(
        signIn = perAppAccount.signIn.map { if (it.label == "Site") it.copy(value = "app.other.test") else it },
        // The domain is not the dApp's either: both warnings, as the screen stacks them.
        warnings = listOf(
            DappRequestViewModel.warningLine(DappSignWarning.DOMAIN_NOT_DAPP_URL, null),
            DappRequestViewModel.warningLine(
                DappSignWarning.PER_APP_OTHER_SITE,
                PerAppSigner(host("example.org"), host("other.test")),
            ),
        ),
    ),
)

// A dApp at a site the user linked to the address (example.org, linked to example.net's), signing in to its own
// domain: said, and not warned about.
@Preview
@Composable
private fun DappRequestPerAppLinkedPreview() = DappRequestPreview(
    signIn.copy(
        accountName = "example.net",
        perAppLine = DappRequestViewModel.perAppLine(
            PerAppSigner(host("example.net"), host("example.org"), linked = true),
        ),
    ),
)

// A Safe transaction Safe{Wallet} asks an owner to sign over WalletConnect: 1 COW to 0xbcb8…2d9c, at nonce 55.
private val previewSafe = Address.of("0x425e7c0c12e60bc7fe106ac3b44fa9d0602ca0ab")
private val previewCow = Token(Address.of("0xdef1ca1fb7fbcdc777520aa7f396b4e015f497ab"), "COW", "CoW Protocol Token", 18, "cow")
private val previewRecipient = Address.of("0xbcb8a02a8166ffb75924ecf89865539feac12d9c")
private val previewSafeState = SafeReading.Verified(
    SafeState("1.4.1", l2 = false, listOf(Address.of(ACCOUNT), previewRecipient), 2, 55, emptyList(), null),
    24_000_000,
    emptyList(),
)

private fun previewSafeTx(to: Address, data: ByteArray, operation: SafeOperation = SafeOperation.CALL) =
    SafeTx(to, UInt256.ZERO, data, operation, UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, Address.ZERO, Address.ZERO, 55)

private fun safeTxState(tx: SafeTx, refusal: String? = null, warnings: List<String> = emptyList()) = signIn.copy(
    title = "Safe transaction to sign",
    name = "Safe{Wallet}",
    url = "https://app.example.org",
    signIn = emptyList(),
    typed = true,
    network = "On Ethereum, real funds",
    meaning = "A transaction of the Safe 0x425E…A0AB, for its owners to sign. Once enough of them have, anyone " +
        "may execute it. What it does is decoded below.",
    safeTx = dappSafeTxSection(
        safe = previewSafe,
        action = SafeTxAction.of(previewSafe, tx),
        gasTerms = SafeTxGasTerms.of(tx),
        network = Network.ETHEREUM,
        tokens = listOf(previewCow),
        nonce = tx.nonce,
        hash = "0x" + tx.hash(Network.ETHEREUM.chainId, previewSafe).hex,
        reading = previewSafeState,
        signer = Address.of(ACCOUNT),
    ),
    domainFields = listOf(
        SignInField("Network", "Ethereum (1)"),
        SignInField("Contract", "0x425E7c0C12E60Bc7Fe106AC3b44FA9D0602CA0AB"),
    ),
    typedRows = listOf(
        TypedRow(0, "to", Eip55.format(tx.to)),
        TypedRow(0, "value", "0"),
        TypedRow(0, "data", "0x" + tx.data.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }),
        TypedRow(0, "operation", tx.operation.code.toString()),
        TypedRow(0, "nonce", "55"),
    ),
    refusal = refusal,
    warnings = warnings,
)

private val safeTransfer = safeTxState(
    previewSafeTx(previewCow.address, Erc20.transferData(previewRecipient, UInt256.of(1_000_000_000_000_000_000))),
)

private val safeBatch = safeTxState(
    previewSafeTx(
        Address.of("0x9641d764fc13c8b624c04430c7356c1c7c8102e2"),
        SafeMultiSend.data(
            listOf(
                BatchedCall(SafeOperation.CALL, previewCow.address, UInt256.ZERO, Erc20.approveData(previewRecipient, UInt256.of(5_000_000_000_000_000_000))),
                BatchedCall(SafeOperation.CALL, previewRecipient, UInt256.of(10_000_000_000_000_000), ByteArray(0)),
            ),
        ),
        SafeOperation.DELEGATECALL,
    ),
    refusal = "A delegate call: Punicus does not sign, approve or execute one yet. Batches through the pinned " +
        "MultiSendCallOnly come later.",
)

private val safeModule = safeTxState(
    previewSafeTx(previewSafe, "610b5925000000000000000000000000bcb8a02a8166ffb75924ecf89865539feac12d9c".chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
    refusal = "Enabling a module is refused: a module can move the Safe's funds without its owners. Which modules may " +
        "be enabled is still open (#54).",
)

private val safeMessage = signIn.copy(
    title = "Safe message to sign",
    name = "Safe{Wallet}",
    url = "https://app.example.org",
    signIn = emptyList(),
    typed = true,
    network = "On Ethereum, real funds",
    meaning = "A message signed for the Safe 0x425E…A0AB, as one of its owners: once enough of them have, contracts " +
        "take it as the Safe's own signature (EIP-1271). The request holds only a hash of the message, so Punicus " +
        "cannot show what the Safe signs.",
    meaningFields = listOf(
        SignInField("Safe", "0x425E7c0C12E60Bc7Fe106AC3b44FA9D0602CA0AB"),
        SignInField("Message (its hash)", "0x" + "ab".repeat(32)),
    ),
    domainFields = listOf(SignInField("Network", "Ethereum (1)"), SignInField("Contract", "0x425E7c0C12E60Bc7Fe106AC3b44FA9D0602CA0AB")),
    typedRows = listOf(TypedRow(0, "message", "0x" + "ab".repeat(32))),
    warnings = listOf(DappRequestViewModel.warningLine(DappSignWarning.SAFE_MESSAGE_HASH_ONLY, null)),
)

private val sessionGrant = signIn.copy(
    title = "Session key to grant",
    name = "ENS",
    url = "https://app.example.org",
    signIn = emptyList(),
    typed = true,
    network = "On Ethereum, real funds",
    meaning = "Grants a session key the power to act for 0x425E…A0AB on Ethereum (1): any call, to any contract, " +
        "with no limit, for ever. Whoever holds the key may then use it without asking you again (Smart Sessions).",
    meaningWarnings = listOf(
        TypedDataText.SESSION_ANY_CALL,
        TypedDataText.SESSION_SUDO,
        TypedDataText.SESSION_NEVER_EXPIRES,
        TypedDataText.SESSION_SIGNS_MESSAGES,
        TypedDataText.SESSION_GENERIC,
    ),
    meaningFields = listOf(
        SignInField("Account", "0x425E7c0C12E60Bc7Fe106AC3b44FA9D0602CA0AB"),
        SignInField("Network", "Ethereum (1)"),
        SignInField("Allowed calls", "Any contract, any function, under SudoPolicy (no limit)"),
        SignInField("Until", "Never: it does not expire"),
        SignInField("Signs messages for the account", "Yes, under SudoPolicy (no limit)"),
        SignInField(
            "Session key",
            "Checked by 0xBCb8A02a8166FFB75924ECf89865539fEAC12D9c, with 96 bytes of its own data: who holds the key " +
                "is not decoded here",
        ),
        SignInField("Checked by", "0xad568B3f825a8D5FfC06dd3253526b64D810aE89 (its SmartSessionEmissary)"),
    ),
    domainFields = listOf(SignInField("Name", "SmartSessionEmissary"), SignInField("Version", "1")),
    typedRows = listOf(
        TypedRow(0, "sessionsAndChainIds", "1 item"),
        TypedRow(1, "[0]", null),
        TypedRow(2, "chainId", "1"),
        TypedRow(2, "session", null),
        TypedRow(3, "account", "0x425E7c0C12E60Bc7Fe106AC3b44FA9D0602CA0AB"),
    ),
    warnings = listOf(
        DappRequestViewModel.warningLine(DappSignWarning.NO_CHAIN_ID, null),
        DappRequestViewModel.warningLine(DappSignWarning.SESSION_KEY_GRANT, null),
    ),
)

@Preview
@Composable
private fun DappRequestSafeTransferPreview() = DappRequestPreview(safeTransfer)

@Preview
@Composable
private fun DappRequestSafeTransferDarkPreview() = DappRequestPreview(safeTransfer, darkTheme = true)

@Preview
@Composable
private fun DappRequestSafeBatchPreview() = DappRequestPreview(safeBatch)

@Preview
@Composable
private fun DappRequestSafeModulePreview() = DappRequestPreview(safeModule)

@Preview
@Composable
private fun DappRequestSafeMessagePreview() = DappRequestPreview(safeMessage)

@Preview
@Composable
private fun DappRequestSessionGrantPreview() = DappRequestPreview(sessionGrant)

@Preview
@Composable
private fun DappRequestSessionGrantDarkPreview() = DappRequestPreview(sessionGrant, darkTheme = true)

private fun host(name: String) = (AppHost.parse(name) as AppHostResult.Host).host

/** Every preview above, which DappRequestScreenPreviewsTest renders: a new preview goes in here too. */
internal val dappRequestScreenPreviews: List<ScreenPreview> = listOf(
    ScreenPreview("sign-in") { DappRequestSignInPreview() },
    ScreenPreview("sign-in-dark") { DappRequestSignInDarkPreview() },
    ScreenPreview("biometrics") { DappRequestBiometricsPreview() },
    ScreenPreview("other-site") { DappRequestOtherSitePreview() },
    ScreenPreview("message") { DappRequestMessagePreview() },
    ScreenPreview("hex") { DappRequestHexPreview() },
    ScreenPreview("refused") { DappRequestRefusedPreview() },
    ScreenPreview("wrong-password") { DappRequestWrongPasswordPreview() },
    ScreenPreview("signing") { DappRequestSigningPreview() },
    ScreenPreview("explaining") { DappRequestExplainingPreview() },
    ScreenPreview("ended") { DappRequestEndedPreview() },
    ScreenPreview("loading") { DappRequestLoadingPreview() },
    ScreenPreview("approval") { DappRequestApprovalPreview() },
    ScreenPreview("approval-dark") { DappRequestApprovalDarkPreview() },
    ScreenPreview("unlimited") { DappRequestUnlimitedPreview() },
    ScreenPreview("swap-order") { DappRequestSwapOrderPreview() },
    ScreenPreview("not-decoded") { DappRequestNotDecodedPreview() },
    ScreenPreview("typed-described") { DappRequestTypedDescribedPreview() },
    ScreenPreview("typed-described-dark") { DappRequestTypedDescribedDarkPreview() },
    ScreenPreview("typed-describing") { DappRequestTypedDescribingPreview() },
    ScreenPreview("typed-contract-changed") { DappRequestTypedContractChangedPreview() },
    ScreenPreview("other-network") { DappRequestOtherNetworkPreview() },
    ScreenPreview("real-funds") { DappRequestRealFundsPreview() },
    ScreenPreview("real-funds-dark") { DappRequestRealFundsDarkPreview() },
    ScreenPreview("per-app") { DappRequestPerAppPreview() },
    ScreenPreview("per-app-other-site") { DappRequestPerAppOtherSitePreview() },
    ScreenPreview("per-app-linked") { DappRequestPerAppLinkedPreview() },
    ScreenPreview("safe-transfer") { DappRequestSafeTransferPreview() },
    ScreenPreview("safe-transfer-dark") { DappRequestSafeTransferDarkPreview() },
    ScreenPreview("safe-batch") { DappRequestSafeBatchPreview() },
    ScreenPreview("safe-module") { DappRequestSafeModulePreview() },
    ScreenPreview("safe-message") { DappRequestSafeMessagePreview() },
    ScreenPreview("session-grant") { DappRequestSessionGrantPreview() },
    ScreenPreview("session-grant-dark") { DappRequestSessionGrantDarkPreview() },
)
