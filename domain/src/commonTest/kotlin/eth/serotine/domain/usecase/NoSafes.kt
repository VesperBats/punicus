package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** An [ObserveSafe] for tests that read no Safe: every address's kind is not available. */
internal fun noSafes(accounts: AccountRepository): ObserveSafe = ObserveSafe(
    object : AddressKinds {
        override fun observeKind(network: Network, address: Address): Flow<KindReading> =
            flowOf(KindReading.Unavailable(ReadUnavailable.NO_ENGINE))
    },
    accounts,
)
