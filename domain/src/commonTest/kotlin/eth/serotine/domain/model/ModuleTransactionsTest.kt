package eth.serotine.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Gnosis Pay change signed for its relayer: a Delay's `ModuleTx` (docs/gnosis-pay.md). */
class ModuleTransactionsTest {

    private val delay = Address.of("0x22bdfd8d6238f034a0937fa2eec3ddbd4b039fe3")
    private val bouncer = Address.of("0x203d2e0d726978cbe9e82e6fb277220bf9cba5c2")
    private val eight = UInt256.of("8000000000000000000000")

    /** account-kit's `createInnerTransaction`: the card's limit set to [refill] a [period]. */
    private fun setAllowance(refill: UInt256, period: Long, timestamp: Long = 0) =
        AbiWords.selector("setAllowance(bytes32,uint128,uint128,uint128,uint64,uint64)") +
            RolesAllowance.SPENDING_ALLOWANCE_KEY.hexToBytes() + refill.toBytes32() + refill.toBytes32() +
            refill.toBytes32() + AbiWords.word(period) + AbiWords.word(timestamp)

    private fun queue(to: Address, data: ByteArray, operation: SafeOperation = SafeOperation.CALL) =
        AbiWords.selector("execTransactionFromModule(address,uint256,bytes,uint8)") + AbiWords.word(to) +
            UInt256.ZERO.toBytes32() + AbiWords.word(128) + AbiWords.word(operation.code.toLong()) + AbiWords.dynamic(data)

    private fun typed(data: ByteArray) = TypedData(
        types = mapOf(
            Eip712.DOMAIN_TYPE to listOf(TypedField("chainId", "uint256"), TypedField("verifyingContract", "address")),
            "ModuleTx" to listOf(TypedField("data", "bytes"), TypedField("salt", "bytes32")),
        ),
        primaryType = "ModuleTx",
        domain = mapOf("chainId" to TypedValue.Number("100"), "verifyingContract" to TypedValue.Text(delay.hex)),
        message = mapOf(
            "data" to TypedValue.Text("0x" + data.toHex()),
            "salt" to TypedValue.Text("0x" + "00".repeat(28) + "1a21ea68"),
        ),
    )

    @Test
    fun gnosisPaysDailyLimitIsRead() {
        val meaning = assertIs<TypedDataMeaning.ModuleTx>(
            TypedDataMeaning.of(Eip712.encode(typed(queue(bouncer, setAllowance(eight, 86_400))))!!),
        )
        assertEquals(delay, meaning.tx.modifier)
        val call = meaning.tx.call!!
        assertEquals(bouncer, call.to)
        val allowance = call.allowance!!
        assertTrue(allowance.spending)
        assertEquals(eight, allowance.refill)
        assertEquals(86_400L, allowance.period)
        assertEquals("fe687fc128d1915040376d20ccb1bf40d838ddd82bf9b0ba3da683cc2a251623", RolesAllowance.SPENDING_ALLOWANCE_KEY)
    }

    @Test
    fun aModuleEnabledThroughTheDelayIsReadAsTheSafesSettingsChange() {
        val safe = Address.of("0x" + "5a".repeat(20))
        val enable = SafeSettingsCalls.ENABLE_MODULE + AbiWords.word(bouncer)
        val call = ModuleTransaction.of(Eip712.encode(typed(queue(safe, enable)))!!)!!.call!!
        assertEquals(SafeTxAction.SettingsChange(SafeSettingsChange.ModuleEnabled(bouncer)), call.action)
        assertNull(call.allowance)
        // A byte too many is no queued call.
        assertNull(ModuleTransaction.of(Eip712.encode(typed(queue(safe, enable) + byteArrayOf(0)))!!)!!.call)
    }
}
