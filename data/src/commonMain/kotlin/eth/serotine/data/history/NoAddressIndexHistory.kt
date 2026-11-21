package eth.serotine.data.history

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AddressIndexEntry
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressIndexCheck
import eth.serotine.domain.repository.AddressIndexHistory
import eth.serotine.domain.repository.AddressIndexQuery
import eth.serotine.domain.repository.AddressIndexReading
import eth.serotine.domain.repository.AddressIndexRefusal

/**
 * The [AddressIndexHistory] of a build whose engine has no Swarm reader (issue #334): every call is refused with the
 * lasting [AddressIndexRefusal.NotInThisBuild], never an empty page, and [hasReader] says so up front. It is what the
 * desktop is wired to until a published Myotis release carries the address history's API (Myotis #908; the engine
 * adapter is part 4 of the plan), so the experimental setting is greyed out there, saying why
 * (`.claude/rules/data-sources.md`, *The history may also take the addr1 index from Swarm*). It reads nothing and
 * contacts nobody.
 */
class NoAddressIndexHistory : AddressIndexHistory {

    override suspend fun hasReader(): Boolean = false

    override suspend fun page(network: Network, address: Address, query: AddressIndexQuery): AddressIndexReading =
        AddressIndexReading.Refused(AddressIndexRefusal.NotInThisBuild)

    override suspend fun verify(
        network: Network,
        address: Address,
        entries: List<AddressIndexEntry>,
    ): AddressIndexCheck {
        // The port's contract: more than it takes, or none, is the caller's error, whatever the build.
        require(entries.size in 1..AddressIndexHistory.MAX_CHECKED) {
            "1 to ${AddressIndexHistory.MAX_CHECKED} entries"
        }
        return AddressIndexCheck.Refused(AddressIndexRefusal.NotInThisBuild)
    }
}

/**
 * The importer key of the addr1 index pinned in this release: the one key whose index the list may rest on, named by
 * the experimental setting (owner's decision, 2026-11-21, on #335: pinned in the release, never typed by the user).
 * Which key is pinned is the owner's choice, named in the PR that pins it. **None is pinned yet** (owner's decision,
 * 2026-11-21), so it is null, and the setting is greyed out saying so.
 */
object PinnedAddressIndexImporter {
    /** The key pinned in this release, or null: none is. */
    val KEY: Address? = null
}
