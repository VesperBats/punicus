package eth.serotine.data.settings

import eth.serotine.data.files.VersionedJsonFile
import eth.serotine.data.files.VersionedJsonFile.Decision
import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressIndexChoice
import eth.serotine.domain.repository.AddressIndexSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [AddressIndexSetting] kept in the file at [path] (a [VersionedJsonFile]): the importer key the user turned the
 * experimental addr1 history on for, or none. No file is off: the setting is opt-in (owner ruling, 2026-11-21, #334).
 * A damaged file, a key that is not an address among it, is set aside, which reads as off and says so until another
 * choice is made ([AddressIndexChoice.Chosen.setAside]), and one that cannot be read is
 * [AddressIndexChoice.Unreadable]. It stores a key the user accepted, never one typed in: what may be accepted is the
 * use case's to say (`TurnAddressIndex`, the key pinned in the release).
 */
class FileAddressIndexSetting(
    path: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AddressIndexSetting {

    private val file = VersionedJsonFile(
        path,
        dispatcher,
        what = "addr1 history setting",
        formatVersion = FORMAT_VERSION,
        empty = Saved(chosen = false, importer = null),
        decode = { _, text ->
            val stored = JSON.decodeFromString(StoredChoice.serializer(), text)
            // A key that is not an address is damage, set aside: never read as "off" silently, nor as some key.
            Saved(chosen = true, importer = stored.importer?.let { requireNotNull(Address.parse(it)) })
        },
        encode = { saved ->
            JSON.encodeToString(StoredChoice.serializer(), StoredChoice(FORMAT_VERSION, saved.importer?.hex))
        },
    )

    override fun observe(): Flow<AddressIndexChoice> = file.observe().map { read ->
        when (read) {
            // Said until a choice is made again: then nothing is missing any more.
            is VersionedJsonFile.Read.Loaded ->
                AddressIndexChoice.Chosen(read.value.importer, setAside = read.damagedSetAside && !read.value.chosen)
            is VersionedJsonFile.Read.Unreadable -> AddressIndexChoice.Unreadable
        }
    }

    override suspend fun trust(importer: Address?): Boolean =
        file.change(notSaved = false) { _ -> Decision(true, next = Saved(chosen = true, importer = importer)) }

    /** What the file holds: whether a choice was ever [chosen], and the key it was turned on for, null for off. */
    private data class Saved(val chosen: Boolean, val importer: Address?)

    /** The file's layout. [version] comes first and is always written; [importer] is null while it is off. */
    @Serializable
    private class StoredChoice(val version: Int, val importer: String? = null)

    private companion object {
        const val FORMAT_VERSION = 1
        val JSON = Json
    }
}
