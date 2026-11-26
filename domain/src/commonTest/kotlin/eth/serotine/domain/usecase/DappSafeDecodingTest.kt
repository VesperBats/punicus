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
        assertNull(grant.expires, "2^256 - 1 never expires")
        assertTrue(grant.anyCall)
        assertTrue(grant.sudo)
        assertTrue(grant.signsMessages)
        assertEquals(setOf(DappSignWarning.SESSION_KEY_GRANT, DappSignWarning.NO_CHAIN_ID), review.warnings)
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
