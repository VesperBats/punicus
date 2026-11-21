package eth.serotine.domain.repository

import eth.serotine.domain.model.Address
import kotlinx.coroutines.flow.Flow

/**
 * The port for the experimental setting that lets an account's *Activity* also take the addr1 index from Swarm, on
 * the desktop only (owner ruling, 2026-11-21, #334, and the owner's decisions of that day on #335;
 * `.claude/rules/data-sources.md`, *The history may also take the addr1 index from Swarm*). Off by default. It keeps
 * the importer key the user turned it on for, not merely "on": a later release that pins another key finds the
 * setting turned on for a key it no longer pins, and that is off until the user turns it on again
 * (`eth.serotine.domain.usecase.AddressIndexStatus`). The key itself is pinned in the release and never typed by the
 * user; this port only remembers which pinned key the user accepted. :data keeps it in a file, so it survives a
 * restart. Never throws.
 */
interface AddressIndexSetting {
    /** The choice, re-emitted on each change, or that it cannot be read. Never throws. */
    fun observe(): Flow<AddressIndexChoice>

    /**
     * Turns the setting on for [importer], the key pinned in the release, or off (null). False when not saved, and
     * nothing changed.
     */
    suspend fun trust(importer: Address?): Boolean
}

/** What [AddressIndexSetting.observe] emits. */
sealed interface AddressIndexChoice {
    /**
     * The key the user turned the setting on for ([importer]), null while it is off, which it is until the user
     * turns it on; [setAside] when no choice is left because the stored one was damaged and set aside.
     */
    data class Chosen(val importer: Address?, val setAside: Boolean = false) : AddressIndexChoice

    /** A choice is stored but cannot be read: taken as off, and nothing can be chosen until it can. */
    data object Unreadable : AddressIndexChoice
}
