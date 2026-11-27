package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the public wallet-testing sites send, read as those sites publish it (docs/walletconnect.md, *Testing with
 * public test dApps*): MetaMask's test dApp's token calls and malicious demos, and the known test signatures.
 */
class TestDappRequestsTest {

    private fun hex(text: String) = text.removePrefix("0x").hexToBytes()
    private val alice = Address.of("0x" + "a1".repeat(20))
    private val bob = Address.of("0x" + "b0".repeat(20))

    @Test
    fun metamasksMaliciousDemosAreDecodedAndTheirAddressesKnown() {
        // MetaMask's test dApp, ppom/sharedConstants.js: setApprovalForAll(0xb854…5ede, true).
        val all = hex("0xa22cb465000000000000000000000000b85492afc686d5ca405e3cd4f50b05d358c75ede0000000000000000000000000000000000000000000000000000000000000001")
        val call = TokenCalls.of(all)
        assertEquals(TokenCall.ApprovalForAll(Address.of("0xb85492afc686d5ca405e3cd4f50b05d358c75ede"), approved = true), call)
        assertTrue(call!!.grantsApproval)
        assertEquals("a malicious operator", KnownTestRequests.address(Address.of("0xb85492afc686d5ca405e3cd4f50b05d358c75ede"))?.role)
        assertEquals("a malicious spender", KnownTestRequests.address(Address.of("0xe50a2dbc466d01a34c3e8b7e8e45fce4f7da39e6"))?.role)
        assertNull(KnownTestRequests.address(alice))
        // A bool word other than 0 or 1 is no setApprovalForAll.
        assertNull(TokenCalls.of(all.copyOf().also { it[it.size - 1] = 2 }))
    }

    @Test
    fun tokenCallsAreReadExactly() {
        fun encode(signature: String, vararg args: AbiValue): ByteArray {
            val function = AbiFunction.parse(signature)!!
            return AbiWords.selector(signature) + AbiDecoder.encode(
                AbiType.TupleType(function.params),
                AbiValue.TupleValue(args.map { null to it }),
            )
        }
        fun a(address: Address) = AbiValue.AddressValue(address)
        fun u(value: Long) = AbiValue.UintValue(UInt256.of(value), 256)
        assertEquals(
            TokenCall.TransferFrom(alice, bob, UInt256.of(7)),
            TokenCalls.of(encode("transferFrom(address,address,uint256)", a(alice), a(bob), u(7))),
        )
        assertEquals(
            TokenCall.AllowanceIncrease(bob, UInt256.of(9)),
            TokenCalls.of(encode("increaseAllowance(address,uint256)", a(bob), u(9))),
        )
        assertEquals(
            TokenCall.NftTransfer(alice, bob, UInt256.of(3), 2),
            TokenCalls.of(
                encode("safeTransferFrom(address,address,uint256,bytes)", a(alice), a(bob), u(3), AbiValue.BytesValue("beef", fixed = false)),
            ),
        )
        assertEquals(
            TokenCall.MultiTokenTransfer(alice, bob, UInt256.of(1), UInt256.of(50)),
            TokenCalls.of(
                encode("safeTransferFrom(address,address,uint256,uint256,bytes)", a(alice), a(bob), u(1), u(50), AbiValue.BytesValue("", fixed = false)),
            ),
        )
        val batch = encode(
            "safeBatchTransferFrom(address,address,uint256[],uint256[],bytes)",
            a(alice), a(bob), AbiValue.ArrayValue(listOf(u(1), u(2))), AbiValue.ArrayValue(listOf(u(10), u(20))),
            AbiValue.BytesValue("", fixed = false),
        )
        assertEquals(
            TokenCall.MultiTokenBatchTransfer(alice, bob, listOf(UInt256.of(1) to UInt256.of(10), UInt256.of(2) to UInt256.of(20))),
            TokenCalls.of(batch),
        )
        // A trailing byte: not read.
        assertNull(TokenCalls.of(batch + byteArrayOf(0)))
    }

    private fun mail(chainId: String) = TypedData(
        types = mapOf(
            "EIP712Domain" to listOf(
                TypedField("name", "string"), TypedField("version", "string"), TypedField("chainId", "uint256"),
                TypedField("verifyingContract", "address"),
            ),
            "Person" to listOf(TypedField("name", "string"), TypedField("wallet", "address")),
            "Mail" to listOf(TypedField("from", "Person"), TypedField("to", "Person"), TypedField("contents", "string")),
        ),
        primaryType = "Mail",
        domain = mapOf(
            "name" to TypedValue.Text("Ether Mail"), "version" to TypedValue.Text("1"),
            "chainId" to TypedValue.Number(chainId),
            "verifyingContract" to TypedValue.Text("0xcccccccccccccccccccccccccccccccccccccccc"),
        ),
        message = mapOf(
            "from" to TypedValue.Struct(mapOf("name" to TypedValue.Text("Cow"), "wallet" to TypedValue.Text("0xcd2a3d9f938e13cd947ec05abc7fe734df8dd826"))),
            "to" to TypedValue.Struct(mapOf("name" to TypedValue.Text("Bob"), "wallet" to TypedValue.Text("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))),
            "contents" to TypedValue.Text("Hello, Bob!"),
        ),
    )

    @Test
    fun theKnownTestSignaturesAreRecognisedOnAnyChainAndNothingElseIs() {
        assertEquals(KnownTestRequests.REOWN, KnownTestRequests.typedData(Eip712.encode(mail("11155111"))!!)?.site)
        assertEquals(KnownTestRequests.REOWN, KnownTestRequests.typedData(Eip712.encode(mail("1"))!!)?.site)
        val other = mail("1").let { it.copy(message = it.message + ("contents" to TypedValue.Text("Hello, Eve!"))) }
        assertNull(KnownTestRequests.typedData(Eip712.encode(other)!!))
        // A domain with no chain id is another signature, valid on every chain: not the test.
        val anyChain = mail("1").let { data ->
            data.copy(
                types = data.types + ("EIP712Domain" to data.types.getValue("EIP712Domain").filter { it.name != "chainId" }),
                domain = data.domain - "chainId",
            )
        }
        assertNull(KnownTestRequests.typedData(Eip712.encode(anyChain)!!))
        // Nor is a name in another case.
        val shouting = mail("1").let { it.copy(domain = it.domain + ("name" to TypedValue.Text("ETHER MAIL"))) }
        assertNull(KnownTestRequests.typedData(Eip712.encode(shouting)!!))
        assertEquals(KnownTestRequests.REOWN, KnownTestRequests.message("My email is john@doe.com - 1793700000000")?.site)
        assertEquals(KnownTestRequests.METAMASK, KnownTestRequests.message("Example `personal_sign` message")?.site)
        assertNull(KnownTestRequests.message("My email is john@doe.com - soon"))
    }
}
