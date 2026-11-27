package eth.serotine.ui.dapprequest

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.ShownText
import eth.serotine.domain.model.SmartSessionGrants
import eth.serotine.domain.model.ModuleTransaction
import eth.serotine.domain.model.Token
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.TypedValue
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.usecase.DappSignContent
import eth.serotine.domain.usecase.MAX_SHOWN_VALUES
import eth.serotine.ui.components.Eip55

/** One row of typed data shown field by field: [label] at [depth], and its [value] (null for a struct or an array). */
data class TypedRow(val depth: Int, val label: String, val value: String?)

/**
 * Typed data as screen D5 shows it (docs/walletconnect.md, *Typed data*), display-ready: what it lets
 * someone do for the structs this app knows, its domain, and every field. Amounts are in a built-in
 * token's units where the token is one, else raw and said so; times are formatted with [formatTime].
 */
internal class TypedDataText(private val content: DappSignContent.Typed, private val formatTime: (Long) -> String) {

    private val data: TypedData get() = content.typed.data

    /** The screen's title. */
    val title: String
        get() = when (content.meaning) {
            is TypedDataMeaning.Permit, is TypedDataMeaning.Permit2Allowance, is TypedDataMeaning.Permit2Transfer ->
                "Approval request"
            is TypedDataMeaning.CowOrder -> "Swap order to sign"
            is TypedDataMeaning.SafeTransaction -> "Safe transaction to sign"
            is TypedDataMeaning.SafeMessage -> "Safe message to sign"
            is TypedDataMeaning.SmartSession -> "Session key to grant"
            is TypedDataMeaning.ModuleTx ->
                if ((content.meaning as TypedDataMeaning.ModuleTx).tx.call?.allowance?.spending == true) "Card limit change to sign" else "Delay transaction to sign"
            null -> "Data to sign"
        }

    /** What the system's biometric prompt says is signed: nothing the dApp states. */
    val promptSubject: String
        get() = when (content.meaning) {
            is TypedDataMeaning.Permit, is TypedDataMeaning.Permit2Allowance, is TypedDataMeaning.Permit2Transfer ->
                "Sign a dApp's approval"
            is TypedDataMeaning.CowOrder -> "Sign a swap order for a dApp"
            is TypedDataMeaning.SafeTransaction -> "Sign a Safe transaction"
            is TypedDataMeaning.SafeMessage -> "Sign a message for a Safe"
            is TypedDataMeaning.SmartSession -> "Grant a session key"
            is TypedDataMeaning.ModuleTx -> "Sign a transaction for a Delay module"
            null -> "Sign a dApp's data"
        }

    /** What signing it lets someone do, in a sentence; null when the struct is not one this app knows. */
    val meaning: String?
        get() = when (val meaning = content.meaning) {
            is TypedDataMeaning.Permit -> "Lets ${short(meaning.spender)} take up to " +
                "${amount(meaning.value, meaning.token)} from this account, until ${until(meaning.deadline)}. " +
                APPROVAL_NOTE
            is TypedDataMeaning.Permit2Allowance -> "Lets ${short(meaning.spender)} take up to " +
                "${amount(meaning.amount, meaning.token, MAX_UINT160)} from this account through Permit2, " +
                "${expiration(meaning.expiration)}. $APPROVAL_NOTE"
            is TypedDataMeaning.Permit2Transfer -> "Lets ${short(meaning.spender)} take " +
                "${amount(meaning.amount, meaning.token)} from this account once, through Permit2, until " +
                "${until(meaning.deadline)}. $APPROVAL_NOTE"
            is TypedDataMeaning.CowOrder -> if (meaning.kind == "buy") {
                "An order on CoW Protocol to buy ${amount(meaning.buyAmount, meaning.buyToken)} for at most " +
                    "${amount(meaning.sellAmount, meaning.sellToken)}, until ${until(meaning.validTo)}. $ORDER_NOTE"
            } else {
                "An order on CoW Protocol to sell ${amount(meaning.sellAmount, meaning.sellToken)} for at least " +
                    "${amount(meaning.buyAmount, meaning.buyToken)}, until ${until(meaning.validTo)}. $ORDER_NOTE"
            }
            // Its own block says what it does (DappSafeTxSection).
            is TypedDataMeaning.SafeTransaction -> "A transaction of the Safe ${short(meaning.safe)}, for its " +
                "owners to sign. Once enough of them have, anyone may execute it. What it does is decoded below."
            is TypedDataMeaning.SafeMessage -> "A message signed for the Safe ${short(meaning.safe)}, as one of " +
                "its owners: once enough of them have, contracts take it as the Safe's own signature (EIP-1271). " +
                "The request holds only a hash of the message, so Punicus cannot show what the Safe signs."
            is TypedDataMeaning.SmartSession -> sessionMeaning(meaning)
            is TypedDataMeaning.ModuleTx -> moduleMeaning(meaning.tx)
            null -> null
        }

    /** What to know before granting a session key, at the top: each power that has no limit. */
    val meaningWarnings: List<String>
        get() = (content.meaning as? TypedDataMeaning.SmartSession)?.grants?.grants.orEmpty().flatMap { grant ->
            listOfNotNull(
                SESSION_ANY_CALL.takeIf { grant.anyCall },
                SESSION_SUDO.takeIf { grant.sudo },
                // Smart Sessions sets no end to a session once enabled; no time policy is read here.
                SESSION_NEVER_EXPIRES,
                SESSION_SIGNS_MESSAGES.takeIf { grant.signsMessages },
                SESSION_GENERIC.takeIf { grant.genericPolicy },
            )
        }.distinct()

    /** The decoded struct's parts, each in full. */
    val meaningFields: List<SignInField>
        get() = when (val meaning = content.meaning) {
            is TypedDataMeaning.Permit -> listOf(
                SignInField("Token", token(meaning.token)),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amount(meaning.value, meaning.token)),
                SignInField("Until", until(meaning.deadline)),
            )
            is TypedDataMeaning.Permit2Allowance -> listOf(
                SignInField("Token", token(meaning.token)),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amount(meaning.amount, meaning.token, MAX_UINT160)),
                SignInField("Allowance", expiration(meaning.expiration)),
                SignInField("Signature good until", until(meaning.sigDeadline)),
            )
            is TypedDataMeaning.Permit2Transfer -> listOf(
                SignInField("Token", token(meaning.token)),
                SignInField("Spender", Eip55.format(meaning.spender)),
                SignInField("Amount", amount(meaning.amount, meaning.token)),
                SignInField("Until", until(meaning.deadline)),
            )
            is TypedDataMeaning.CowOrder -> listOf(
                SignInField("Sell", "${amount(meaning.sellAmount, meaning.sellToken)} (${token(meaning.sellToken)})"),
                SignInField("Buy", "${amount(meaning.buyAmount, meaning.buyToken)} (${token(meaning.buyToken)})"),
                SignInField(
                    "Paid to",
                    if (meaning.receiver == Address.ZERO) "This account" else Eip55.format(meaning.receiver),
                ),
                SignInField("Valid until", until(meaning.validTo)),
                SignInField("Partly filled", if (meaning.partiallyFillable) "Allowed" else "No: all at once or not"),
            )
            is TypedDataMeaning.SafeTransaction -> emptyList()
            is TypedDataMeaning.ModuleTx -> moduleFields(meaning.tx)
            is TypedDataMeaning.SafeMessage -> listOf(
                SignInField("Safe", Eip55.format(meaning.safe)),
                SignInField("Message (its hash)", "0x${meaning.message}"),
            )
            is TypedDataMeaning.SmartSession -> {
                val grants = meaning.grants.grants
                grants.flatMapIndexed { index, grant ->
                    val label = if (grants.size == 1) "" else " (session ${index + 1})"
                    buildList {
                        add(SignInField("Account$label", Eip55.format(grant.account)))
                        add(SignInField("Network$label", network(grant.chainId)))
                        add(SignInField("Allowed calls$label", actions(grant)))
                        add(SignInField("Lasts$label", "Until it is removed from the account: Smart Sessions sets no end"))
                        add(
                            SignInField(
                                "Can be enabled until$label",
                                grant.enableBy?.let(formatTime) ?: "Any time: no date",
                            ),
                        )
                        add(
                            SignInField(
                                "Signs messages for the account$label",
                                if (grant.signsMessages) "Yes, under ${policyNames(grant.messagePolicies)}" else "No",
                            ),
                        )
                        if (grant.claimPolicies.isNotEmpty()) {
                            add(
                                SignInField(
                                    "Claims locked funds$label",
                                    "Lock tag 0x${grant.lockTag}, under ${policyNames(grant.claimPolicies)}",
                                ),
                            )
                        }
                        add(SignInField("Session key$label", sessionKey(grant)))
                        add(SignInField("Checked by$label", "${Eip55.format(grant.emissary)} (its SmartSessionEmissary)"))
                    }
                }
            }
            null -> emptyList()
        }

    private fun moduleMeaning(tx: ModuleTransaction): String {
        val delay = short(tx.modifier)
        val queued = "Signed, Gnosis Pay's relayer (or anyone) can hand it to the Delay module $delay as your " +
            "call: the Delay queues it, and once its cooldown has passed anyone can execute it on the account's Safe."
        val call = tx.call ?: return "A call for the Delay module $delay that Punicus does not read. $queued"
        val allowance = call.allowance
        if (allowance != null && allowance.spending) {
            val per = period(allowance.period)
            val full = allowance.balance == allowance.refill && allowance.maxRefill == allowance.refill
            return "Sets the Gnosis Pay card's spending limit to ${cardAmount(allowance.refill)} $per, in the card's " +
                "currency (EURe or GBPe)" + (if (full) "" else ", with ${cardAmount(allowance.balance)} spendable now " +
                "and at most ${cardAmount(allowance.maxRefill)} held") + ". $queued"
        }
        if (allowance != null) {
            return "Sets a Roles allowance (key 0x${allowance.key.take(8)}…) to ${allowance.refill.toDecimal()} units " +
                "${period(allowance.period)}. $queued"
        }
        val item = eth.serotine.ui.components.safeTxActionItem(
            call.action, null, content.tokens.values.map { eth.serotine.ui.components.DecodingToken(it) }, network(),
        )
        return "The Safe would: ${item.title}. $queued"
    }

    private fun moduleFields(tx: ModuleTransaction): List<SignInField> = buildList {
        add(SignInField("Delay module", Eip55.format(tx.modifier)))
        val call = tx.call
        if (call == null) {
            add(SignInField("Call", "${tx.dataSize} bytes, not read here"))
        } else {
            val allowance = call.allowance
            add(
                SignInField(
                    "Called by the Safe",
                    Eip55.format(call.to) + if (allowance?.spending == true) {
                        " (the account's Roles bouncer, as Gnosis Pay's app sets it up; not checked here)"
                    } else {
                        ""
                    },
                ),
            )
            if (allowance != null) {
                add(SignInField("Allowance", if (allowance.spending) "The card's spending limit (SPENDING_ALLOWANCE)" else "0x${allowance.key}"))
                add(SignInField("Refill", "${amountOf(allowance.refill, allowance.spending)} every ${period(allowance.period).removePrefix("a ").removePrefix("every ")}"))
                add(SignInField("Most it holds", amountOf(allowance.maxRefill, allowance.spending)))
                add(SignInField("Spendable now", amountOf(allowance.balance, allowance.spending)))
                add(SignInField("Counted from", if (allowance.timestamp == 0L) "Now" else formatTime(allowance.timestamp)))
            } else {
                val item = eth.serotine.ui.components.safeTxActionItem(
                    call.action, null, content.tokens.values.map { eth.serotine.ui.components.DecodingToken(it) }, network(),
                )
                add(SignInField("The Safe would", item.title))
                item.addresses.forEach { add(SignInField(it.what, it.address)) }
                if (call.operation == eth.serotine.domain.model.SafeOperation.DELEGATECALL) add(SignInField("Operation", "Delegate call"))
            }
        }
        add(SignInField("Salt", "0x${tx.salt}"))
    }

    /** The network the typed data names, or Gnosis Chain, where Gnosis Pay is. */
    private fun network(): Network = (data.domain["chainId"]?.let(Eip712::unsignedOf)?.toLongOrNull())
        ?.let(Network::ofChainId) ?: Network.GNOSIS

    /** [value] of the card's currency, counted at 18 decimals as EURe and GBPe are, its raw form beside it. */
    private fun cardAmount(value: UInt256): String {
        val (whole, fraction) = value.formatUnits(18).split('.').let { it[0] to it.getOrNull(1) }
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return grouped + (fraction?.let { ".$it" } ?: "")
    }

    private fun amountOf(value: UInt256, card: Boolean): String =
        if (card) "${cardAmount(value)} (18 decimals, as EURe and GBPe count; raw ${value.toDecimal()})"
        else value.toDecimal()

    private fun period(seconds: Long): String = when (seconds) {
        86_400L -> "a day"
        604_800L -> "a week"
        3_600L -> "an hour"
        else -> "every $seconds seconds"
    }

    private fun sessionMeaning(meaning: TypedDataMeaning.SmartSession): String {
        val grants = meaning.grants.grants
        val accounts = grants.map { it.account }.distinct().joinToString(", ") { short(it) }
        val chains = grants.map { network(it.chainId) }.distinct().joinToString(", ")
        val unlimited = grants.any { it.anyCall && it.sudo }
        return "Grants a session key the power to act for $accounts on $chains" +
            (if (unlimited) ": any call, to any contract, with no limit" else ", within the policies below") +
            ", until it is removed" +
            ". Whoever holds the key may then use it without asking you again (Smart Sessions)."
    }

    private fun actions(grant: eth.serotine.domain.model.SmartSessionGrant): String =
        grant.actions.joinToString("\n") { action ->
            val target = if (action.target == SmartSessionGrants.ANY_TARGET) "Any contract" else Eip55.format(action.target)
            val function = if (action.selector in SmartSessionGrants.ANY_SELECTOR) "any function" else "function 0x${action.selector}"
            "$target, $function, under ${policyNames(action.policies)}"
        }.ifEmpty { "None" }

    private fun policyNames(policies: List<Address>): String = if (policies.isEmpty()) {
        "no policy"
    } else {
        policies.joinToString(", ") {
            if (it == SmartSessionGrants.SUDO_POLICY) "SudoPolicy (no limit)" else "policy ${Eip55.format(it)}"
        }
    }

    private fun sessionKey(grant: eth.serotine.domain.model.SmartSessionGrant): String {
        val owners = grant.keyOwners
        return if (owners != null) {
            "${owners.threshold} of ${owners.owners.joinToString(", ") { Eip55.format(it) }} (OwnableValidator)"
        } else {
            "Checked by ${Eip55.format(grant.validator)}, with ${grant.validatorInitSize} bytes of its own data: " +
                "who holds the key is not decoded here"
        }
    }

    /** The domain: who verifies the signature, on which chain. */
    val domainFields: List<SignInField>
        get() = data.types.getValue(Eip712.DOMAIN_TYPE).map { field ->
            val value = data.domain.getValue(field.name)
            when (field.name) {
                "chainId" -> SignInField("Network", network(Eip712.unsignedOf(value)))
                "verifyingContract" -> SignInField("Contract", atom(field.type, value))
                else -> SignInField(field.name.replaceFirstChar(Char::uppercaseChar), atom(field.type, value))
            }
        }

    /**
     * Every field of the message, in the order its type gives them: all of them, since data with more
     * than [MAX_ROWS] is refused, and only then cut.
     */
    val rows: List<TypedRow>
        get() {
            val rows = mutableListOf<TypedRow>()
            fun add(depth: Int, label: String, type: String, value: TypedValue) {
                if (rows.size > MAX_ROWS) return
                val fields = data.types[type]
                when {
                    fields != null -> {
                        rows += TypedRow(depth, label, null)
                        val struct = (value as TypedValue.Struct).fields
                        for (field in fields) add(depth + 1, field.name, field.type, struct.getValue(field.name))
                    }
                    type.endsWith("]") -> {
                        val items = (value as TypedValue.Items).items
                        rows += TypedRow(depth, label, if (items.size == 1) "1 item" else "${items.size} items")
                        val itemType = type.substringBeforeLast('[')
                        items.forEachIndexed { index, item -> add(depth + 1, "[$index]", itemType, item) }
                    }
                    else -> rows += TypedRow(depth, label, atom(type, value))
                }
            }
            for (field in data.types.getValue(data.primaryType)) {
                add(0, field.name, field.type, data.message.getValue(field.name))
            }
            if (rows.size > MAX_ROWS) {
                return rows.take(MAX_ROWS) + TypedRow(0, "…", "and more fields, not shown here")
            }
            return rows
        }

    private fun atom(type: String, value: TypedValue): String = when {
        type == "address" -> Eip55.format(Address.of((value as TypedValue.Text).text))
        type == "bool" -> if ((value as TypedValue.Bool).value) "true" else "false"
        type.startsWith("uint") -> Eip712.unsignedOf(value).toDecimal()
        else -> when (value) {
            // One line per value: a character that draws nothing, or a line break, is spelled out.
            is TypedValue.Text -> ShownText.escaped(value.text)
            is TypedValue.Number -> value.digits
            is TypedValue.Bool -> value.value.toString()
            else -> ""
        }
    }

    private fun tokenOf(address: Address): Token? = content.tokens[address]

    private fun token(address: Address): String =
        tokenOf(address)?.let { "${it.symbol} · ${Eip55.format(address)}" }
            ?: "${Eip55.format(address)} · not in the built-in token list"

    private fun amount(value: UInt256, address: Address, unlimited: UInt256 = UInt256.MAX): String {
        val token = tokenOf(address)
        return when {
            value == unlimited || value == UInt256.MAX -> "an unlimited amount of ${token?.symbol ?: "the token"}"
            token != null -> "${value.formatUnits(token.decimals)} ${token.symbol}"
            else -> "${value.toDecimal()} units of ${short(address)}"
        }
    }

    private fun until(seconds: UInt256): String {
        val long = seconds.toLongOrNull()
        return if (long == null || long > MAX_DATE_SECONDS) "no end" else formatTime(long)
    }

    private fun expiration(seconds: UInt256): String =
        if (seconds.isZero) "for the block it is used in" else "until ${until(seconds)}"

    internal fun network(chainId: UInt256): String {
        val id = chainId.toLongOrNull()
        return id?.let(Network::ofChainId)?.let { "${it.displayName} ($id)" } ?: "Chain ${chainId.toDecimal()}"
    }

    companion object {
        /** The most rows shown of typed data's fields: what a request may have and still be signed. */
        const val MAX_ROWS = MAX_SHOWN_VALUES

        // Year 9999: beyond it a deadline is no date anyone can read, and means no end.
        private const val MAX_DATE_SECONDS = 253_402_300_799L

        private val MAX_UINT160 = UInt256.fromBigEndian(ByteArray(12) + ByteArray(20) { -1 })

        const val APPROVAL_NOTE =
            "Signing moves nothing itself, but the spender may then take it without asking you again."
        const val ORDER_NOTE = "Signing lets CoW Protocol's solvers fill it, without asking you again."

        const val SESSION_ANY_CALL = "The session may call any contract, any function, as the account."
        const val SESSION_SUDO = "Its policy is the SudoPolicy: nothing limits what the session does, its amounts " +
            "included. Whoever holds the session key can move everything the account holds."
        const val SESSION_NEVER_EXPIRES = "Once enabled, the session has no end: it lasts until it is removed from " +
            "the account. The date in the data is only how long it can be enabled."
        const val SESSION_SIGNS_MESSAGES = "The session may sign messages for the account, and a signed permit or " +
            "order can let others take its tokens."
        const val SESSION_GENERIC = "A call no action names may still be allowed by the account's generic policies."

        fun short(address: Address): String = Eip55.format(address).let { it.take(6) + "…" + it.takeLast(4) }
    }
}
