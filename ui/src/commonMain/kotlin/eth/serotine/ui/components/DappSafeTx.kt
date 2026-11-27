package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SafeTxAction
import eth.serotine.domain.model.SafeTxGasTerms
import eth.serotine.domain.model.Token
import eth.serotine.domain.usecase.SafeReading

/**
 * A Safe transaction a dApp asks an owner held here to sign or to execute (docs/walletconnect.md, *A Safe
 * transaction to sign*), display-ready: the Safe ([safeAddress], EIP-55), what is known of it now ([safeLine], from
 * the light client, and [safeWarning] when the address is no Safe it recognises), its [nonce] and [hash] where the
 * request has them, what it does ([action], decoded here as screen 17 decodes one) and its [gasTerms] where it
 * carries any.
 */
data class DappSafeTxSection(
    val safeAddress: String,
    val safeLine: String,
    val safeWarning: String? = null,
    val nonce: String? = null,
    val hash: String? = null,
    val action: SafeTxActionItem,
    val gasTerms: GasTermsItem? = null,
)

/**
 * [action], a call of the Safe at [safe] on [network], as D5 and D6 show it: amounts in the built-in [tokens]'
 * units; [nonce] and [hash] where the request has them (typed data does, `execTransaction` does not); [reading], the
 * Safe as the light client reads it now (null: not read yet), held against [signer], the account asked.
 */
internal fun dappSafeTxSection(
    safe: Address,
    action: SafeTxAction,
    gasTerms: SafeTxGasTerms?,
    network: Network,
    tokens: Collection<Token>,
    nonce: Long?,
    hash: String?,
    reading: SafeReading?,
    signer: Address,
    /** D6's Execute: [signer] sends it, owner or not, and signs nothing for the Safe. */
    executing: Boolean = false,
): DappSafeTxSection {
    val item = safeTxActionItem(action, nonce, tokens.map { DecodingToken(it) }, network)
    val (line, warning) = safeLine(reading, nonce, signer, executing)
    return DappSafeTxSection(
        safeAddress = Eip55.format(safe),
        safeLine = line,
        safeWarning = warning,
        nonce = nonce?.let { "Nonce $it" },
        hash = hash,
        action = item,
        gasTerms = gasTerms?.let { gasTermsItem(it, network) },
    )
}

/** What the light client says of the Safe now, and what to know of it before signing, or null. */
private fun safeLine(reading: SafeReading?, nonce: Long?, signer: Address, executing: Boolean): Pair<String, String?> =
    when (reading) {
    null, SafeReading.Checking -> "Checking the Safe with the light client…" to null
    SafeReading.NotASafe -> "Not a Safe Punicus recognises, as its verified code says." to
        "This address holds no Safe Punicus recognises: what this data means to it is not known here. Sign it " +
        "only if you know the contract."
    is SafeReading.Unavailable -> unavailableText(reading.reason) to null
    is SafeReading.Verified -> {
        val state = reading.state
        val owners = if (state.owners.size == 1) "1 owner" else "${state.owners.size} owners"
        val at = when {
            nonce == null -> "at nonce ${state.nonce}"
            nonce == state.nonce -> "at nonce ${state.nonce}: this is its next transaction"
            nonce < state.nonce -> "at nonce ${state.nonce}: this one's nonce is used, so it can never run"
            else -> "at nonce ${state.nonce}: ${nonce - state.nonce} run before this one"
        }
        val owner = when {
            // Any account may execute: the owners' signatures are in the call.
            executing -> "This account executes it and pays the fee; the owners' signatures are in the call."
            signer in state.owners -> "You sign as one of its owners."
            else -> "The account asked is not one of its owners: its signature does not count."
        }
        "A Safe ${state.version}, verified by the light client: ${state.threshold} of $owners, $at. $owner" to
            "The account asked is not one of this Safe's owners.".takeIf { !executing && signer !in state.owners }
    }
    }

/** Test tag of a dApp's Safe transaction block, for UI tests. */
const val DAPP_SAFE_TX_TAG = "dapp-safe-tx"

/**
 * [section] drawn: the Safe, in full with its blockie and what the light client says of it; the transaction's
 * action card ([SafeTxActionCard]); its nonce, its `safeTxHash` in full, to compare with another signer's screen;
 * and its gas and refund terms, with their warning.
 */
@Composable
fun DappSafeTxBlock(section: DappSafeTxSection, modifier: Modifier = Modifier, onRetrace: () -> Unit = {}) {
    Column(
        modifier.testTag(DAPP_SAFE_TX_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Blockie(section.safeAddress, size = 28.dp)
            Column {
                Text("Safe", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(
                        section.safeAddress,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Text(section.safeLine, style = MaterialTheme.typography.bodySmall)
        section.safeWarning?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        SafeTxActionCard(section.action, onRetrace = onRetrace)
        section.nonce?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        section.hash?.let { hash ->
            Text("Safe transaction hash", style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(
                    hash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        section.gasTerms?.let { terms ->
            Text("Gas and refund terms", style = MaterialTheme.typography.labelMedium)
            terms.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            terms.addresses.forEach { line ->
                Text(
                    "${line.what}: ${line.address}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(GAS_TERMS_WARNING, color = MaterialTheme.colorScheme.error)
        }
    }
}
