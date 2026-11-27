package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignFormatter
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSigningBinding
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.ClearSigningDescriptor
import eth.serotine.domain.model.ClearSigningLookups
import eth.serotine.domain.model.ClearSigningToken
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NotCoveredReason
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.keccak256
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Says in a built-in descriptor's words what a dApp's call or typed-data message does (docs/clear-signing.md,
 * A3, A4), or why it says nothing. In order, failing closed at each step:
 *
 * 1. A call or a message a hand-written decoder claims (a payment, ERC-20 `transfer` and `approve`, the
 *    structs of [TypedDataMeaning]) is that decoder's: [NotCoveredReason.ClaimedByAppDecoder].
 * 2. The catalog has a deployment at the call's `to`, or the domain's `verifyingContract`, on its network.
 * 3. Its code, and for a proxy its pointer and the implementation's code, read verified at a fresh head, are
 *    the pinned ones ([ClearSigningBinding]): otherwise [NotCoveredReason.ContractChanged].
 * 4. One of its descriptors has a format for the call or the message, and [ClearSignFormatter] renders it.
 *
 * A nested call goes through the same steps for its callee. Tokens are named by the built-in list alone
 * ([tokens]); an address's kind, which an `addressName` holds against its expected types, is the verified one
 * ([kinds]), each read once and all within one deadline; a kind that cannot be read in time is flagged as not
 * verified. An address is named by the built-in tokens and the catalog's deployments.
 */
class DescribeClearSigning(
    private val catalog: ClearSigningCatalog,
    private val codes: ContractCodes,
    private val tokens: TokenCatalog,
    private val kinds: AddressKinds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** What [data] sent by [from] to [to] with [value] on [network] does, as a built-in descriptor says. */
    suspend fun call(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): ClearSignResult {
        if (data.isEmpty()) return ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder)
        val bindings = Bindings()
        return describeCall(network, from, to, value, data, depth = 0, lookups = lookups(network, bindings), bindings = bindings)
    }

    /** What [typed], signed by [signer] on [network], lets someone do, as a built-in descriptor says. */
    suspend fun typedData(network: Network, signer: Address, typed: TypedData): ClearSignResult {
        if (Eip712.encode(typed) == null) return ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
        // By type, as calls are by selector: a type the decoder knows is its, decoded by it or not.
        if (Eip712.encodeType(typed.types, typed.primaryType) in TypedDataMeaning.CLAIMED_TYPES) {
            return ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder)
        }
        val verifier = (typed.domain["verifyingContract"] as? eth.serotine.domain.model.TypedValue.Text)?.text
            ?.let(Address::parse) ?: return ClearSignResult.NotCovered(NotCoveredReason.DomainWithoutVerifyingContract)
        // As for a call: the format first, from the built-in catalog, so no code is read for a type no descriptor covers.
        val pinned = catalog.deployment(network, verifier) ?: return ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor)
        val typeHash = "0x" + keccak256(Eip712.encodeType(typed.types, typed.primaryType).encodeToByteArray()).toHex()
        val descriptor = pinned.descriptors.firstOrNull {
            it.kind == ClearSigningDescriptor.Kind.EIP712 && it.formatForTypeHash(typeHash) != null
        } ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(null))
        Bindings().bound(network, verifier) { return it }
        return ClearSignFormatter.formatTypedData(descriptor, network, signer, typed, lookups(network))
    }

    private suspend fun describeCall(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256?,
        data: ByteArray,
        depth: Int,
        lookups: ClearSigningLookups,
        bindings: Bindings,
    ): ClearSignResult {
        if (data.size < SELECTOR) return ClearSignResult.NotCovered(NotCoveredReason.TooShort)
        val selector = "0x" + data.copyOf(SELECTOR).toHex()
        // By selector, at every depth: what a hand-written decoder claims stands, "not decoded" included.
        if (selector in CLAIMED_SELECTORS) return ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder)
        val pinned = catalog.deployment(network, to) ?: return ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor)
        // The format first, from the built-in catalog: no code is read for a call no descriptor covers.
        val descriptor = pinned.descriptors.firstOrNull {
            it.kind == ClearSigningDescriptor.Kind.CALLDATA && it.formatForSelector(selector) != null
        } ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(selector))
        val deployment = bindings.bound(network, to) { return it }
        return ClearSignFormatter.formatCall(
            descriptor, network, from, to, value, data, lookups, depth, deployment.delegatesToItself,
        )
    }

    /**
     * The binding checks of one description, each address's read once however often it is called (a nested
     * call to the same contract is bound by the same reads), all within [BINDING_TIMEOUT_MS].
     */
    private inner class Bindings {
        private val outcomes = mutableMapOf<Pair<Network, Address>, Any>()
        private var started: TimeMark? = null

        /** The deployment at [address] once its binding holds; otherwise [otherwise] is given why not. */
        suspend inline fun bound(
            network: Network,
            address: Address,
            otherwise: (ClearSignResult.NotCovered) -> Nothing,
        ): ClearSigningDeployment {
            val outcome = outcomes.getOrPut(network to address) { check(network, address) }
            if (outcome is ClearSignResult.NotCovered) otherwise(outcome)
            return outcome as ClearSigningDeployment
        }

        suspend fun check(network: Network, address: Address): Any {
            val deployment = catalog.deployment(network, address)
                ?: return ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor)
            val mark = started ?: timeSource.markNow().also { started = it }
            val left = BINDING_TIMEOUT_MS - mark.elapsedNow().inWholeMilliseconds
            val reading = if (left <= 0) null else withTimeoutOrNull(left) { codes.read(network, address, deployment.codePin) }
            val reads = when (reading) {
                is CodeReading.Read -> reading.reads
                is CodeReading.Unavailable, null -> return ClearSignResult.NotCovered(NotCoveredReason.CodeNotProven)
            }
            return ClearSigningBinding.check(deployment, reads)?.let { ClearSignResult.NotCovered(it) } ?: deployment
        }
    }

    private suspend fun lookups(network: Network, bindings: Bindings = Bindings()): ClearSigningLookups {
        val builtIn = tokens.observeTokens(network).first()
        val listed = builtIn.associate { it.address to ClearSigningToken(it.symbol, it.decimals) }
        val names = builtIn.associate { it.address to it.name }
        // Each address's kind is read once per description, and all of them within one deadline.
        val known = mutableMapOf<Address, String?>()
        var started: TimeMark? = null
        return object : ClearSigningLookups {
            override fun token(network: Network, token: Address) = listed[token]

            override fun addressName(network: Network, address: Address): String? =
                names[address] ?: catalog.deployment(network, address)?.name

            override suspend fun addressKind(network: Network, address: Address): String? {
                if (address in known) return known[address]
                // The deadline starts at the first kind asked, after the binding's reads.
                val mark = started ?: timeSource.markNow().also { started = it }
                val left = KIND_TIMEOUT_MS - mark.elapsedNow().inWholeMilliseconds
                val kind = if (left <= 0) null else withTimeoutOrNull(left) {
                    val reading = kinds.observeKind(network, address).filterNot { it is KindReading.Syncing }.firstOrNull()
                    // A Safe is a contract. ERC-7730's `wallet` is one of the user's own accounts, which a later
                    // part brings with the wallet's accounts; it is no kind of code.
                    when ((reading as? KindReading.Verified)?.kind) {
                        AddressKind.Eoa, is AddressKind.DelegatedEoa -> "eoa"
                        is AddressKind.Safe, AddressKind.Contract -> "contract"
                        null -> null
                    }
                }
                known[address] = kind
                return kind
            }

            // No collection is built in for clear signing yet, so each is shown by its address (A3, *NFTs*).
            override fun builtInCollectionName(network: Network, collection: Address): String? = null

            override suspend fun nested(
                network: Network,
                from: Address,
                callee: Address,
                value: UInt256?,
                data: ByteArray,
                depth: Int,
            ): ClearSignResult = describeCall(network, from, callee, value, data, depth, this, bindings)
        }
    }

    private companion object {
        const val SELECTOR = 4
        const val KIND_TIMEOUT_MS = 15_000L
        const val BINDING_TIMEOUT_MS = 30_000L

        /** ERC-20 `transfer` and `approve`, which [Erc20] decodes: never described by a descriptor. */
        val CLAIMED_SELECTORS = setOf("0xa9059cbb", "0x095ea7b3") + eth.serotine.domain.model.TokenCalls.SELECTORS
    }
}
