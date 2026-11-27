package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ModuleTransaction
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeOperation
import eth.serotine.domain.repository.DelayGeneration
import eth.serotine.domain.repository.DelayModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether [tx], asked of [signer] on [network], is the one relayed Delay call Punicus signs (owner's request,
 * 2026-11-27; docs/gnosis-pay.md, *A change signed for Gnosis Pay's app*): the Gnosis Pay card's spending limit,
 * account-kit's `setAllowance` under `SPENDING_ALLOWANCE`, on Gnosis Chain, a plain call (no value, CALL) of neither
 * the Delay nor, once known, the Safe. Null when it is; otherwise why not. Pure: what the chain says of the Delay is
 * [GnosisPayDelayCheck]'s.
 */
fun ModuleTransaction.cardLimitRefusal(network: Network, safe: Address? = null): ModuleTxRefusal? {
    if (network != Network.GNOSIS) return ModuleTxRefusal.OtherNetwork
    val call = call ?: return ModuleTxRefusal.NotCardLimit
    val allowance = call.allowance ?: return ModuleTxRefusal.NotCardLimit
    if (!allowance.spending || call.operation != SafeOperation.CALL || !call.value.isZero) return ModuleTxRefusal.NotCardLimit
    if (call.to == modifier || call.to == safe) return ModuleTxRefusal.NotCardLimit
    return null
}

/** Why a relayed Delay call is not signed here. */
sealed interface ModuleTxRefusal {
    /** Not on Gnosis Chain, where Gnosis Pay alone runs. */
    data object OtherNetwork : ModuleTxRefusal

    /**
     * Anything but the card's spending limit: through the relayed path nothing else is signed, as nothing else but
     * plain transfers and member changes is queued here (CLAUDE.md, *Trust*).
     */
    data object NotCardLimit : ModuleTxRefusal

    /** The contract it names is no Delay of a Gnosis Pay account in this wallet, as verified now. */
    data object NotAccountsDelay : ModuleTxRefusal

    /** It is such a Delay, of the retired mastercopy: nothing is signed through it (owner's decisions, 2026-11-15). */
    data object RetiredDelay : ModuleTxRefusal

    /** The account asked is not one of the Delay's members, whose signature alone it takes. */
    data object NotMember : ModuleTxRefusal

    /** The Delay could not be verified now, for [reason]. */
    data class Unverified(val reason: eth.serotine.domain.repository.ReadUnavailable?) : ModuleTxRefusal
}

/** What [GnosisPayDelayCheck] found: the Gnosis Pay account [safe] whose Delay it is, verified, or why not. */
sealed interface GnosisPayDelay {
    data class Verified(val safe: AccountId, val delay: DelayModule) : GnosisPayDelay
    data class Refused(val refusal: ModuleTxRefusal) : GnosisPayDelay
}

/**
 * Finds the Gnosis Pay account in the wallet whose Delay is [modifier], as the light client proves it
 * ([ObserveGnosisPays]: the Delay's code a proxy of a pinned mastercopy, its avatar, target and owner the Safe, its
 * members), and holds [signer] to its members. A Delay of the retired mastercopy is refused, and so is one this
 * wallet holds no Gnosis Pay account of: the data names no Safe, so the account must be the wallet's.
 */
class GnosisPayDelayCheck(
    /** The wallet's Gnosis Pay accounts as the light client reads them: [ObserveGnosisPays]. */
    private val gnosisPays: () -> Flow<Map<AccountId, GnosisPayReading>>,
) {

    /** The verdict, followed as the readings change. */
    operator fun invoke(modifier: Address, signer: Address): Flow<GnosisPayDelay?> =
        gnosisPays().map { readings -> verdict(readings, modifier, signer) }

    /** The verdict once every Safe's reading answered, or after [budget]; unverified then. */
    suspend fun check(modifier: Address, signer: Address, budget: Duration = 45.seconds): GnosisPayDelay =
        withTimeoutOrNull(budget) { invoke(modifier, signer).first { it != null } }
            ?: GnosisPayDelay.Refused(ModuleTxRefusal.Unverified(null))

    /** The verdict of [readings]; null while one that could be the Delay's is still being read. */
    private fun verdict(readings: Map<AccountId, GnosisPayReading>, modifier: Address, signer: Address): GnosisPayDelay? {
        val found = readings.entries.firstOrNull { (_, reading) ->
            (reading as? GnosisPayReading.Found)?.delay?.address == modifier
        }
        if (found == null) {
            if (readings.values.any { it is GnosisPayReading.Checking }) return null
            val unavailable = readings.values.filterIsInstance<GnosisPayReading.Unavailable>().firstOrNull()
            return GnosisPayDelay.Refused(
                unavailable?.let { ModuleTxRefusal.Unverified(it.reason) } ?: ModuleTxRefusal.NotAccountsDelay,
            )
        }
        val reading = found.value as GnosisPayReading.Found
        val delay = reading.delay
        return when {
            reading.held -> GnosisPayDelay.Refused(ModuleTxRefusal.Unverified(null))
            delay.generation == DelayGeneration.RETIRED -> GnosisPayDelay.Refused(ModuleTxRefusal.RetiredDelay)
            signer !in delay.members -> GnosisPayDelay.Refused(ModuleTxRefusal.NotMember)
            else -> GnosisPayDelay.Verified(found.key, delay)
        }
    }
}
