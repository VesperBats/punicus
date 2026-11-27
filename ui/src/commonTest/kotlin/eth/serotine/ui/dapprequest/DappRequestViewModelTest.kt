package eth.serotine.ui.dapprequest

import eth.serotine.ui.components.ClearSignedText
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.ClearSigningDescriptor
import eth.serotine.domain.model.ClearSigningField
import eth.serotine.domain.model.ClearSigningFormat
import eth.serotine.domain.model.ClearSigningPath
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.FieldFormat
import eth.serotine.domain.model.Visibility
import kotlin.test.assertNotNull
import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppHostResult
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.PerAppAccount
import eth.serotine.domain.usecase.DappSignWarning
import eth.serotine.domain.usecase.PerAppSigner
import eth.serotine.ui.MemoryAliases
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.TypedField
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PersonalMessage
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.BiometricUnlock
import eth.serotine.domain.repository.DappAnswer
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestError
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import eth.serotine.domain.repository.KeyDeletion
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStore
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.NewMnemonic
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import eth.serotine.domain.usecase.DescribeKeyStore
import eth.serotine.domain.usecase.ObserveDappMessages
import eth.serotine.domain.usecase.RefuseDappRequest
import eth.serotine.domain.usecase.SignDappMessage
import eth.serotine.ui.FakeAccounts
import eth.serotine.ui.FakeDappRequests
import eth.serotine.ui.FakeSigner
import eth.serotine.ui.MemoryNetworkSettings
import eth.serotine.ui.components.Eip55
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ViewModel of a dApp's request to sign a message (screen D4). */
@OptIn(ExperimentalCoroutinesApi::class)
class DappRequestViewModelTest {

    private val aliases = MemoryAliases()

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private val now = 1_793_700_000L
    private var clock = now
    private val ticks = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val held = Account(
        Network.SEPOLIA,
        Address.of("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        AccountKind.EOA,
        "Main",
        AccountCustody.InKeyStore,
    )
    private val accounts = FakeAccounts(held)
    private val networks = MemoryNetworkSettings(setOf(Network.SEPOLIA))
    private val requests = FakeDappRequests()
    private val signer = FakeSigner()
    private var biometrics: BiometricUnlock = BiometricUnlock.NotOnThisDevice
    private val keys = object : KeyStore {
        override suspend fun describe() = KeyStoreDescription.Ready(true, KeyProtection.HARDWARE, biometrics)
        override suspend fun newMnemonic(): NewMnemonic = error("not used by these tests")
        override suspend fun importMnemonic(mnemonic: Mnemonic, password: WalletPassword): KeyImport = error("not used")
        override suspend fun enableBiometrics(password: WalletPassword, confirmation: String): BiometricChange =
            error("not used by these tests")
        override suspend fun deriveAccount(from: Address, password: WalletPassword, held: Set<Address>) =
            KeyDerivation.Unavailable
        override suspend fun disableBiometrics(): BiometricChange = error("not used by these tests")
        override suspend fun delete(address: Address): KeyDeletion = error("not used by these tests")
    }

    private val signIn = """
        app.example.org wants you to sign in with your Ethereum account:
        ${Eip55.format(held.address)}

        Sign in to Example dApp.

        URI: https://app.example.org/login
        Version: 1
        Chain ID: 11155111
        Nonce: abcdefgh12
        Issued At: 2026-11-03T10:00:00Z
    """.trimIndent()

    private fun request(id: Long, text: String = signIn, url: String = "https://app.example.org") = DappRequest(
        DappRequestId(DappSessionId("t"), id),
        DappMetadata("Example dApp", url, ""),
        listOf(held.id),
        "eip155:11155111",
        DappAsk.PersonalSign(held.address, PersonalMessage.of(text.encodeToByteArray())!!),
        now + 300,
    )

    private val usdc = Token(Address.of("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"), "USDC", "USD Coin", 6, null)
    private val tokens = object : TokenCatalog {
        override fun observeTokens(network: Network): Flow<List<Token>> = flowOf(listOf(usdc))
    }

    private val verifier = Address.of("0x" + "99".repeat(20))
    private var deployments: Array<ClearSigningDeployment> = emptyArray()
    private var unproven = false
    private var throwing = false
    private var onRead: () -> Unit = {}
    private val mailing = ClearSigningDeployment(
        Network.SEPOLIA, "Mailer", verifier, "0x" + "11".repeat(32), CodePin.Fixed,
        listOf(
            ClearSigningDescriptor(
                source = "test", sha256 = "00", owner = "Mailer", kind = ClearSigningDescriptor.Kind.EIP712,
                domainName = "Mailer", domainVersion = null, enums = emptyMap(),
                formats = listOf(
                    ClearSigningFormat(
                        key = "Mail(address to)", function = null, selector = null, encodeType = "Mail(address to)",
                        typeHash = "0xabc0d49233c79caa7e457f29c2a529935c2d94a8f1e51c8feeb1fa60f08ba769", primaryType = "Mail",
                        intent = "Send mail", interpolatedIntent = null,
                        fields = listOf(ClearSigningField(ClearSigningPath.parse("to"), "To", FieldFormat.Raw, Visibility.Shown)),
                    ),
                ),
            ),
        ),
    )

    private fun mail(id: Long): DappRequest {
        val data = TypedData(
            types = mapOf(
                "EIP712Domain" to listOf(
                    TypedField("name", "string"),
                    TypedField("chainId", "uint256"),
                    TypedField("verifyingContract", "address"),
                ),
                "Mail" to listOf(TypedField("to", "address")),
            ),
            primaryType = "Mail",
            domain = mapOf(
                "name" to TypedValue.Text("Mailer"),
                "chainId" to TypedValue.Number("11155111"),
                "verifyingContract" to TypedValue.Text(verifier.hex),
            ),
            message = mapOf("to" to TypedValue.Text("0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad")),
        )
        return request(id).copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(data)!!))
    }

    @Test
    fun typedDataNoDecoderKnowsIsDescribedByItsBuiltInDescriptor() = runTest {
        deployments = arrayOf(mailing)
        requests.waiting.value = listOf(mail(1))
        val state = viewModel().uiState.value
        val described = assertNotNull(state.described)
        assertEquals("Send mail", described.intent)
        assertEquals(listOf("To"), described.rows.map { it.label })
        assertFalse(DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED, null) in state.warnings)
    }

    @Test
    fun typedDataWithoutAVerifyingContractClaimsNoReason() {
        assertNull(ClearSignedText.notDescribedLine(eth.serotine.domain.model.NotCoveredReason.DomainWithoutVerifyingContract))
    }

    @Test
    fun typedDataNoDescriptorCoversStaysNotDecoded() = runTest {
        requests.waiting.value = listOf(mail(1))
        val state = viewModel().uiState.value
        assertNull(state.described)
        assertTrue(DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED, null) in state.warnings)
    }

    @Test
    fun aDecodedPermitKeepsItsHandWrittenMeaningEvenWhereADescriptorCoversIt() = runTest {
        val permitFormat = ClearSigningFormat(
            key = PERMIT, function = null, selector = null, encodeType = PERMIT,
            typeHash = "0x6e71edae12b1b97f4d1f60370fef10105fa2faae0126114a169c64845d6126c9", primaryType = "Permit",
            intent = "Something else", interpolatedIntent = null,
            fields = listOf(ClearSigningField(ClearSigningPath.parse("spender"), "Spender", FieldFormat.Raw, Visibility.Shown)),
        )
        deployments = arrayOf(
            mailing.copy(
                address = usdc.address,
                descriptors = listOf(mailing.descriptors.single().copy(domainName = null, formats = listOf(permitFormat))),
            ),
        )
        requests.waiting.value = listOf(permit(1))
        val state = viewModel().uiState.value
        assertNotNull(state.meaning, "the hand-written meaning stands")
        assertNull(state.described)
        assertNull(state.notDescribed)
    }

    @Test
    fun anUndescribedBatchPermitIsFlaggedAsAnApproval() = runTest {
        requests.waiting.value = listOf(batch(1))
        val state = viewModel().uiState.value
        assertNull(state.described)
        assertTrue(DappRequestViewModel.TYPED_APPROVAL in state.warnings)
        assertTrue(DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED, null) in state.warnings)
    }

    @Test
    fun aCheckThatCouldNotBeMadeIsMadeAgainOnTheNextTick() = runTest {
        deployments = arrayOf(mailing)
        unproven = true
        requests.waiting.value = listOf(mail(1))
        val vm = viewModel()
        assertEquals(ClearSignedText.CODE_NOT_CHECKED, vm.uiState.value.notDescribed)
        unproven = false
        ticks.value += 1
        runCurrent()
        assertEquals("Send mail", vm.uiState.value.described?.intent)
    }

    @Test
    fun beingShownAgainAsksAgain() = runTest {
        var asked = 0
        deployments = arrayOf(mailing)
        onRead = { asked++ }
        requests.waiting.value = listOf(mail(1))
        val vm = viewModel()
        assertEquals(1, asked)
        vm.onShown()
        runCurrent()
        assertEquals(2, asked)
    }

    @Test
    fun aUseCaseThatThrowsLeavesTheDataNotDecoded() = runTest {
        throwing = true
        requests.waiting.value = listOf(mail(1))
        val state = viewModel().uiState.value
        assertNull(state.described)
        assertNull(state.notDescribed)
        assertTrue(DappRequestViewModel.warningLine(DappSignWarning.NOT_DECODED, null) in state.warnings)
    }

    private fun batch(id: Long): DappRequest {
        val data = TypedData(
            types = mapOf(
                "EIP712Domain" to listOf(TypedField("name", "string"), TypedField("chainId", "uint256"), TypedField("verifyingContract", "address")),
                "PermitDetails" to listOf(
                    TypedField("token", "address"), TypedField("amount", "uint160"),
                    TypedField("expiration", "uint48"), TypedField("nonce", "uint48"),
                ),
                "PermitBatch" to listOf(TypedField("details", "PermitDetails[]"), TypedField("spender", "address"), TypedField("sigDeadline", "uint256")),
            ),
            primaryType = "PermitBatch",
            domain = mapOf(
                "name" to TypedValue.Text("Permit2"),
                "chainId" to TypedValue.Number("11155111"),
                "verifyingContract" to TypedValue.Text("0x000000000022d473030f116ddee9f6b43ac78ba3"),
            ),
            message = mapOf(
                "details" to TypedValue.Items(
                    listOf(
                        TypedValue.Struct(
                            mapOf(
                                "token" to TypedValue.Text(usdc.address.hex), "amount" to TypedValue.Number("1"),
                                "expiration" to TypedValue.Number("1"), "nonce" to TypedValue.Number("0"),
                            ),
                        ),
                    ),
                ),
                "spender" to TypedValue.Text("0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad"),
                "sigDeadline" to TypedValue.Number("1"),
            ),
        )
        return request(id).copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(data)!!))
    }

    private fun permit(id: Long): DappRequest {
        val data = TypedData(
            types = mapOf(
                "EIP712Domain" to listOf(
                    TypedField("name", "string"),
                    TypedField("chainId", "uint256"),
                    TypedField("verifyingContract", "address"),
                ),
                "Permit" to listOf(
                    TypedField("owner", "address"),
                    TypedField("spender", "address"),
                    TypedField("value", "uint256"),
                    TypedField("nonce", "uint256"),
                    TypedField("deadline", "uint256"),
                ),
            ),
            primaryType = "Permit",
            domain = mapOf(
                "name" to TypedValue.Text("USD Coin"),
                "chainId" to TypedValue.Number("11155111"),
                "verifyingContract" to TypedValue.Text(usdc.address.hex),
            ),
            message = mapOf(
                "owner" to TypedValue.Text(held.address.hex),
                "spender" to TypedValue.Text("0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad"),
                "value" to TypedValue.Text("250000000"),
                "nonce" to TypedValue.Number("0"),
                "deadline" to TypedValue.Number("1793703600"),
            ),
        )
        return request(id).copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(data)!!))
    }

    @Test
    fun aRequestOnEthereumNamesItsNetworkAndIsSignedForItsAccountThere() = runTest {
        val onEthereum = Account(Network.ETHEREUM, held.address, AccountKind.EOA, "Savings", AccountCustody.InKeyStore)
        accounts.accounts.value = listOf(held, onEthereum)
        networks.setEnabled(Network.ETHEREUM, true)
        val text = signIn.replace("Chain ID: 11155111", "Chain ID: 1")
        requests.waiting.value = listOf(
            request(1, text).copy(
                chain = "eip155:1",
                sessionAccounts = listOf(onEthereum.id),
                sessionNetworks = setOf(Network.ETHEREUM, Network.GNOSIS),
            ),
        )
        val vm = viewModel()
        val state = vm.uiState.value
        assertEquals("On Ethereum, real funds", state.network)
        assertEquals("Savings", state.accountName, "the account on the request's network, not its Sepolia twin")
        assertNull(state.refusal)
    }

    /** The Safe a request's typed data is for, as a test sets it: not available until then. */
    private val safeKind = kotlinx.coroutines.flow.MutableStateFlow<eth.serotine.domain.repository.KindReading>(
        eth.serotine.domain.repository.KindReading.Unavailable(eth.serotine.domain.repository.ReadUnavailable.NO_ENGINE),
    )
    private val observeSafe by lazy {
        eth.serotine.domain.usecase.ObserveSafe(
            object : eth.serotine.domain.repository.AddressKinds {
                override fun observeKind(network: Network, address: eth.serotine.domain.model.Address) = safeKind
            },
            accounts,
        )
    }

    private val traced = mutableListOf<Triple<Address, Address, eth.serotine.domain.model.UInt256>>()

    private fun TestScope.viewModel(id: Long = 1): DappRequestViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val observe = ObserveDappMessages(requests, accounts, networks, tokens, aliases, clock = { now })
        val vm = DappRequestViewModel(
            "t/$id",
            observe,
            when {
                throwing -> eth.serotine.ui.dapptransaction.describingThat(IllegalStateException("engine"))
                else -> eth.serotine.ui.dapptransaction.describing(tokens, *deployments, onRead = onRead, unproven = { unproven })
            },
            SignDappMessage(requests, accounts, networks, signer, observeSafe, clock = { now }),
            RefuseDappRequest(requests, accounts, networks, clock = { now }),
            DescribeKeyStore(keys),
            observeSafe,
            eth.serotine.ui.dapptransaction.tracing(tokens, backgroundScope, traced),
            clock = { clock },
            formatTime = { "at $it" },
            ticks = ticks.map { },
            formatOn = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun aSignInIsShownFieldByFieldAndSignedWithThePassword() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        val state = vm.uiState.value
        assertEquals("Sign-in request", state.title)
        assertEquals("Sign in", state.signLabel)
        assertEquals("Main", state.accountName)
        assertEquals(Eip55.format(held.address), state.accountAddress)
        assertEquals(
            listOf("Site", "Account", "Statement", "URI", "Network", "Nonce", "Issued"),
            state.signIn.map { it.label },
        )
        assertEquals("Sepolia (11155111)", state.signIn.first { it.label == "Network" }.value)
        assertEquals("at 1793700000", state.signIn.first { it.label == "Issued" }.value)
        assertTrue(state.warnings.isEmpty())
        assertNull(state.refusal)
        assertEquals("The dApp waits for an answer for 5 more minutes.", state.waits)

        // Nothing is signed without the password.
        vm.sign()
        assertTrue(signer.asked.isEmpty())
        vm.onPasswordChange("correct horse")
        vm.sign()
        runCurrent()
        assertEquals(listOf("correct horse"), signer.passwords)
        assertEquals(request(1).id, requests.signatures.single().first)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun howLongTheDappWaitsIsSaidAgainAsTimePasses() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        assertEquals("The dApp waits for an answer for 5 more minutes.", vm.uiState.value.waits)
        clock = now + 250
        ticks.value = 1
        runCurrent()
        assertEquals("The dApp waits for an answer for about a minute more.", vm.uiState.value.waits)
    }

    private val safe = Address.of("0x425e7c0c12e60bc7fe106ac3b44fa9d0602ca0ab")
    private val recipient = Address.of("0xbcb8a02a8166ffb75924ecf89865539feac12d9c")

    private fun safeTx(id: Long, tx: eth.serotine.domain.model.SafeTx) =
        request(id).copy(ask = DappAsk.SignTypedData(held.address, Eip712.encode(tx.typedData(Network.SEPOLIA.chainId, safe))!!))

    private fun plain(to: Address, data: ByteArray, operation: eth.serotine.domain.model.SafeOperation = eth.serotine.domain.model.SafeOperation.CALL) =
        eth.serotine.domain.model.SafeTx(
            to, eth.serotine.domain.model.UInt256.ZERO, data, operation, eth.serotine.domain.model.UInt256.ZERO,
            eth.serotine.domain.model.UInt256.ZERO, eth.serotine.domain.model.UInt256.ZERO, Address.ZERO, Address.ZERO, 55,
        )

    private fun safeState(owners: List<Address>) = eth.serotine.domain.repository.KindReading.Verified(
        eth.serotine.domain.repository.AddressKind.Safe(
            eth.serotine.domain.repository.SafeState("1.4.1", l2 = false, owners, 2, 55, emptyList(), null),
        ),
        100,
    )

    @Test
    fun safeWalletsTokenTransferIsDecodedOnItsOwnCardWithTheSafeAndItsHash() = runTest {
        val tx = plain(usdc.address, eth.serotine.domain.model.Erc20.transferData(recipient, eth.serotine.domain.model.UInt256.of(40_000_000)))
        requests.waiting.value = listOf(safeTx(4, tx))
        val vm = viewModel(4)
        safeKind.value = safeState(listOf(held.address, recipient))
        runCurrent()
        val state = vm.uiState.value
        assertEquals("Safe transaction to sign", state.title)
        val section = assertNotNull(state.safeTx)
        assertEquals("Send 40 USDC", section.action.title)
        assertEquals(Eip55.format(safe), section.safeAddress)
        assertEquals("Nonce 55", section.nonce)
        assertEquals("0x" + tx.hash(Network.SEPOLIA.chainId, safe).hex, section.hash)
        assertTrue(section.safeLine.contains("2 of 2 owners, at nonce 55: this is its next transaction"), section.safeLine)
        assertNull(section.safeWarning)
        assertNull(state.refusal)
        assertTrue(state.warnings.isEmpty(), "${state.warnings}")
        // Traced as the Safe's own call, as screen 17 traces one.
        assertEquals(Triple(safe, usdc.address, eth.serotine.domain.model.UInt256.ZERO), traced.last())
        assertEquals(eth.serotine.ui.components.TraceText.SAFE_CALL_NOTE, section.action.trace?.note)
        vm.onPasswordChange("correct horse")
        vm.sign()
        runCurrent()
        assertEquals(safeTx(4, tx).id, requests.signatures.single().first)
    }

    @Test
    fun aSafeTxEnablingAModuleIsRefusedWithScreen17sReason() = runTest {
        val enable = plain(safe, "610b5925".let { sel -> (sel + "0".repeat(24) + recipient.hex.removePrefix("0x")) }.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        requests.waiting.value = listOf(safeTx(5, enable))
        val state = viewModel(5).uiState.value
        assertEquals("Enable a module", state.safeTx?.action?.title)
        assertEquals(eth.serotine.ui.components.MODULE_ENABLED_REFUSAL, state.refusal)
        assertTrue(state.signUnavailable)
    }

    @Test
    fun anOwnerThatIsNotOneIsWarned() = runTest {
        val tx = plain(usdc.address, eth.serotine.domain.model.Erc20.transferData(recipient, eth.serotine.domain.model.UInt256.of(1)))
        requests.waiting.value = listOf(safeTx(6, tx))
        safeKind.value = safeState(listOf(recipient))
        val section = assertNotNull(viewModel(6).uiState.value.safeTx)
        assertEquals("The account asked is not one of this Safe's owners.", section.safeWarning)
    }

    @Test
    fun aKnownTestSignatureIsNamedAsOne() = runTest {
        val walletbeat = "This is a safe test message for educational purposes only. It does not authorize any " +
            "transactions or actions."
        requests.waiting.value = listOf(request(12, walletbeat))
        val state = viewModel(12).uiState.value
        assertEquals(
            "A known test request: Walletbeat's test playground's \"Simple Test Message\", byte for byte. Any site " +
                "can send the same, so this says what it is, not who is asking.",
            state.knownTest,
        )
    }

    @Test
    fun aPermitIsShownAsTheApprovalItIsWithItsDomainAndEveryField() = runTest {
        requests.waiting.value = listOf(permit(3))
        val vm = viewModel(3)
        val state = vm.uiState.value
        assertTrue(state.typed)
        assertEquals("Approval request", state.title)
        assertEquals("Sign", state.signLabel)
        assertTrue(state.meaning!!.startsWith("Lets 0x3fC9…7FAD take up to 250 USDC from this account, until at 1793703600."))
        assertEquals(
            listOf(
                SignInField("Token", "USDC · ${Eip55.format(usdc.address)}"),
                SignInField("Spender", "0x3fC91A3afd70395Cd496C647d5a6CC9D4B2b7FAD"),
                SignInField("Amount", "250 USDC"),
                SignInField("Until", "at 1793703600"),
            ),
            state.meaningFields,
        )
        assertEquals(listOf("Name", "Network", "Contract"), state.domainFields.map { it.label })
        assertEquals("Sepolia (11155111)", state.domainFields[1].value)
        assertEquals(listOf("owner", "spender", "value", "nonce", "deadline"), state.typedRows.map { it.label })
        assertEquals("250000000", state.typedRows[2].value)
        assertTrue(state.warnings.isEmpty())
        vm.onPasswordChange("correct horse")
        vm.sign()
        runCurrent()
        assertEquals(permit(3).id, requests.signatures.single().first)
    }

    @Test
    fun aWrongPasswordSaysSoAndTheFieldIsEmptied() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        signer.answer = Signing.WrongPassword
        vm.onPasswordChange("wrong")
        vm.sign()
        runCurrent()
        val state = vm.uiState.value
        assertEquals("That is not the wallet password. Nothing was signed.", state.unlock.error)
        assertEquals("", state.unlock.password.text)
        assertFalse(state.done)
        assertTrue(requests.signatures.isEmpty())
    }

    @Test
    fun aMessageThatIsNoSignInIsWarnedAboutAndBytesThatAreNoTextAreShownAsHex() = runTest {
        requests.waiting.value = listOf(
            request(1, "Hello, dApp"),
            request(2).copy(ask = DappAsk.PersonalSign(held.address, PersonalMessage.ofHex("deadbeef")!!)),
        )
        val text = viewModel(1).uiState.value
        assertEquals("Message to sign", text.title)
        assertEquals("Sign", text.signLabel)
        assertEquals("Hello, dApp", text.messageText)
        assertNull(text.messageHex)
        assertEquals("11 bytes", text.messageSize)
        assertEquals(1, text.warnings.size)
        val hex = viewModel(2).uiState.value
        assertNull(hex.messageText)
        assertEquals("deadbeef", hex.messageHex)
    }

    @Test
    fun aSignInForAnotherSiteIsWarnedAbout() = runTest {
        requests.waiting.value = listOf(request(1, url = "https://other.example.net"))
        assertEquals(
            listOf(DappRequestViewModel.warningLine(DappSignWarning.DOMAIN_NOT_DAPP_URL, null)),
            viewModel().uiState.value.warnings,
        )
    }

    @Test
    fun oneThatCannotBeSignedSaysWhyOnSignAndIsRefusedWithItsError() = runTest {
        requests.waiting.value = listOf(request(1, signIn.replace("Chain ID: 11155111", "Chain ID: 1")))
        val vm = viewModel()
        assertTrue(vm.uiState.value.signUnavailable)
        vm.onPasswordChange("correct horse")
        vm.sign()
        assertEquals("Cannot be signed", vm.uiState.value.explaining?.title)
        assertTrue(signer.asked.isEmpty())
        vm.dismissExplanation()
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1).id to DappRequestError.INVALID_PARAMS), requests.refusals)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aDeclinedRequestIsAnsweredAsRejected() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        vm.refuse()
        runCurrent()
        assertEquals(listOf(request(1).id to DappRequestError.USER_REJECTED), requests.refusals)
    }

    @Test
    fun anAnswerThatDidNotTakeSaysWhyAndARequestNoLongerWaitingEndsTheScreen() = runTest {
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        requests.answer = DappAnswer.NOT_SAVED
        vm.refuse()
        runCurrent()
        assertEquals(DappRequestViewModel.NOT_SAVED, vm.uiState.value.notice)
        requests.answer = DappAnswer.EXPIRED
        vm.onPasswordChange("correct horse")
        vm.sign()
        runCurrent()
        assertEquals(DappRequestViewModel.EXPIRED, vm.uiState.value.notice)
        requests.waiting.value = emptyList()
        runCurrent()
        assertTrue(vm.uiState.value.ended)
        assertFalse(vm.uiState.value.gone)
        assertTrue(viewModel(9).uiState.value.gone)
    }

    @Test
    fun withBiometricsOnAFingerprintSignsAndTheHiddenScreenDropsThePassword() = runTest {
        biometrics = BiometricUnlock.On
        requests.waiting.value = listOf(request(1))
        val vm = viewModel()
        assertTrue(vm.uiState.value.unlock.biometrics)
        vm.onPasswordChange("typed")
        vm.onHidden()
        assertEquals("", vm.uiState.value.unlock.password.text)
        vm.signWithBiometrics()
        runCurrent()
        val unlock = signer.asked.single().second as Unlock.WithBiometrics
        assertEquals("Sign in to a dApp with ${Eip55.format(held.address)}", unlock.reason)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aPerAppAddressIsNamedAndHeldAgainstItsSiteAndItsAliasesThroughTheViewModel() = runTest {
        val site = (AppHost.parse("example.org") as AppHostResult.Host).host
        val other = (AppHost.parse("other.test") as AppHostResult.Host).host
        val mark = PerAppAccount(site, AppIsolation.pathOf(site), held.address)
        accounts.accounts.value = listOf(held.copy(perApp = mark))

        // Signing in to its own site: named, no warning.
        requests.waiting.value = listOf(request(1))
        val own = viewModel(1).uiState.value
        assertEquals("Per-app address for example.org", own.perAppLine)
        assertTrue(own.warnings.isEmpty())

        // Signing in to other.test, from a dApp at other.test: warned that it links the two, both sites named.
        val elsewhere = signIn.replace("app.example.org", "app.other.test")
        requests.waiting.value = listOf(request(2, elsewhere, url = "https://app.other.test"))
        val vm = viewModel(2)
        assertEquals(
            listOf(DappRequestViewModel.warningLine(DappSignWarning.PER_APP_OTHER_SITE, PerAppSigner(site, other))),
            vm.uiState.value.warnings,
        )

        // Linked by the user meanwhile: the same screen says so, and the warning goes.
        aliases.linked.value = mapOf(other to held.address)
        runCurrent()
        assertEquals(
            "Per-app address for example.org. Signing in to other.test, linked by you to example.org.",
            vm.uiState.value.perAppLine,
        )
        assertTrue(vm.uiState.value.warnings.isEmpty())
    }

    @Test
    fun aPerAppAddressIsNamedAndASignInToAnotherSiteSaysItLinksThem() {
        val site = (AppHost.parse("example.org") as AppHostResult.Host).host
        val other = (AppHost.parse("other.test") as AppHostResult.Host).host
        assertEquals("Per-app address for example.org", DappRequestViewModel.perAppLine(PerAppSigner(site, site)))
        assertEquals(
            "Per-app address for example.org. Signing in to other.test, linked by you to example.org.",
            DappRequestViewModel.perAppLine(PerAppSigner(site, other, linked = true)),
        )
        assertEquals(
            "This address was made for example.org. Signing in to other.test with it links the two sites: each " +
                "can tell your activity on the other is yours.",
            DappRequestViewModel.warningLine(DappSignWarning.PER_APP_OTHER_SITE, PerAppSigner(site, other)),
        )
    }
}

private const val PERMIT = "Permit(address owner,address spender,uint256 value,uint256 nonce,uint256 deadline)"
