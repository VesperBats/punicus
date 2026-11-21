package eth.serotine.data.history

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** [NoAddressIndexHistory] against the port's contract: a lasting refusal for every call, never an empty page. */
class NoAddressIndexHistoryTest {

    private val history = NoAddressIndexHistory()
    private val address = Address.of("0x" + "11".repeat(20))
    private val entry = AddressIndexEntry(21_000_000, 1, setOf(AddressIndexRole.FROM))

    @Test
    fun itSaysUpFrontThatThisBuildHasNoReader() = runTest {
        assertFalse(history.hasReader())
    }

    @Test
    fun everyPageAndEveryCheckIsRefusedAsNotInThisBuildOnEveryNetwork() = runTest {
        Network.entries.forEach { network ->
            assertEquals(
                AddressIndexReading.Refused(AddressIndexRefusal.NotInThisBuild),
                history.page(network, address, AddressIndexQuery()),
            )
            assertEquals(
                AddressIndexCheck.Refused(AddressIndexRefusal.NotInThisBuild),
                history.verify(network, address, listOf(entry)),
            )
        }
        assertEquals(false, AddressIndexRefusal.NotInThisBuild.retryable, "lasting: asking again changes nothing")
    }

    @Test
    fun aCheckOfNoEntriesIsTheCallersError() = runTest {
        assertFailsWith<IllegalArgumentException> { history.verify(Network.ETHEREUM, address, emptyList()) }
    }

    @Test
    fun noImporterKeyIsPinnedInThisRelease() {
        assertNull(PinnedAddressIndexImporter.KEY, "owner's decision, 2026-11-21: none is pinned yet")
    }
}
