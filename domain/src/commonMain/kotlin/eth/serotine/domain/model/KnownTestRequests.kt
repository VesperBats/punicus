package eth.serotine.domain.model

/**
 * A request a public wallet-testing site sends, recognised by its exact content: which [site] sends it, and its
 * [name] there. Recognising one says only that the content is that test's, byte for byte (any site can send the
 * same); what it lets someone do is still decoded and shown as for any other request.
 */
data class KnownTestRequest(val site: String, val name: String)

/**
 * An address a wallet-testing site uses on purpose as a bad actor in its demos: [site] and what it stands for
 * there ([role]).
 */
data class KnownTestAddress(val site: String, val role: String)

/**
 * The requests and addresses of the public wallet-testing sites a user may test Punicus with over WalletConnect
 * (docs/walletconnect.md, *Testing with public test dApps*): MetaMask's test dApp (metamask.github.io/test-dapp),
 * Reown's sample dApp (react-app.walletconnect.com) and Walletbeat's test playground (walletbeat.eth.limo/test/).
 * Built in, as their sources publish them; a test site that changes its content is no longer recognised.
 */
object KnownTestRequests {

    const val METAMASK = "MetaMask's test dApp"
    const val REOWN = "Reown's sample dApp"
    const val WALLETBEAT = "Walletbeat's test playground"

    private data class Fingerprint(
        val encodeType: String,
        val domainType: String,
        val domain: Map<String, String>,
        val messageHash: String,
    )

    private fun fields(vararg pairs: Pair<String, String>) = pairs.map { (name, type) -> TypedField(name, type) }
    private fun text(value: String) = TypedValue.Text(value)

    /** Ether Mail as MetaMask's test dApp sends it for "Sign Typed Data V4" (an unused `Group` type and all). */
    private val ETHER_MAIL_V4 = TypedData(
        types = mapOf(
            Eip712.DOMAIN_TYPE to fields(
                "name" to "string", "version" to "string", "chainId" to "uint256", "verifyingContract" to "address",
            ),
            "Group" to fields("name" to "string", "members" to "Person[]"),
            "Mail" to fields("from" to "Person", "to" to "Person[]", "contents" to "string", "attachment" to "bytes"),
            "Person" to fields("name" to "string", "wallets" to "address[]"),
        ),
        primaryType = "Mail",
        domain = mapOf(
            "name" to text("Ether Mail"), "version" to text("1"), "chainId" to TypedValue.Number("1"),
            "verifyingContract" to text("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"),
        ),
        message = mapOf(
            "contents" to text("Hello, Bob!"),
            "from" to TypedValue.Struct(
                mapOf(
                    "name" to text("Cow"),
                    "wallets" to TypedValue.Items(
                        listOf(text("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"), text("0xDeaDbeefdEAdbeefdEadbEEFdeadbeEFdEaDbeeF")),
                    ),
                ),
            ),
            "to" to TypedValue.Items(
                listOf(
                    TypedValue.Struct(
                        mapOf(
                            "name" to text("Bob"),
                            "wallets" to TypedValue.Items(
                                listOf(
                                    text("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"),
                                    text("0xB0BdaBea57B0BDABeA57b0bdABEA57b0BDabEa57"),
                                    text("0xB0B0b0b0b0b0B000000000000000000000000000"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            "attachment" to text("0x"),
        ),
    )

    /** Ether Mail as EIP-712 itself gives it, which Reown's sample dApp sends for `eth_signTypedData_v4`. */
    private val ETHER_MAIL = TypedData(
        types = mapOf(
            Eip712.DOMAIN_TYPE to fields(
                "name" to "string", "version" to "string", "chainId" to "uint256", "verifyingContract" to "address",
            ),
            "Person" to fields("name" to "string", "wallet" to "address"),
            "Mail" to fields("from" to "Person", "to" to "Person", "contents" to "string"),
        ),
        primaryType = "Mail",
        domain = ETHER_MAIL_V4.domain,
        message = mapOf(
            "from" to TypedValue.Struct(mapOf("name" to text("Cow"), "wallet" to text("0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"))),
            "to" to TypedValue.Struct(mapOf("name" to text("Bob"), "wallet" to text("0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"))),
            "contents" to text("Hello, Bob!"),
        ),
    )

    /** Walletbeat's "EIP-712 Test Signature". */
    private val WALLETBEAT_TEST = TypedData(
        types = mapOf(
            Eip712.DOMAIN_TYPE to fields(
                "name" to "string", "version" to "string", "chainId" to "uint256", "verifyingContract" to "address",
                "salt" to "bytes32",
            ),
            "TestMessage" to fields("purpose" to "string", "message" to "string"),
        ),
        primaryType = "TestMessage",
        domain = mapOf(
            "name" to text("Test Signature App"), "version" to text("1"), "chainId" to TypedValue.Number("1"),
            "verifyingContract" to text("0x0000000000000000000000000000000000000000"),
            "salt" to text("0x" + "00".repeat(32)),
        ),
        message = mapOf(
            "purpose" to text("Educational Testing Only"),
            "message" to text(
                "This signature is for testing purposes only. It does not authorize any transactions, transfers, " +
                    "or approvals.",
            ),
        ),
    )

    private val TYPED: List<Pair<Fingerprint, KnownTestRequest>> = listOf(
        ETHER_MAIL_V4 to KnownTestRequest(METAMASK, "Sign Typed Data V4 (Ether Mail, EIP-712's example)"),
        ETHER_MAIL to KnownTestRequest(REOWN, "eth_signTypedData_v4 (Ether Mail, EIP-712's example)"),
        WALLETBEAT_TEST to KnownTestRequest(WALLETBEAT, "EIP-712 Test Signature"),
    ).map { (data, known) -> fingerprint(checkNotNull(Eip712.encode(data))) to known }

    /**
     * The struct, the domain's type (so its members, `chainId` among them, in order), the domain's values but its
     * chain id's, which follows the network the test is run on, exactly (an address in any case), and the message.
     */
    private fun fingerprint(encoded: EncodedTypedData): Fingerprint {
        val data = encoded.data
        val domain = data.domain.filterKeys { it != "chainId" }.mapValues { (name, value) ->
            val text = (value as? TypedValue.Text)?.text ?: value.toString()
            if (name == "verifyingContract") text.lowercase() else text
        }
        return Fingerprint(
            Eip712.encodeType(data.types, data.primaryType),
            Eip712.encodeType(data.types, Eip712.DOMAIN_TYPE),
            domain,
            encoded.messageHash,
        )
    }

    /** The test [encoded] is, when its struct, its domain (on any chain) and its message are exactly one's. */
    fun typedData(encoded: EncodedTypedData): KnownTestRequest? {
        val print = fingerprint(encoded)
        return TYPED.firstOrNull { it.first == print }?.second
    }

    private const val WALLETBEAT_MESSAGE = "This is a safe test message for educational purposes only. It does not " +
        "authorize any transactions or actions."
    private val REOWN_MESSAGE = Regex("^My email is john@doe\\.com - [0-9]{1,16}$")

    /** The test a `personal_sign` [text] is, when it is exactly one's. */
    fun message(text: String): KnownTestRequest? = when {
        text == WALLETBEAT_MESSAGE -> KnownTestRequest(WALLETBEAT, "Simple Test Message")
        text == "Example `personal_sign` message" -> KnownTestRequest(METAMASK, "Personal Sign")
        REOWN_MESSAGE.matches(text) -> KnownTestRequest(REOWN, "personal_sign")
        else -> null
    }

    private val ADDRESSES: Map<Address, KnownTestAddress> = mapOf(
        "0x5fbdb2315678afecb367f032d93f642f64180aa3" to "a malicious recipient",
        "0xe50a2dbc466d01a34c3e8b7e8e45fce4f7da39e6" to "a malicious spender",
        "0xb85492afc686d5ca405e3cd4f50b05d358c75ede" to "a malicious operator",
        "0x000062accd1a9d62ef428ec86ca3dd4f45120000" to "a malicious contract",
        "0x00008f1149168c1d2fa1eba1ad3e9cd644510000" to "a malicious contract",
    ).map { (hex, role) -> Address.of(hex) to KnownTestAddress(METAMASK, role) }.toMap()

    /** What [address] stands for in a test site's malicious-request demos, when it is one of them. */
    fun address(address: Address): KnownTestAddress? = ADDRESSES[address]
}
