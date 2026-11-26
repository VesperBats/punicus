package eth.serotine.domain.model

/**
 * A grant of a session key on a smart account, as Rhinestone's Smart Sessions v2 (`SmartSessionEmissary`) has an
 * account's owner sign it: a `MultiChainSession`, one [SmartSessionGrant] per chain. Signed, it lets whoever holds
 * the session's key act for the account within the session's policies, without asking the owner again
 * (docs/walletconnect.md, *Typed data*, *Session keys*).
 *
 * Its domain is `EIP712Domain(string name,string version)`, name `SmartSessionEmissary`, version `1`: no chain and no
 * verifying contract, so no descriptor can be bound to code for it (docs/clear-signing.md, A4), and what it means
 * is read here from the struct alone, member by member, exactly as Smart Sessions v2 defines it.
 */
data class SmartSessionGrants(val grants: List<SmartSessionGrant>) {

    companion object {
        /** The domain's name. */
        const val DOMAIN_NAME = "SmartSessionEmissary"

        private const val DOMAIN = "EIP712Domain(string name,string version)"

        /** `encodeType` of the primary type, every struct it names included, as Smart Sessions v2 defines them. */
        const val MULTI_CHAIN_SESSION_TYPE =
            "MultiChainSession(ChainSession[] sessionsAndChainIds)" +
                "ActionData(bytes4 actionTargetSelector,address actionTarget,PolicyData[] actionPolicies)" +
                "ChainSession(uint64 chainId,SignedSession session)" +
                "ERC7739Context(bytes32 appDomainSeparator,string[] contentName)" +
                "ERC7739Data(ERC7739Context[] allowedERC7739Content,PolicyData[] erc1271Policies)" +
                "LockTagData(bytes12 lockTag,PolicyData[] claimPolicies)" +
                "PolicyData(address policy,bytes initData)" +
                "SignedPermissions(ActionData[] actions,ERC7739Data erc7739Policies,LockTagData lockTagPolicies," +
                "bool permitGenericPolicy)" +
                "SignedSession(address account,uint256 expires,uint256 nonce,SignedPermissions permissions," +
                "bytes32 salt,address sessionValidator,bytes sessionValidatorInitData,address smartSessionEmissary)"

        /** Smart Sessions' flag for an action on any contract (`FALLBACK_TARGET_FLAG`). */
        val ANY_TARGET: Address = Address.of("0x0000000000000000000000000000000000000001")

        /**
         * Smart Sessions' flags for an action on any function (`FALLBACK_TARGET_SELECTOR_FLAG`), and on any function
         * the Smart Sessions contract itself included (`…_PERMITTED_TO_CALL_SMARTSESSION`).
         */
        val ANY_SELECTOR: Set<String> = setOf("00000001", "00000002")

        /** Rhinestone's SudoPolicy, which allows whatever it is asked about (rhinestonewtf/constants). */
        val SUDO_POLICY: Address = Address.of("0x0000003111cd8e92337c100f22b7a9dbf8dee301")

        /** Rhinestone's OwnableValidator, whose init data names the session key's owners and their threshold. */
        val OWNABLE_VALIDATOR: Address = Address.of("0x2483da3a338895199e5e538530213157e931bf06")

        // Year 9999: an expiry past it is no date anyone can read, and means never.
        private const val MAX_DATE_SECONDS = 253_402_300_799L

        /** What [encoded] grants, when it is exactly a Smart Sessions v2 `MultiChainSession`; null otherwise. */
        fun of(encoded: EncodedTypedData): SmartSessionGrants? {
            val data = encoded.data
            if (Eip712.encodeType(data.types, data.primaryType) != MULTI_CHAIN_SESSION_TYPE) return null
            if (Eip712.encodeType(data.types, Eip712.DOMAIN_TYPE) != DOMAIN) return null
            if ((data.domain["name"] as? TypedValue.Text)?.text != DOMAIN_NAME) return null
            if ((data.domain["version"] as? TypedValue.Text)?.text != "1") return null
            val items = (data.message.getValue("sessionsAndChainIds") as TypedValue.Items).items
            return SmartSessionGrants(items.map { grant((it as TypedValue.Struct).fields) })
        }

        private fun grant(chainSession: Map<String, TypedValue>): SmartSessionGrant {
            val session = chainSession.struct("session")
            val permissions = session.struct("permissions")
            val erc7739 = permissions.struct("erc7739Policies")
            val lockTag = permissions.struct("lockTagPolicies")
            val expires = Eip712.unsignedOf(session.getValue("expires"))
            val validator = address(session.getValue("sessionValidator"))
            val validatorInit = Eip712.bytesOf(session.getValue("sessionValidatorInitData"))
            return SmartSessionGrant(
                chainId = Eip712.unsignedOf(chainSession.getValue("chainId")),
                account = address(session.getValue("account")),
                expires = expires.toLongOrNull()?.takeIf { it <= MAX_DATE_SECONDS },
                nonce = Eip712.unsignedOf(session.getValue("nonce")),
                actions = permissions.items("actions").map { item ->
                    val action = (item as TypedValue.Struct).fields
                    SessionAction(
                        target = address(action.getValue("actionTarget")),
                        selector = Eip712.bytesOf(action.getValue("actionTargetSelector")).toHex(),
                        policies = policies(action.items("actionPolicies")),
                    )
                },
                messagePolicies = policies(erc7739.items("erc1271Policies")),
                messageContexts = erc7739.items("allowedERC7739Content").size,
                lockTag = Eip712.bytesOf(lockTag.getValue("lockTag")).toHex(),
                claimPolicies = policies(lockTag.items("claimPolicies")),
                genericPolicy = (permissions.getValue("permitGenericPolicy") as TypedValue.Bool).value,
                validator = validator,
                keyOwners = if (validator == OWNABLE_VALIDATOR) ownableOwners(validatorInit) else null,
                validatorInitSize = validatorInit.size,
                emissary = address(session.getValue("smartSessionEmissary")),
            )
        }

        private fun policies(items: List<TypedValue>): List<Address> =
            items.map { address((it as TypedValue.Struct).fields.getValue("policy")) }

        /**
         * The owners and threshold OwnableValidator's init data names, `abi.encode(uint256 threshold, address[]
         * owners)`, read strictly; null when it is not exactly that.
         */
        private fun ownableOwners(init: ByteArray): SessionKeyOwners? {
            val word = AbiWords.WORD
            if (init.size < 3 * word || init.size % word != 0) return null
            val words = AbiWords.words(init, init.size / word) ?: return null
            val threshold = AbiWords.long(words[0]) ?: return null
            if (AbiWords.long(words[1]) != 2L * word) return null
            val count = AbiWords.long(words[2])?.takeIf { it == (words.size - 3).toLong() } ?: return null
            val owners = (0 until count.toInt()).map { AbiWords.address(words[3 + it]) ?: return null }
            return SessionKeyOwners(owners, threshold)
        }

        private fun Map<String, TypedValue>.struct(name: String) = (getValue(name) as TypedValue.Struct).fields

        private fun Map<String, TypedValue>.items(name: String) = (getValue(name) as TypedValue.Items).items

        // The values were checked against their types by Eip712.encode: these only read them.
        private fun address(value: TypedValue): Address = Address.of((value as TypedValue.Text).text)
    }
}

/**
 * One chain's session of a [SmartSessionGrants]: on chain [chainId], whoever holds the session key ([validator]
 * checks its signatures; [keyOwners] when that is Rhinestone's OwnableValidator) may act for [account] until
 * [expires] (unix seconds; null: never), through the [actions] allowed, sign messages for it under
 * [messagePolicies], and claim its locked funds under [claimPolicies].
 */
data class SmartSessionGrant(
    val chainId: UInt256,
    val account: Address,
    val expires: Long?,
    val nonce: UInt256,
    val actions: List<SessionAction>,
    /** The policies that allow the session to sign messages for the account (EIP-1271); empty: none. */
    val messagePolicies: List<Address>,
    /** How many apps' typed data it is allowed to sign (ERC-7739 contexts). */
    val messageContexts: Int,
    /** The lock tag (12 bytes, hex) of The Compact's resource lock its claim policies cover. */
    val lockTag: String,
    val claimPolicies: List<Address>,
    /** Whether a call no action names may fall back to the account's generic policies. */
    val genericPolicy: Boolean,
    val validator: Address,
    val keyOwners: SessionKeyOwners?,
    val validatorInitSize: Int,
    val emissary: Address,
) {
    /** Whether an action allows calls on any contract or any function. */
    val anyCall: Boolean
        get() = actions.any { it.target == SmartSessionGrants.ANY_TARGET || it.selector in SmartSessionGrants.ANY_SELECTOR }

    /** Whether one of its policies is the SudoPolicy, which allows everything. */
    val sudo: Boolean
        get() = (actions.flatMap { it.policies } + messagePolicies + claimPolicies).any { it == SmartSessionGrants.SUDO_POLICY }

    /** Whether it lets the session key sign messages for the account, which can grant approvals (permits). */
    val signsMessages: Boolean get() = messagePolicies.isNotEmpty()
}

/** An action a session may take: calls of [selector] (hex, no `0x`) on [target], under [policies]. */
data class SessionAction(val target: Address, val selector: String, val policies: List<Address>)

/** The owners of a session key, as OwnableValidator holds them: [threshold] of [owners] sign for it. */
data class SessionKeyOwners(val owners: List<Address>, val threshold: Long)
