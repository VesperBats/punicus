package eth.serotine.data.settings

import eth.serotine.domain.model.Address
import eth.serotine.domain.repository.AddressIndexChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileAddressIndexSettingTest {

    private val directory = Path(SystemTemporaryDirectory, "serotine-addr1-${Random.nextLong().toULong()}")
    private val file = Path(directory, "address-index.json")

    /** A made-up importer key, a test fixture: no release pins it. */
    private val fixtureImporter = Address.of("0x" + "1e".repeat(20))

    private fun TestScope.setting() = FileAddressIndexSetting(file.toString(), StandardTestDispatcher(testScheduler))

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun write(text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
    }

    @Test
    fun offUntilTurnedOnAndTheKeyAcceptedSurvivesARestart() = runTest {
        assertEquals(AddressIndexChoice.Chosen(importer = null), setting().observe().first())

        assertTrue(setting().trust(fixtureImporter))
        assertEquals(AddressIndexChoice.Chosen(fixtureImporter), setting().observe().first())

        assertTrue(setting().trust(null))
        assertEquals(AddressIndexChoice.Chosen(importer = null), setting().observe().first())
    }

    @Test
    fun aNewerFormatIsUnreadableAndCannotBeOverwritten() = runTest {
        write("""{"version":2,"importer":null}""")
        val setting = setting()

        assertEquals(AddressIndexChoice.Unreadable, setting.observe().first())
        assertFalse(setting.trust(null))
    }

    @Test
    fun aKeyThatIsNoAddressIsSetAsideAsOffAndSaidUntilAnotherChoiceIsMade() = runTest {
        write("""{"version":1,"importer":"0x1234"}""")
        val setting = setting()

        assertEquals(AddressIndexChoice.Chosen(importer = null, setAside = true), setting.observe().first())
        assertTrue(setting.trust(null))
        assertEquals(AddressIndexChoice.Chosen(importer = null), setting.observe().first())
    }
}
