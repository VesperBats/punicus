package eth.serotine.domain.usecase

import eth.serotine.domain.model.Account
import eth.serotine.domain.model.AccountCustody
import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.BatchedCall
import eth.serotine.domain.model.DappCallMeaning
import eth.serotine.domain.model.DappTransactionRequest
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.Erc20
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeExecCall
import eth.serotine.domain.model.SafeMultiSend
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.model.SafeSettingsChange
import eth.serotine.domain.model.SafeTx
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SmartSessionGrants
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.TypedField
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.hexToBytes
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.DappAsk
import eth.serotine.domain.repository.DappMetadata
import eth.serotine.domain.repository.DappRequest
import eth.serotine.domain.repository.DappRequestId
import eth.serotine.domain.repository.DappSessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Safe{Wallet} asks an owner connected over WalletConnect, decoded (docs/walletconnect.md, *A Safe transaction
 * to sign*): its `SafeTx` typed data, its `SafeMessage`, its `execTransaction` and `approveHash`; and a Smart Sessions
 * grant (*Session keys*).
 */
class DappSafeDecodingTest {

    private val owner = Account(Network.ETHEREUM, Address.of("0x" + "0".repeat(39) + "1"), AccountKind.EOA, "Owner", AccountCustody.InKeyStore)
    private val safe = Address.of("0x425e7c0c12e60bc7fe106ac3b44fa9d0602ca0ab")
    private val cow = Address.of("0xdef1ca1fb7fbcdc777520aa7f396b4e015f497ab")
    private val recipient = Address.of("0xbcb8a02a8166ffb75924ecf89865539feac12d9c")
    private val now = 1_793_700_000L

    private fun request(ask: DappAsk) = DappRequest(
        DappRequestId(DappSessionId("t"), 1),
        DappMetadata("Safe{Wallet}", "https://app.safe.global", ""),
        listOf(AccountId(Network.ETHEREUM, owner.address)),
        "eip155:1",
        ask,
        now + 300,
    )

    private fun typed(data: TypedData) = request(DappAsk.SignTypedData(owner.address, Eip712.encode(data)!!))

    private fun review(request: DappRequest) =
        reviewDappMessage(request, listOf(owner), setOf(Network.ETHEREUM), now)!!

    private fun safeTx(to: Address, data: ByteArray, operation: SafeOperation = SafeOperation.CALL, value: UInt256 = UInt256.ZERO) =
        SafeTx(to, value, data, operation, UInt256.ZERO, UInt256.ZERO, UInt256.ZERO, Address.ZERO, Address.ZERO, 55)

    @Test
    fun safeWalletsErc20TransferIsDecodedAndSignedOverTheSafeTxHash() {
        // The transaction of the issue's screenshot: 1 COW to 0xbcb8…2d9c, at nonce 55.
        val tx = safeTx(cow, Erc20.transferData(recipient, UInt256.of(1_000_000_000_000_000_000)))
        val review = review(typed(tx.typedData(Network.ETHEREUM.chainId, safe)))
        val meaning = assertIs<TypedDataMeaning.SafeTransaction>((review.content as DappSignContent.Typed).meaning)
        assertEquals(safe, meaning.safe)
        assertEquals(tx, meaning.tx)
        assertEquals(SafeTxAction.TokenTransfer(cow, recipient, UInt256.of(1_000_000_000_000_000_000)), SafeTxAction.of(safe, tx))
        assertNull(review.refusal)
        assertTrue(review.warnings.isEmpty(), "${review.warnings}")
        assertEquals(tx.hash(Network.ETHEREUM.chainId, safe), review.digest)
    }

    @Test
    fun aSafeTxEnablingAModuleOrDelegateCallingIsRefusedWhoeverProposesIt() {
        val enable = safeTx(safe, "610b5925".hexToBytes() + ByteArray(12) + recipient.hex.removePrefix("0x").hexToBytes())
        val refused = review(typed(enable.typedData(Network.ETHEREUM.chainId, safe)))
        assertEquals(DappSignRefusal.SAFE_TX_REFUSED, refused.refusal)
        assertEquals(SafeTxRefusal.ModuleEnabled(recipient), refused.safeTxRefusal)

        // A Safe{Wallet} batch: decoded call by call, and still a delegate call (#57).
        val batch = SafeMultiSend.data(
            listOf(
                BatchedCall(SafeOperation.CALL, cow, UInt256.ZERO, Erc20.approveData(recipient, UInt256.of(5))),
                BatchedCall(SafeOperation.CALL, recipient, UInt256.of(7), ByteArray(0)),
            ),
        )
        val callOnly = Address.of("0x9641d764fc13c8b624c04430c7356c1c7c8102e2")
        val tx = safeTx(callOnly, batch, SafeOperation.DELEGATECALL)
        assertEquals(
            SafeTxAction.Batch(
                callOnly,
                callOnly = true,
                calls = listOf(SafeTxAction.TokenApproval(cow, recipient, UInt256.of(5)), SafeTxAction.NativeTransfer(recipient, UInt256.of(7))),
            ),
            SafeTxAction.of(safe, tx),
        )
        val review = review(typed(tx.typedData(Network.ETHEREUM.chainId, safe)))
        assertEquals(SafeTxRefusal.DelegateCall, review.safeTxRefusal)
        assertTrue(DappSignWarning.SAFE_CALL_NOT_DECODED !in review.warnings)
        // A delegate call to anything but a MultiSend is not decoded, and said to be unusual.
        val other = safeTx(recipient, batch, SafeOperation.DELEGATECALL)
        assertTrue(DappSignWarning.SAFE_CALL_NOT_DECODED in review(typed(other.typedData(1, safe))).warnings)
        // A batch with a byte too many is no batch.
        assertIs<SafeTxAction.DelegateCall>(SafeTxAction.of(safe, safeTx(callOnly, batch + byteArrayOf(0), SafeOperation.DELEGATECALL)))
    }

    @Test
    fun anNftSentAndACowOrderPreSignedAreDecoded() {
        val nft = Address.of("0x57f1887a8bf19b14fc0df6fd9b2acc9af147ea85")
        val data = "42842e0e".hexToBytes() + word(safe) + word(recipient) + UInt256.of(42).toBytes32()
        assertEquals(SafeTxAction.NftTransfer(nft, safe, recipient, UInt256.of(42)), SafeTxAction.of(safe, safeTx(nft, data)))

        val uid = ByteArray(32) { 7 } + safe.hex.removePrefix("0x").hexToBytes() + byteArrayOf(0x6a, 0x00, 0x00, 0x00)
        val presign = "ec6cb13f".hexToBytes() + UInt256.of(64).toBytes32() + UInt256.of(1).toBytes32() +
            UInt256.of(56).toBytes32() + uid + ByteArray(8)
        val settlement = TypedDataMeaning.GPV2_SETTLEMENT
        assertEquals(
            SafeTxAction.CowPreSignature(uid.toHex(), safe, 0x6a000000L, signed = true),
            SafeTxAction.of(safe, safeTx(settlement, presign)),
        )
        // Only on the settlement contract.
        assertIs<SafeTxAction.ContractCall>(SafeTxAction.of(safe, safeTx(recipient, presign)))
    }

    @Test
    fun anOwnersExecTransactionIsDecodedAndHeldToTheSafeRefusals() {
        val tx = safeTx(cow, Erc20.transferData(recipient, UInt256.of(1)))
        val call = SafeExecCall.data(tx, ByteArray(65) { 1 })
        val exec = request(DappAsk.SendTransaction(DappTransactionRequest(owner.address, safe, UInt256.ZERO, call, null)))
        val meaning = assertIs<DappCallMeaning.SafeExecution>(((review(exec).content) as DappSignContent.Transaction).transaction.meaning)
        assertEquals(safe, meaning.safe)
        assertEquals(SafeTxAction.TokenTransfer(cow, recipient, UInt256.of(1)), meaning.call.action(safe))
        assertEquals(65, meaning.call.signaturesSize)
        assertNull(review(exec).refusal)

        val withRefund = SafeTx(cow, UInt256.ZERO, tx.data, SafeOperation.CALL, UInt256.of(1), UInt256.ZERO, UInt256.of(1), Address.ZERO, recipient, 0)
        val refunded = request(
            DappAsk.SendTransaction(DappTransactionRequest(owner.address, safe, UInt256.ZERO, SafeExecCall.data(withRefund, ByteArray(65)), null)),
        )
        assertEquals(SafeTxRefusal.GasTerms, review(refunded).safeTxRefusal)

        val approve = "d4d9bdcd".hexToBytes() + ByteArray(32) { 9 }
        val approved = request(DappAsk.SendTransaction(DappTransactionRequest(owner.address, safe, UInt256.ZERO, approve, null)))
        assertIs<DappCallMeaning.SafeApproveHash>((review(approved).content as DappSignContent.Transaction).transaction.meaning)
    }

    @Test
    fun aSafeMessageIsRecognisedAndWarnedToHoldOnlyAHash() {
        val data = TypedData(
            types = mapOf(
                Eip712.DOMAIN_TYPE to listOf(TypedField("chainId", "uint256"), TypedField("verifyingContract", "address")),
                "SafeMessage" to listOf(TypedField("message", "bytes")),
            ),
            primaryType = "SafeMessage",
            domain = mapOf("chainId" to TypedValue.Number("1"), "verifyingContract" to TypedValue.Text(safe.hex)),
            message = mapOf("message" to TypedValue.Text("0x" + "ab".repeat(32))),
        )
        val review = review(typed(data))
        assertEquals(TypedDataMeaning.SafeMessage(safe, "ab".repeat(32)), (review.content as DappSignContent.Typed).meaning)
        assertEquals(setOf(DappSignWarning.SAFE_MESSAGE_HASH_ONLY), review.warnings)
    }

    @Test
    fun aSmartSessionGrantIsReadAndItsUnlimitedPowersFlagged() {
        val review = review(typed(smartSession()))
        val meaning = assertIs<TypedDataMeaning.SmartSession>((review.content as DappSignContent.Typed).meaning)
        val grant = meaning.grants.grants.single()
        assertEquals(safe, grant.account)
        assertEquals(UInt256.of(1), grant.chainId)
        assertNull(grant.enableBy, "2^256 - 1: no date")
        assertTrue(grant.anyCall)
        assertTrue(grant.sudo)
        assertTrue(grant.signsMessages)
        assertEquals(setOf(DappSignWarning.SESSION_KEY_GRANT, DappSignWarning.NO_CHAIN_ID), review.warnings)
    }

    @Test
    fun anExecTransactionNotDecodedExactlyIsRefusedSinceTheSafeStillRunsIt() {
        val enable = safeTx(safe, "610b5925".hexToBytes() + word(recipient))
        val exact = SafeExecCall.data(enable, ByteArray(65))
        // One trailing byte: the Safe ignores it and enables the module all the same.
        val padded = request(DappAsk.SendTransaction(DappTransactionRequest(owner.address, safe, UInt256.ZERO, exact + byteArrayOf(0), null)))
        val review = review(padded)
        assertIs<DappCallMeaning.SafeExecutionNotDecoded>((review.content as DappSignContent.Transaction).transaction.meaning)
        assertEquals(DappSignRefusal.SAFE_TX_REFUSED, review.refusal)
        assertEquals(SafeTxRefusal.UndecodedSelfCall, review.safeTxRefusal)
        // Decoded exactly, it is refused for what it does.
        val decoded = review(request(DappAsk.SendTransaction(DappTransactionRequest(owner.address, safe, UInt256.ZERO, exact, null))))
        assertEquals(SafeTxRefusal.ModuleEnabled(recipient), decoded.safeTxRefusal)
    }

    @Test
    fun safe150sMultiSendReadsTheZeroAddressAsTheSafeItself() {
        val batch = SafeMultiSend.data(
            listOf(BatchedCall(SafeOperation.CALL, Address.ZERO, UInt256.ZERO, "610b5925".hexToBytes() + word(recipient))),
        )
        val v150 = Address.of("0xa83c336b20401af773b6219ba5027174338d1836")
        val v141 = Address.of("0x9641d764fc13c8b624c04430c7356c1c7c8102e2")
        assertEquals(
            listOf(SafeTxAction.SettingsChange(SafeSettingsChange.ModuleEnabled(recipient))),
            assertIs<SafeTxAction.Batch>(SafeTxAction.of(safe, safeTx(v150, batch, SafeOperation.DELEGATECALL))).calls,
        )
        assertIs<SafeTxAction.ContractCall>(
            assertIs<SafeTxAction.Batch>(SafeTxAction.of(safe, safeTx(v141, batch, SafeOperation.DELEGATECALL))).calls.single(),
        )
    }

    private val safeAccount = Account(Network.ETHEREUM, safe, AccountKind.SAFE, "Safe", AccountCustody.Watched)
    private val accounts = ListAccountRepository(owner)

    /** A 2-of-3 Safe, as the light client reads it; [kind] set to change that. */
    private val kind = kotlinx.coroutines.flow.MutableStateFlow<eth.serotine.domain.repository.KindReading>(
        eth.serotine.domain.repository.KindReading.Verified(
            eth.serotine.domain.repository.AddressKind.Safe(
                eth.serotine.domain.repository.SafeState(
                    "1.4.1", l2 = false, listOf(owner.address, recipient, cow), 2, 55, emptyList(), null,
                ),
            ),
            100,
        ),
    )
    private val observeSafe = ObserveSafe(
        object : eth.serotine.domain.repository.AddressKinds {
            override fun observeKind(network: Network, address: Address) = kind
        },
        accounts,
    )
    private val networks = object : eth.serotine.domain.repository.NetworkSettings {
        override fun observe() = kotlinx.coroutines.flow.flowOf(setOf(Network.ETHEREUM))
        override suspend fun setEnabled(network: Network, on: Boolean): Boolean = error("not used")
    }
    private val requests = object : eth.serotine.domain.repository.DappRequests {
        val waiting = kotlinx.coroutines.flow.MutableStateFlow<List<DappRequest>>(emptyList())
        override fun observe() = waiting
        override suspend fun answerSignature(request: DappRequestId, signature: eth.serotine.domain.model.Signature) =
            eth.serotine.domain.repository.DappAnswer.DELIVERED
        override suspend fun answerNull(request: DappRequestId) = error("not used")
        override suspend fun answerChainSwitch(request: DappRequestId, network: Network) = error("not used")
        override suspend fun answerTransaction(request: DappRequestId, hash: String) = error("not used")
        override suspend fun refuse(request: DappRequestId, why: eth.serotine.domain.repository.DappRequestError) =
            error("not used")
        override suspend fun recordSafeTx(request: DappRequestId, safeTx: eth.serotine.domain.model.Digest) = error("not used")
    }
    private val signer = FakeSigner(FakeRecovery())

    /** `changeThreshold(3)` on a Safe of three owners: an n-of-n, which needs a recovery module (#54). */
    private val toThree = safeTx(safe, "694e80c3".hexToBytes() + UInt256.of(3).toBytes32())

    @Test
    fun aSafeTxLeavingTheSafeAnNofNIsRefusedWhenSignedAgainstItsVerifiedOwners() = kotlinx.coroutines.test.runTest {
        val asked = typed(toThree.typedData(Network.ETHEREUM.chainId, safe))
        requests.waiting.value = listOf(asked)
        val sign = SignDappMessage(requests, accounts, networks, signer, observeSafe, clock = { now })
        assertEquals(DappMessageSigning.SafeTxRefused(SafeTxRefusal.NeedsRecoveryModule(3)), sign(asked.id, eth.serotine.domain.repository.Unlock.WithBiometrics("x")))
        // A Safe that cannot be read is not signed for either.
        kind.value = eth.serotine.domain.repository.KindReading.Unavailable(eth.serotine.domain.repository.ReadUnavailable.NO_ENGINE)
        assertEquals(
            DappMessageSigning.SafeTxRefused(SafeTxRefusal.SafeUnavailable(eth.serotine.domain.repository.ReadUnavailable.NO_ENGINE)),
            sign(asked.id, eth.serotine.domain.repository.Unlock.WithBiometrics("x")),
        )
    }

    @Test
    fun anExecuteLeavingTheSafeAnNofNIsRefusedWhenQuoted() = kotlinx.coroutines.test.runTest {
        val exec = request(
            DappAsk.SendTransaction(DappTransactionRequest(owner.address, safe, UInt256.ZERO, SafeExecCall.data(toThree, ByteArray(65)), null)),
        )
        requests.waiting.value = listOf(exec)
        val quote = QuoteDappTransaction(
            requests, accounts, networks, ListCatalog(), FakeNativeBalances(), FakeTransactionInputs(), MemorySentTransfers(),
            MemoryTransactionHistory(), outbox = eth.serotine.domain.repository.Outbox.None, observeSafe = observeSafe,
            clock = { now },
        )
        assertEquals(DappTransactionQuoting.SafeTxRefused(SafeTxRefusal.NeedsRecoveryModule(3)), quote(exec.id))
    }

    @Test
    fun aModuleEnabledThroughAGnosisPayDelayIsRefusedAndTheCardLimitIsNot() {
        val delay = Address.of("0x22bdfd8d6238f034a0937fa2eec3ddbd4b039fe3")
        fun moduleTx(to: Address, data: ByteArray) = TypedData(
            types = mapOf(
                Eip712.DOMAIN_TYPE to listOf(TypedField("chainId", "uint256"), TypedField("verifyingContract", "address")),
                "ModuleTx" to listOf(TypedField("data", "bytes"), TypedField("salt", "bytes32")),
            ),
            primaryType = "ModuleTx",
            domain = mapOf("chainId" to TypedValue.Number("1"), "verifyingContract" to TypedValue.Text(delay.hex)),
            message = mapOf(
                "data" to TypedValue.Text(
                    "0x" + ("468721a7".hexToBytes() + word(to) + ByteArray(32) + UInt256.of(128).toBytes32() + ByteArray(32) +
                        UInt256.of(data.size.toLong()).toBytes32() + data + ByteArray((32 - data.size % 32) % 32)).toHex(),
                ),
                "salt" to TypedValue.Text("0x" + "00".repeat(32)),
            ),
        )
        // Not on Gnosis Chain: refused, whatever it asks.
        val enable = review(typed(moduleTx(safe, "610b5925".hexToBytes() + word(recipient))))
        assertEquals(DappSignRefusal.MODULE_TX_REFUSED, enable.refusal)
        assertEquals(eth.serotine.domain.usecase.ModuleTxRefusal.OtherNetwork, enable.moduleTxRefusal)
        val limit = "a8ec43ee".hexToBytes() + eth.serotine.domain.model.RolesAllowance.SPENDING_ALLOWANCE_KEY.hexToBytes() +
            UInt256.of(8).toBytes32() + UInt256.of(8).toBytes32() + UInt256.of(8).toBytes32() +
            UInt256.of(86_400).toBytes32() + ByteArray(32)
        fun tx(to: Address, data: ByteArray) =
            eth.serotine.domain.model.ModuleTransaction.of(Eip712.encode(moduleTx(to, data))!!)!!
        // On Gnosis Chain: the card's limit only, never a call of the Delay or the Safe.
        assertNull(tx(recipient, limit).cardLimitRefusal(Network.GNOSIS, safe))
        assertEquals(ModuleTxRefusal.NotCardLimit, tx(delay, limit).cardLimitRefusal(Network.GNOSIS, safe))
        assertEquals(ModuleTxRefusal.NotCardLimit, tx(safe, limit).cardLimitRefusal(Network.GNOSIS, safe))
        assertEquals(
            ModuleTxRefusal.NotCardLimit,
            tx(safe, "610b5925".hexToBytes() + word(recipient)).cardLimitRefusal(Network.GNOSIS, safe),
        )
        // A byte too many is no call read: refused.
        assertEquals(ModuleTxRefusal.NotCardLimit, tx(recipient, limit + byteArrayOf(0)).cardLimitRefusal(Network.GNOSIS))
    }

    @Test
    fun aCardLimitIsSignedOnlyThroughAVerifiedDelayOfTheWalletsAccountWithTheSignerAMember() = kotlinx.coroutines.test.runTest {
        val delay = Address.of("0x22bdfd8d6238f034a0937fa2eec3ddbd4b039fe3")
        fun found(generation: eth.serotine.domain.repository.DelayGeneration, members: List<Address>) =
            GnosisPayReading.Found(
                eth.serotine.domain.repository.DelayModule(delay, generation, 180, 1800, 0, 0, members),
                100, emptyList(), emptyList(), safeModules = emptyList(),
            )
        val readings = kotlinx.coroutines.flow.MutableStateFlow<Map<AccountId, GnosisPayReading>>(
            mapOf(AccountId(Network.GNOSIS, safe) to found(eth.serotine.domain.repository.DelayGeneration.CURRENT, listOf(owner.address))),
        )
        val check = GnosisPayDelayCheck { readings }
        assertIs<GnosisPayDelay.Verified>(check.check(delay, owner.address))
        assertEquals(GnosisPayDelay.Refused(ModuleTxRefusal.NotMember), check.check(delay, recipient))
        assertEquals(GnosisPayDelay.Refused(ModuleTxRefusal.NotAccountsDelay), check.check(recipient, owner.address))
        readings.value = mapOf(
            AccountId(Network.GNOSIS, safe) to found(eth.serotine.domain.repository.DelayGeneration.RETIRED, listOf(owner.address)),
        )
        assertEquals(GnosisPayDelay.Refused(ModuleTxRefusal.RetiredDelay), check.check(delay, owner.address))
    }

    private fun word(address: Address) = ByteArray(12) + address.hex.removePrefix("0x").hexToBytes()

    /** The session of the second screenshot: any call, the SudoPolicy, no expiry, on chain 1. */
    private fun smartSession(): TypedData {
        fun policy() = TypedValue.Struct(
            mapOf("policy" to TypedValue.Text(SmartSessionGrants.SUDO_POLICY.hex), "initData" to TypedValue.Text("0x")),
        )
        val session = mapOf(
            "account" to TypedValue.Text(safe.hex),
            "expires" to TypedValue.Text(UInt256.MAX.toDecimal()),
            "nonce" to TypedValue.Number("0"),
            "permissions" to TypedValue.Struct(
                mapOf(
                    "actions" to TypedValue.Items(
                        listOf(
                            TypedValue.Struct(
                                mapOf(
                                    "actionTargetSelector" to TypedValue.Text("0x00000001"),
                                    "actionTarget" to TypedValue.Text(SmartSessionGrants.ANY_TARGET.hex),
                                    "actionPolicies" to TypedValue.Items(listOf(policy())),
                                ),
                            ),
                        ),
                    ),
                    "erc7739Policies" to TypedValue.Struct(
                        mapOf(
                            "allowedERC7739Content" to TypedValue.Items(
                                listOf(
                                    TypedValue.Struct(
                                        mapOf(
                                            "appDomainSeparator" to TypedValue.Text("0x" + "00".repeat(32)),
                                            "contentName" to TypedValue.Items(listOf(TypedValue.Text(""))),
                                        ),
                                    ),
                                ),
                            ),
                            "erc1271Policies" to TypedValue.Items(listOf(policy())),
                        ),
                    ),
                    "lockTagPolicies" to TypedValue.Struct(
                        mapOf("lockTag" to TypedValue.Text("0x" + "00".repeat(12)), "claimPolicies" to TypedValue.Items(emptyList())),
                    ),
                    "permitGenericPolicy" to TypedValue.Bool(true),
                ),
            ),
            "salt" to TypedValue.Text("0x" + "11".repeat(32)),
            "sessionValidator" to TypedValue.Text(recipient.hex),
            "sessionValidatorInitData" to TypedValue.Text("0x1234"),
            "smartSessionEmissary" to TypedValue.Text("0xad568b3f825a8d5ffc06dd3253526b64d810ae89"),
        )
        return TypedData(
            types = SMART_SESSION_TYPES,
            primaryType = "MultiChainSession",
            domain = mapOf("name" to TypedValue.Text("SmartSessionEmissary"), "version" to TypedValue.Text("1")),
            message = mapOf(
                "sessionsAndChainIds" to TypedValue.Items(
                    listOf(TypedValue.Struct(mapOf("chainId" to TypedValue.Number("1"), "session" to TypedValue.Struct(session)))),
                ),
            ),
        )
    }

    companion object {
        private fun fields(vararg pairs: Pair<String, String>) = pairs.map { (name, type) -> TypedField(name, type) }

        val SMART_SESSION_TYPES = mapOf(
            Eip712.DOMAIN_TYPE to fields("name" to "string", "version" to "string"),
            "MultiChainSession" to fields("sessionsAndChainIds" to "ChainSession[]"),
            "ChainSession" to fields("chainId" to "uint64", "session" to "SignedSession"),
            "SignedSession" to fields(
                "account" to "address", "expires" to "uint256", "nonce" to "uint256",
                "permissions" to "SignedPermissions", "salt" to "bytes32", "sessionValidator" to "address",
                "sessionValidatorInitData" to "bytes", "smartSessionEmissary" to "address",
            ),
            "SignedPermissions" to fields(
                "actions" to "ActionData[]", "erc7739Policies" to "ERC7739Data", "lockTagPolicies" to "LockTagData",
                "permitGenericPolicy" to "bool",
            ),
            "ActionData" to fields("actionTargetSelector" to "bytes4", "actionTarget" to "address", "actionPolicies" to "PolicyData[]"),
            "ERC7739Data" to fields("allowedERC7739Content" to "ERC7739Context[]", "erc1271Policies" to "PolicyData[]"),
            "ERC7739Context" to fields("appDomainSeparator" to "bytes32", "contentName" to "string[]"),
            "LockTagData" to fields("lockTag" to "bytes12", "claimPolicies" to "PolicyData[]"),
            "PolicyData" to fields("policy" to "address", "initData" to "bytes"),
        )
    }
}
