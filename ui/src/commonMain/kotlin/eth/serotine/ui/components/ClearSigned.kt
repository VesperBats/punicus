package eth.serotine.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSignedField
import eth.serotine.domain.model.NotCoveredReason

/** A call or a message described by a built-in descriptor, display-ready (docs/clear-signing.md, A5). */
data class ClearSignedSection(
    /** What it does, in the descriptor's words, its placeholders filled in where they could be. */
    val intent: String,
    /** Whose words these are, as the descriptor states it, and what that does and does not prove. */
    val describedBy: String,
    /** What it shows, in the descriptor's order; a nested call's rows (its other parameters among them) indented. */
    val rows: List<ClearSignedRow>,
    /** Every parameter no row shows, raw: a descriptor orders and labels, it hides nothing. */
    val otherParameters: List<ClearSignedParameter>,
    /** Whether a call inside it is not decoded: its call data is then only partly described. */
    val partly: Boolean = false,
)

/**
 * One row of a [ClearSignedSection] at its nesting [level]: the descriptor's [label] and [value], the decoded
 * [raw] value where it differs from what is shown, and a [note] where something about it needs saying.
 */
data class ClearSignedRow(val level: Int, val label: String, val value: String, val raw: String?, val note: String?)

/** A parameter no row of a [ClearSignedSection] shows: where it is in the call or the message, and its raw value. */
data class ClearSignedParameter(val location: String, val raw: String)

/** What the screens say of a clear-signed call or message, and how a [ClearSignResult] becomes a [ClearSignedSection]. */
object ClearSignedText {
    const val PARTLY_NOT_DECODED = "Part of this is not decoded: a call inside it is shown raw, and Punicus " +
        "cannot tell what that part does. Go ahead only if you trust this dApp."
    const val NESTED_NOT_DECODED = "Not decoded: Punicus cannot tell what this part does."
    const val CONTRACT_CHANGED = "This contract changed after this release was built; its description no " +
        "longer applies, so this is not decoded."
    const val CODE_NOT_CHECKED = "This contract's code could not be checked at a verified head right now, so its " +
        "description is not used: this is not decoded."
    const val NOT_AS_DESCRIBED = "This does not match its contract's description exactly, so it is not " +
        "described: it is not decoded."
    const val DESCRIBED_BY = "as the descriptor states (ERC-7730 registry, reviewed for this release). Its " +
        "words are its author's; the values are decoded here from what you sign."

    /** [DESCRIBED_BY] for a transaction that already ran (docs/clear-signing.md, *Past transactions*). */
    const val DESCRIBED_BY_PAST = "as the descriptor states (ERC-7730 registry, reviewed for this release). Its " +
        "words are its author's; the values are decoded here from the transaction's call data."

    /** Why a descriptor does not describe a call or a message, where that says more than "not decoded"; null otherwise. */
    fun notDescribedLine(reason: NotCoveredReason): String? = when (reason) {
        NotCoveredReason.ContractChanged -> CONTRACT_CHANGED
        NotCoveredReason.CodeNotProven -> CODE_NOT_CHECKED
        is NotCoveredReason.ValueOutsideDescriptor, is NotCoveredReason.UnknownEnumValue, NotCoveredReason.NotDecodable,
        is NotCoveredReason.PathNotFound, is NotCoveredReason.UnequalArrays, NotCoveredReason.TooDeep,
        NotCoveredReason.DomainWithoutChainId, NotCoveredReason.OtherChain,
        -> NOT_AS_DESCRIBED
        // No contract to bind to is nothing more to say than "not decoded", as no descriptor is.
        NotCoveredReason.NoDescriptor, is NotCoveredReason.NoFormat, NotCoveredReason.ClaimedByAppDecoder,
        NotCoveredReason.TooShort, NotCoveredReason.DomainWithoutVerifyingContract,
        -> null
    }

    /** Whether a call inside [result], at any depth, is not decoded. */
    fun partlyNotDecoded(result: ClearSignResult.ClearSigned): Boolean = result.fields.any { field ->
        field is ClearSignedField.Nested && when (val nested = field.result) {
            is ClearSignResult.NotCovered -> true
            is ClearSignResult.ClearSigned -> partlyNotDecoded(nested)
        }
    }

    /** [result] as the screen shows it: each value with its raw form where that differs, nested calls indented. */
    fun describedSection(result: ClearSignResult.ClearSigned, past: Boolean = false): ClearSignedSection = ClearSignedSection(
        intent = result.interpolatedIntent ?: result.intent,
        describedBy = "Described by ${result.owner}, ${if (past) DESCRIBED_BY_PAST else DESCRIBED_BY}",
        rows = rows(result, level = 0),
        otherParameters = result.otherParameters.map { ClearSignedParameter(it.location, it.raw) },
        partly = partlyNotDecoded(result),
    )

    private fun rows(result: ClearSignResult.ClearSigned, level: Int): List<ClearSignedRow> = result.fields.flatMap { field ->
        when (field) {
            is ClearSignedField.Value -> listOf(
                ClearSignedRow(
                    level = level,
                    label = listOfNotNull(field.group, field.label).joinToString(" · "),
                    value = field.shown,
                    raw = field.raw.takeIf { it != field.shown },
                    note = field.unlisted?.let { "Not a built-in one: shown by its address, never by the name it gives itself." }
                        ?: field.addressType?.let {
                            "The description expects ${it.expected.sorted().joinToString(" or ")}; this address is ${it.actual}."
                        },
                ),
            )
            is ClearSignedField.Nested -> {
                val nested = field.result
                val head = ClearSignedRow(
                    level = level,
                    label = listOfNotNull(field.group, field.label).joinToString(" · "),
                    value = when (nested) {
                        is ClearSignResult.ClearSigned -> "${nested.interpolatedIntent ?: nested.intent}, described by ${nested.owner}"
                        is ClearSignResult.NotCovered -> "A call to ${Eip55.format(field.callee)}, not decoded"
                    },
                    raw = field.data,
                    note = listOfNotNull(
                        "Grants an approval.".takeIf { field.grantsApproval },
                        (nested as? ClearSignResult.NotCovered)?.reason?.let { notDescribedLine(it) ?: NESTED_NOT_DECODED },
                    ).joinToString(" ").ifEmpty { null },
                )
                // A nested call hides nothing either: its own other parameters, raw, under it.
                val inner = (nested as? ClearSignResult.ClearSigned)?.let { described ->
                    rows(described, level + 1) + described.otherParameters.map {
                        ClearSignedRow(level + 1, "Other parameter · ${it.location}", it.raw, raw = null, note = null)
                    }
                } ?: emptyList()
                listOf(head) + inner
            }
        }
    }
}

/**
 * A call or a message in a built-in descriptor's words (docs/clear-signing.md, A5): its intent, whose words they
 * are, each value with its raw form where that differs, nested calls indented, and every other parameter, raw.
 */
@Composable
fun ClearSignedDescription(
    section: ClearSignedSection,
    /**
     * Whether its values can be selected: not where a third party chose them, an address among them, for a
     * transaction the account did not send (address poisoning protection, 2026-11-18, #324).
     */
    selectable: Boolean = true,
) {
    @Composable
    fun Selectable(content: @Composable () -> Unit) = if (selectable) SelectionContainer { content() } else content()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(section.intent, style = MaterialTheme.typography.titleMedium)
        Text(
            section.describedBy,
            Modifier.padding(top = 2.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    section.rows.forEach { row ->
        Column(Modifier.padding(start = 16.dp + 16.dp * row.level, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Selectable { Text(row.value) }
            row.raw?.let {
                Selectable {
                    Text(
                        "Raw: $it",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (section.otherParameters.isNotEmpty()) {
        Text(
            "Other parameters, which the description does not show",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        section.otherParameters.forEach { parameter ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(parameter.location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Selectable {
                    Text(parameter.raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
