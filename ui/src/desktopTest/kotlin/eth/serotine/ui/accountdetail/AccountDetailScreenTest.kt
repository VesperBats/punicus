package eth.serotine.ui.accountdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import eth.serotine.domain.model.AccountKind
import eth.serotine.domain.model.AddressIndexRole
import eth.serotine.ui.activity.ACTIVITY_INDEX_FOOTER_TAG
import eth.serotine.ui.activity.ACTIVITY_ROW_TAG
import eth.serotine.ui.activity.ActivityKey
import eth.serotine.ui.activity.ActivityOrder
import eth.serotine.ui.activity.ActivityRow
import eth.serotine.ui.activity.ActivityState
import eth.serotine.ui.activity.ActivityUiState
import eth.serotine.ui.activity.IndexEntryKey
import eth.serotine.ui.activity.IndexFooter
import eth.serotine.ui.activity.IndexSection
import eth.serotine.ui.activity.ordered
import eth.serotine.ui.components.Control
import eth.serotine.ui.components.GHOST_ROWS_UNKNOWN
import eth.serotine.ui.components.GHOST_ROW_TAG
import eth.serotine.ui.components.LIGHT_CLIENT_STRIP_TAG
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.UNAVAILABLE_REASON_TAG
import eth.serotine.ui.components.catchingUpStripSample
import eth.serotine.ui.components.verifiedStripSample
import eth.serotine.ui.perapp.PER_APP_ROW_TAG
import eth.serotine.ui.perapp.PerAppRow
import eth.serotine.ui.theme.SerotineTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Renders the stateless [AccountDetailScreen] headless for its states. */
class AccountDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val ready = AccountDetailUiState.Ready(
        label = "Daily",
        address = "0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2",
        network = NetworkLabel(11_155_111, "Sepolia", testnet = true),
        kind = AccountKind.EOA,
        control = Control.WATCH_ONLY,
        total = "$3,000.00",
        totalNote = "Testnet prices · 1 asset without a price",
        pricesNote = "Prices: Chainlink feeds, read through the light client, testnet prices",
    )
    private val events = mutableListOf<String>()

    private fun show(
        state: AccountDetailUiState,
        lightClient: LightClientItem = verifiedStripSample,
    ) = rule.setContent { Screen(state, lightClient) }

    @Composable
    private fun Screen(state: AccountDetailUiState, lightClient: LightClientItem) {
        SerotineTheme {
            AccountDetailScreen(
                state,
                chrome = AccountChrome.Strip(lightClient),
                onBack = { events += "back" },
                onSend = { events += "send $it" },
                onSwap = { events += "swap $it" },
                onReceive = { events += "receive" },
                onCopy = { events += "copy $it" },
                onRename = { events += "rename" },
                onRemove = { events += "remove" },
                onHideToken = { contract, symbol -> events += "hide $symbol $contract" },
                onMessageShown = { events += "message shown" },
                onSort = { events += "sort $it" },
                onSegment = { events += "segment $it" },
                onOpenSafeTx = { events += "open $it" },
                onImportRequest = { events += "import" },
                onOpenActivity = { events += "open activity $it" },
                onTurnOnNetwork = { events += "turn on" },
                onSafeSettings = { events += "safe settings" },
                onGnosisPay = { events += "gnosis pay" },
                onToggleUnlistedNfts = { events += "toggle unlisted nfts" },
                onMoreActivity = { events += "more activity" },
                perAppActions = PerAppActions(
                    onOpen = { chain, address -> events += "open per-app $chain $address" },
                    onRestore = { phrase -> events += "restore per-app $phrase" },
                    onRemoveAlias = { site -> events += "remove alias $site" },
                ),
            )
        }
    }

    @Test
    fun aPerAppAddressShowsItsMarkAndAPhrasesAccountItsPerAppTab() {
        val row = PerAppRow(
            site = "uniswap.org",
            address = "0x9fA6c0b2E14d7A35c8e01B2f6D3a9c47b1e08D52",
            networks = "Ethereum",
            phrase = "Phrase of Daily",
            path = "m/44'/60'/1'/2'/3'",
            chainId = 1,
            hex = "0x9fa6c0b2e14d7a35c8e01b2f6d3a9c47b1e08d52",
        )
        show(
            ready.copy(
                perAppMark = "Per-app address for uniswap.org · m/44'/60'/1'/2'/3'",
                perAppAliases = listOf("uniswap.app"),
                segment = DetailSegment.PER_APP,
                perApp = PerAppTab("0xphrase", listOf(row), empty = null),
            ),
        )
        rule.onNodeWithTag(ACCOUNT_PER_APP_MARK_TAG).assertTextEquals(
            "Per-app address for uniswap.org · m/44'/60'/1'/2'/3'",
        )
        rule.onNodeWithText("Also seen by uniswap.app, linked by you").assertExists()
        rule.onNodeWithTag(ACCOUNT_PER_APP_ALIAS_REMOVE_TAG + "uniswap.app").performClick()
        assertEquals("remove alias uniswap.app", events.last())
        rule.onNodeWithText("Per-app").assertExists()
        rule.onNodeWithTag(PER_APP_ROW_TAG + row.hex).performScrollTo().performClick()
        rule.onNodeWithTag(ACCOUNT_PER_APP_RESTORE_TAG).performScrollTo().performClick()
        assertEquals(listOf("open per-app 1 ${row.hex}", "restore per-app 0xphrase"), events.takeLast(2))
    }

    @Test
    fun theNftsAreBelowTheAssetsTheUnlistedOnesCollapsedAndMarked() {
        val nfts = NftSection(
            shown = listOf(NftRow("ENS", "#982531…4107", null, "0x57f1887a8BF19b14fC0dF6Fd9B2acc9Af147eA85")),
            unlisted = listOf(NftRow("Free Mint Pass", "#7", "×3", "0x" + "a7".repeat(20))),
            note = "Received since block 9,000",
        )
        var state by mutableStateOf(ready.copy(nfts = nfts))
        rule.setContent {
            SerotineTheme {
                AccountDetailScreen(
                    state,
                    chrome = AccountChrome.Strip(verifiedStripSample),
                    onBack = {},
                    onSend = {},
                    onSwap = {},
                    onReceive = {},
                    onCopy = {},
                    onRename = {},
                    onRemove = {},
                    onHideToken = { _, _ -> },
                    onMessageShown = {},
                    onSort = {},
                    onSegment = {},
                    onOpenSafeTx = {},
                    onImportRequest = {},
                    onOpenActivity = {},
                    onToggleUnlistedNfts = {
                        events += "toggle"
                        state = state.copy(nfts = nfts.copy(unlistedOpen = !(state.nfts?.unlistedOpen ?: false)))
                    },
                )
            }
        }

        rule.onNodeWithTag(ACCOUNT_NFTS_TAG).performScrollTo()
        rule.onNodeWithText("ENS", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Received since block 9,000", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag(ACCOUNT_NFT_TAG).assertCountEquals(1)
        rule.onNodeWithText("Unlisted collections (1)").performClick()
        rule.onAllNodesWithTag(ACCOUNT_NFT_TAG).assertCountEquals(2)
        rule.onNodeWithText("#7 · not confirmed · 0xa7a7…a7a7", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("×3", useUnmergedTree = true).assertExists()
        assertEquals(listOf("toggle"), events)
    }

    @Test
    fun namesTheAccountsNetworkBesideItsControl() {
        show(ready)

        rule.onNodeWithText("Sepolia · Testnet", useUnmergedTree = true).assertExists()
    }

    @Test
    fun onANetworkThatIsOffSendSaysWhyAndTurnsItOn() {
        show(ready.copy(control = Control.FULL_CONTROL, networkOn = false))

        rule.onNodeWithText("Sepolia is off in this wallet: nothing is checked.").assertIsDisplayed()
        rule.onNodeWithText("Send").performClick()
        rule.onNodeWithText("Send needs Sepolia").assertIsDisplayed()
        rule.onNodeWithText("Turn on Sepolia").performClick()

        assertEquals(listOf("turn on"), events)
    }

    @Test
    fun showsTheAccountItsTotalAndWhatTheTotalLeavesOut() {
        show(ready)

        rule.onNodeWithText("Daily").assertIsDisplayed()
        rule.onNodeWithTag(ACCOUNT_TOTAL_TAG).assertTextEquals("$3,000.00")
        rule.onNodeWithTag(ACCOUNT_ADDRESS_TAG).assertTextEquals(ready.address)
        rule.onNodeWithText("Watch-only").assertIsDisplayed()
        rule.onNodeWithText("Testnet prices · 1 asset without a price").assertIsDisplayed()
        rule.onNodeWithText("Prices: Chainlink feeds, read through the light client, testnet prices").assertExists()
    }

    @Test
    fun saysWhatTheLightClientIsDoingWhileTheAmountsWaitForIt() {
        show(ready.copy(total = "Checking…"), lightClient = catchingUpStripSample)

        rule.onNodeWithTag(LIGHT_CLIENT_STRIP_TAG).assertIsDisplayed()
        rule.onNodeWithText("Verifying sync committees · 1 of 4 periods").assertIsDisplayed()
    }

    @Test
    fun eachRowHasItsValue() {
        show(
            ready.copy(
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true, "block 9", value = "$3,000.00"),
                    AssetRow("UNI", "Uniswap", "2 UNI", "uni", verified = true, "block 9", value = "No price"),
                ),
            ),
        )

        // The header's total and the ETH row's value.
        rule.onAllNodesWithText("$3,000.00", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("No price", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theMenuSortsTheRowsTheOtherWay() {
        var state by mutableStateOf(ready)
        rule.setContent {
            SerotineTheme {
                AccountDetailScreen(
                    state,
                    chrome = AccountChrome.Strip(verifiedStripSample),
                    onBack = {},
                    onSend = {},
                    onSwap = {},
                    onReceive = {},
                    onCopy = {},
                    onRename = {},
                    onRemove = {},
                    onHideToken = { _, _ -> },
                    onMessageShown = {},
                    onSort = { order ->
                        events += "sort $order"
                        state = state.copy(sort = order)
                    },
                    onSegment = {},
                    onOpenSafeTx = {},
                    onImportRequest = {},
                    onOpenActivity = {},
                )
            }
        }

        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Sort by value").assertDoesNotExist()
        rule.onNodeWithText("Sort by name").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Sort by name").assertDoesNotExist()
        rule.onNodeWithText("Sort by value").performClick()

        assertEquals(listOf("sort NAME", "sort VALUE"), events)
    }

    @Test
    fun aSafesOwnersOfferNoSort() {
        show(ready.copy(kind = AccountKind.SAFE, safe = SafeDetails.Checking, segment = DetailSegment.OWNERS))

        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Sort by name").assertDoesNotExist()
    }

    @Test
    fun theActivityOffersTheOtherOrderInPlaceOfTheSort() {
        var state by mutableStateOf(ready.copy(segment = DetailSegment.ACTIVITY))
        rule.setContent {
            SerotineTheme {
                AccountDetailScreen(
                    state,
                    chrome = AccountChrome.Strip(verifiedStripSample),
                    onBack = {},
                    onSend = {},
                    onSwap = {},
                    onReceive = {},
                    onCopy = {},
                    onRename = {},
                    onRemove = {},
                    onHideToken = { _, _ -> },
                    onMessageShown = {},
                    onSort = { events += "sort $it" },
                    onSegment = {},
                    onOpenSafeTx = {},
                    onImportRequest = {},
                    onOpenActivity = {},
                    onOrderActivity = { order ->
                        events += "order $order"
                        state = state.copy(activityOrder = order)
                    },
                )
            }
        }

        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Sort by name").assertDoesNotExist()
        rule.onNodeWithText("Newest first").assertDoesNotExist()
        rule.onNodeWithText("Oldest first").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Oldest first").assertDoesNotExist()
        rule.onNodeWithText("Newest first").performClick()

        assertEquals(listOf("order OLDEST_FIRST", "order NEWEST_FIRST"), events)
    }

    @Test
    fun sendSwapAndReceiveShareOneLineInFullOnANarrowPhoneAndWithLargerText() {
        var width by mutableStateOf(375.dp)
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.requiredWidth(width)) { Screen(ready, verifiedStripSample) }
            }
        }

        // An iPhone mini's width; narrower still, where the buttons leave out their icons; and an
        // iPhone's width with the text made larger.
        for ((narrow, scale) in listOf(375.dp to 1f, 320.dp to 1f, 393.dp to 1.5f)) {
            width = narrow
            fontScale = scale
            rule.waitForIdle()
            val labels = listOf("Send", "Swap", "Receive")
            val tops = labels.map { rule.onNodeWithText(it).getBoundsInRoot().top }
            assertEquals(1, tops.distinct().size, "one line at $narrow, text ×$scale: $tops")
            for (label in labels) {
                assertFalse(ellipsized(label), "$label cut off at $narrow, text ×$scale")
            }
        }
    }

    /**
     * Whether the text [label] is drawn cut off: narrower than it needs. (Not isLineEllipsized,
     * which the desktop's text layout does not report.)
     */
    private fun ellipsized(label: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        return layout.multiParagraph.intrinsics.maxIntrinsicWidth > layout.size.width
    }

    @Test
    fun everyActionOfTheLongPressMenuIsAlsoHere() {
        show(ready)

        rule.onNodeWithText("Receive").performClick()
        rule.onNodeWithContentDescription("Copy address").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Remove").performClick()

        assertEquals(listOf("receive", "copy ${ready.address}", "rename", "remove"), events)
        rule.onNodeWithText("Address copied").assertIsDisplayed()
    }

    @Test
    fun anAccountWhoseKeyIsHeldHereSendsFromItsButtonAndFromAnAssetsMenu() {
        val contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
        show(
            ready.copy(
                control = Control.FULL_CONTROL,
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true),
                    AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = contract),
                ),
            ),
        )

        rule.onNodeWithText("Send").performClick()
        // A menu item by its action, not by a pointer: a menu opened upwards lies over the Send
        // button, and the test's injected release would reach the button once the menu is gone.
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Send USDC").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("1.5 ETH").performTouchInput { longClick() }
        rule.onNodeWithText("Send ETH").performSemanticsAction(SemanticsActions.OnClick)
        // A tap on a row sends its asset.
        rule.onNodeWithText("5 USDC").performClick()
        rule.onNodeWithText("1.5 ETH").performClick()

        assertEquals(listOf("send null", "send $contract", "send null", "send $contract", "send null"), events)
    }

    @Test
    fun aWatchOnlyAccountShowsSendGreyedOutAndATapSaysWhy() {
        show(ready.copy(assets = listOf(AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = "0x1"))))

        // Owner ruling, 2026-10-18, #55: shown, greyed out, and a tap says what is missing.
        rule.onNodeWithText("Send").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "This account is watch-only: its key is not on this device, so nothing can be sent from it. Import its " +
                "recovery phrase to send from it.",
        )
        rule.onNodeWithText("Close").performClick()
        assertEquals(emptyList(), events.filter { it.startsWith("send") }, "nothing sent")
        // A tap on the row says the same.
        rule.onNodeWithText("5 USDC").performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertEquals(emptyList(), events.filter { it.startsWith("send") }, "nothing sent")
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Hide token").assertIsDisplayed()
        rule.onNodeWithText("Send USDC")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertIsDisplayed()
        assertEquals(emptyList(), events.filter { it.startsWith("send") }, "nothing sent")
    }

    @Test
    fun anAccountWhoseKeyIsHeldHereSwapsFromItsButtonAndFromAnAssetsMenu() {
        val contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
        show(
            ready.copy(
                control = Control.FULL_CONTROL,
                assets = listOf(AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = contract)),
            ),
        )

        rule.onNodeWithTag(ACCOUNT_SWAP_TAG).performClick()
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Swap USDC").performClick()

        assertEquals(listOf("swap null", "swap $contract"), events)
    }

    @Test
    fun aWatchOnlyAccountShowsSwapGreyedOutAndATapSaysWhy() {
        show(ready.copy(assets = listOf(AssetRow("USDC", "USDC", "5 USDC", "usdc", verified = true, contract = "0x1"))))

        rule.onNodeWithTag(ACCOUNT_SWAP_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "This account is watch-only: its key is not on this device, so nothing can be swapped from it. " +
                "Import its recovery phrase to swap from it.",
        )
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Swap USDC").performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertIsDisplayed()
        assertEquals(emptyList(), events.filter { it.startsWith("swap") }, "nothing swapped")
    }

    @Test
    fun aSafeSwapsWithAnOwnerHeldHereAndIsGreyedOutWithNone() {
        show(ready.copy(kind = AccountKind.SAFE, control = Control.CO_SIGNER, safe = SafeDetails.Checking))
        rule.onNodeWithTag(ACCOUNT_SWAP_TAG).performClick()
        assertEquals(listOf("swap null"), events.filter { it.startsWith("swap") })
    }

    @Test
    fun aWatchedSafesSwapIsGreyedOutAndATapSaysSo() {
        show(ready.copy(kind = AccountKind.SAFE, control = Control.WATCH_ONLY, safe = SafeDetails.Checking))

        rule.onNodeWithTag(ACCOUNT_SWAP_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
            .performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "No owner of this Safe has its key on this device, so nothing can be proposed from it. Import an " +
                "owner's recovery phrase to propose and sign its transactions.",
        )
        assertEquals(emptyList(), events.filter { it.startsWith("swap") }, "nothing swapped")
    }

    @Test
    fun anUnreadableListIsExplained() {
        show(AccountDetailUiState.Unavailable("Your account list cannot be read right now."))

        rule.onNodeWithText("Your account list cannot be read right now.").assertIsDisplayed()
        rule.onNodeWithContentDescription("More actions").assertDoesNotExist()
    }

    @Test
    fun theAssetsAreListedEachWithItsBlockAndWhatIsNotKnownYet() {
        show(
            ready.copy(
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true, verifiedAt = "block 9"),
                    AssetRow("USDC", "USDC", "1250 USDC", "usdc", verified = true, verifiedAt = "block 12"),
                    AssetRow("XYZ", "Some token", "3 XYZ", null, verified = true),
                ),
                tokensNote = "Checking 1 token",
            ),
        )

        rule.onAllNodesWithTag(ACCOUNT_ASSET_TAG).assertCountEquals(3)
        rule.onNodeWithText("1250 USDC").assertIsDisplayed()
        rule.onNodeWithText("USDC · verified at block 12").assertIsDisplayed()
        rule.onNodeWithText("XY").assertIsDisplayed() // the monogram of a token without an icon
        rule.onNodeWithText("Checking 1 token").assertIsDisplayed()
    }

    @Test
    fun aTokenRowIsHiddenFromItsMenuAndAnAddedImpostorIsMarked() {
        val contract = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
        show(
            ready.copy(
                assets = listOf(
                    AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true),
                    AssetRow(
                        "USDC",
                        "Not USDC",
                        "5 USDC",
                        null,
                        verified = true,
                        contract = contract,
                        warning = "Not the built-in USDC: another contract",
                    ),
                ),
            ),
        )

        rule.onNodeWithText("Not the built-in USDC: another contract").assertIsDisplayed()
        rule.onNodeWithText("5 USDC").performTouchInput { longClick() }
        rule.onNodeWithText("Hide token").performClick()
        assertEquals(listOf("hide USDC $contract"), events)
    }

    @Test
    fun howHidingWentIsShownInASnackbar() {
        val said = "USDC could not be hidden: the token settings cannot be saved right now."
        show(ready.copy(message = said))

        rule.onNodeWithText(said).assertIsDisplayed()
    }

    @Test
    fun anAccountsTabsAreAssetsAndActivityAndASafesOwnersAndQueueFollow() {
        show(ready)

        rule.onNodeWithText("Assets").assertIsDisplayed()
        rule.onNodeWithText("Owners").assertDoesNotExist()
        rule.onNodeWithText("Activity").performClick()
        assertEquals(listOf("segment ACTIVITY"), events)

        assertEquals(listOf(DetailSegment.ASSETS, DetailSegment.ACTIVITY), ready.segments)
        assertEquals(
            listOf(DetailSegment.ASSETS, DetailSegment.ACTIVITY, DetailSegment.OWNERS, DetailSegment.QUEUE),
            ready.copy(kind = AccountKind.SAFE, safe = SafeDetails.Checking).segments,
        )
    }

    private val sent = ActivityRow(
        key = "0x" + "1".repeat(64),
        title = "Sent 0.25 ETH",
        detail = "To Bob · 0x5aAe…eAed",
        about = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        time = "Oct 19, 2026, 2:02 PM",
        status = "Reverted in block 9,401,220",
        state = ActivityState.FAILED,
        opens = ActivityKey("0x" + "1".repeat(64)),
    )

    @Test
    fun theActivityTabSaysWhatEachRowDidAndHowItEndedAndATapOpensItsDetail() {
        show(
            ready.copy(
                segment = DetailSegment.ACTIVITY,
                activity = ActivityUiState.Shown(listOf(sent), "Transactions sent from this device."),
            ),
        )

        rule.onNodeWithText("Transactions sent from this device.").assertIsDisplayed()
        rule.onNodeWithText("Reverted in block 9,401,220").assertIsDisplayed()
        rule.onNodeWithText("To Bob · 0x5aAe…eAed").assertIsDisplayed()
        rule.onAllNodesWithTag(ACCOUNT_ASSET_TAG).assertCountEquals(0)
        rule.onNodeWithText("Sent 0.25 ETH").performClick()

        assertEquals(listOf("open activity ${sent.opens}"), events)
    }

    @Test
    fun aSafesEventInItsActivityOpensTheDetailOfItsLog() {
        val opens = ActivityKey("0x" + "2".repeat(64), 0)
        val event = sent.copy(key = "0x" + "2".repeat(64) + ":0", title = "Received 1 ETH", opens = opens)
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                safe = SafeDetails.Checking,
                segment = DetailSegment.ACTIVITY,
                activity = ActivityUiState.Shown(listOf(event), "Its events on chain."),
            ),
        )

        rule.onNodeWithText("Received 1 ETH").performClick()

        assertEquals(listOf("open activity $opens"), events)
    }

    @Test
    fun backFromARowsDetailTheActivityIsWhereItWasLeftOnceItIsReadAgain() {
        val rows = (1..40).map { n ->
            val hash = "0x" + n.toString(16).padStart(64, '0')
            sent.copy(key = hash, title = "Sent $n ETH", opens = ActivityKey(hash))
        }
        val read = ActivityUiState.Shown(rows, "Transactions sent from this device.")
        var activity by mutableStateOf<ActivityUiState>(read)
        var shown by mutableStateOf(true)
        // As the back stack keeps an entry's saved state while another one is in front.
        rule.setContent {
            val entry = rememberSaveableStateHolder()
            if (shown) {
                entry.SaveableStateProvider("account") {
                    Screen(ready.copy(segment = DetailSegment.ACTIVITY, activity = activity), verifiedStripSample)
                }
            }
        }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sent 30 ETH"))

        // Back from a row's detail after the Activity's state expired: it is read again from Loading.
        shown = false
        rule.waitForIdle()
        activity = ActivityUiState.Loading
        shown = true
        rule.waitForIdle()
        activity = read

        rule.onNodeWithText("Sent 30 ETH").assertIsDisplayed()
        rule.onNodeWithText("Sent 1 ETH").assertDoesNotExist()
    }

    @Test
    fun anEmptyActivitySaysSo() {
        show(
            ready.copy(
                segment = DetailSegment.ACTIVITY,
                activity = ActivityUiState.Shown(emptyList(), "Transactions sent from this device."),
            ),
        )

        rule.onNodeWithText("Nothing sent from this device yet.").assertIsDisplayed()
        rule.onAllNodesWithTag(ACTIVITY_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun anUnreadableActivitySaysWhy() {
        show(
            ready.copy(
                segment = DetailSegment.ACTIVITY,
                activity = ActivityUiState.Unavailable("The history cannot be read right now."),
            ),
        )

        rule.onNodeWithText("The history cannot be read right now.").assertIsDisplayed()
    }

    @Test
    fun aSafesOwnersAreBehindTheirSegmentWithTheOnesHeldHereMarked() {
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                safe = SafeDetails.Shown(
                    title = "Safe 1.4.1 · nonce 12",
                    signers = "2 of 2 owners must sign",
                    owners = listOf(
                        OwnerRow("0x8f3A2C71E5B9d04A6f1e3C2BA7D90E5F4c6B91c2", "Daily", heldHere = true),
                        OwnerRow("0x3333333333333333333333333333333333333333", null, heldHere = false),
                    ),
                    modules = listOf("0x4444444444444444444444444444444444444444"),
                    guard = null,
                    checkedAt = "Verified at block 9,412,118",
                ),
                segment = DetailSegment.OWNERS,
            ),
        )

        rule.onNodeWithText("2 of 2 owners must sign").assertIsDisplayed()
        rule.onNodeWithText("This device").assertIsDisplayed()
        rule.onNodeWithText("Not in this wallet").assertIsDisplayed()
        rule.onNodeWithText("Modules: each can move this Safe's funds without the owners' signatures.").assertExists()
        rule.onNodeWithText("Assets").performClick()
        assertEquals(listOf("segment ASSETS"), events)
    }

    @Test
    fun aGnosisPayAccountsOwnersOpenItsScreenAndItsSendPointsThere() {
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                control = Control.WATCH_ONLY,
                safe = SafeDetails.Shown(
                    title = "Safe 1.3.0 · nonce 1",
                    signers = "1 of 1 owners must sign",
                    owners = listOf(OwnerRow(GNOSIS_PAY_PLACEHOLDER, null, heldHere = false)),
                    modules = emptyList(),
                    guard = null,
                    checkedAt = "Verified at block 1",
                ),
                segment = DetailSegment.OWNERS,
                gnosisPay = "Gnosis Pay account. Its controlling key is on this device.",
            ),
        )

        rule.onNodeWithText("Gnosis Pay account. Its controlling key is on this device.").assertExists()
        rule.onNodeWithTag(ACCOUNT_GNOSIS_PAY_TAG).performScrollTo().performClick()
        assertEquals(listOf("gnosis pay"), events)

        // Send on the Safe itself: no owner signs for it, so the way is its Gnosis Pay screen.
        rule.onNodeWithText("Send").performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(GNOSIS_PAY_SEND)
        // The dialog's way out, drawn over the Owners tab's own entry of the same name.
        rule.onAllNodesWithText("Gnosis Pay").onLast().performClick()
        assertEquals(listOf("gnosis pay", "gnosis pay"), events)
    }

    @Test
    fun aSafesOwnersOpenItsSettingsAndOnANetworkThatIsOffSayWhyNot() {
        val safe = ready.copy(kind = AccountKind.SAFE, safe = SafeDetails.Checking, segment = DetailSegment.OWNERS)
        var state by mutableStateOf<AccountDetailUiState>(safe)
        rule.setContent { Screen(state, verifiedStripSample) }

        rule.onNodeWithTag(ACCOUNT_SAFE_SETTINGS_TAG).performScrollTo().performClick()
        assertEquals(listOf("safe settings"), events)

        state = safe.copy(networkOn = false)
        rule.onNodeWithTag(ACCOUNT_SAFE_SETTINGS_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Safe settings needs Sepolia").assertIsDisplayed()
        rule.onNodeWithText("Turn on Sepolia").performClick()
        assertEquals(listOf("safe settings", "turn on"), events)

        state = safe.copy(networkBlocked = true)
        rule.onNodeWithTag(ACCOUNT_SAFE_SETTINGS_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(UNAVAILABLE_REASON_TAG).assertTextEquals(
            "This version of Punicus can no longer follow Sepolia: nothing can be changed from its accounts until " +
                "you update the app.",
        )
        assertEquals(listOf("safe settings", "turn on"), events)
    }

    @Test
    fun aSafesQueueListsItsTransactionsOpensOneAndImportsARequest() {
        val hash = "0x" + "3c".repeat(32)
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                control = Control.CO_SIGNER,
                safe = SafeDetails.Checking,
                segment = DetailSegment.QUEUE,
                queue = SafeQueue.Shown(
                    listOf(
                        QueueRow(hash, "Send 40 USDC", null, "Nonce 12 · next", "1 of 2 signatures"),
                        QueueRow(
                            "0x" + "4d".repeat(32),
                            "Delegate call",
                            null,
                            "Nonce 13 · 1 before it",
                            "0 of 2 signatures",
                            refusal = "Not signed here: a delegate call",
                        ),
                    ),
                ),
            ),
        )

        rule.onNodeWithText("Queue (2)").assertIsDisplayed()
        rule.onAllNodesWithTag(ACCOUNT_QUEUE_ROW_TAG).assertCountEquals(2)
        rule.onNodeWithText("Nonce 12 · next · 1 of 2 signatures").assertIsDisplayed()
        rule.onNodeWithText("Not signed here: a delegate call").assertIsDisplayed()
        rule.onNodeWithText("Send 40 USDC").performClick()
        rule.onNodeWithText("Import a request").performClick()
        // A Safe with an owner held here sends: Send proposes a Safe transaction.
        rule.onNodeWithText("Send").performClick()
        assertEquals(listOf("open $hash", "import", "send null"), events)
    }

    @Test
    fun aQueueThatCannotBeReadSaysSo() {
        show(
            ready.copy(
                kind = AccountKind.SAFE,
                safe = SafeDetails.Checking,
                segment = DetailSegment.QUEUE,
                queue = SafeQueue.Unavailable("This device's queue of Safe transactions cannot be read right now."),
            ),
        )

        rule.onNodeWithText("This device's queue of Safe transactions cannot be read right now.").assertIsDisplayed()
        rule.onNodeWithText("Queue").assertIsDisplayed()
        rule.onNodeWithText("Send")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
    }

    @Test
    fun eachTokenStillCheckedHoldsTheRoomOfItsRowSoTheRowTakesItWithoutAJump() {
        val ether = AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true, "block 9", value = "$3,000.00")
        show(ready.copy(assets = listOf(ether), pendingAssets = 2, tokensNote = "Checking 2 tokens"))

        val ghosts = rule.onAllNodesWithTag(GHOST_ROW_TAG, useUnmergedTree = true)
        ghosts.assertCountEquals(2)
        assertEquals(
            rule.onNodeWithTag(ACCOUNT_ASSET_TAG).getBoundsInRoot().height,
            ghosts[0].getBoundsInRoot().height,
        )
        rule.onNodeWithContentDescription("Tokens not read yet").assertExists()
    }

    @Test
    fun noGhostRowOnceEveryTokenIsChecked() {
        show(ready.copy(assets = listOf(AssetRow("ETH", "Ether", "1.5 ETH", "eth", verified = true))))

        rule.onAllNodesWithTag(GHOST_ROW_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun anActivityBeingReadShowsGhostRowsOfItsRowsHeight() {
        var activity by mutableStateOf<ActivityUiState>(ActivityUiState.Loading)
        rule.setContent {
            Screen(ready.copy(segment = DetailSegment.ACTIVITY, activity = activity), verifiedStripSample)
        }

        val ghosts = rule.onAllNodesWithTag(GHOST_ROW_TAG, useUnmergedTree = true)
        ghosts.assertCountEquals(GHOST_ROWS_UNKNOWN)
        rule.onNodeWithContentDescription("Reading the activity").assertExists()
        val ghost = ghosts[0].getBoundsInRoot().height

        activity = ActivityUiState.Shown(listOf(sent), "Transactions sent from this device.")
        rule.waitForIdle()
        rule.onAllNodesWithTag(GHOST_ROW_TAG, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(ghost, rule.onNodeWithTag(ACTIVITY_ROW_TAG).getBoundsInRoot().height)
    }

    @Test
    fun aSafesQueueBeingReadShowsGhostRowsOfItsRowsHeight() {
        val safe = ready.copy(kind = AccountKind.SAFE, safe = SafeDetails.Checking, segment = DetailSegment.QUEUE)
        var queue by mutableStateOf<SafeQueue>(SafeQueue.Checking)
        rule.setContent { Screen(safe.copy(queue = queue), verifiedStripSample) }

        val ghosts = rule.onAllNodesWithTag(GHOST_ROW_TAG, useUnmergedTree = true)
        ghosts.assertCountEquals(GHOST_ROWS_UNKNOWN)
        rule.onNodeWithContentDescription("Reading this device's queue").assertExists()
        val ghost = ghosts[0].getBoundsInRoot().height

        queue = SafeQueue.Shown(
            listOf(QueueRow("0x" + "3c".repeat(32), "Send 40 USDC", null, "Nonce 12 · next", "1 of 2 signatures")),
        )
        rule.waitForIdle()
        assertEquals(ghost, rule.onNodeWithTag(ACCOUNT_QUEUE_ROW_TAG).getBoundsInRoot().height)
    }

    @Test
    fun aSafeBeingReadShowsGhostOwners() {
        show(ready.copy(kind = AccountKind.SAFE, safe = SafeDetails.Checking, segment = DetailSegment.OWNERS))

        rule.onAllNodesWithTag(GHOST_ROW_TAG, useUnmergedTree = true).assertCountEquals(GHOST_ROWS_UNKNOWN)
        rule.onNodeWithContentDescription("Reading the Safe's storage").assertExists()
    }

    private val indexRow = ActivityRow(
        key = "index:9412300:4",
        title = "Received 0.5 ETH",
        detail = "From Treasury · 0xfB69…d359",
        about = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        time = "Block 9,412,300",
        status = "Finalized in block 9,412,300 · checked against the chain",
        state = ActivityState.DONE,
        opens = ActivityKey("0x" + "4c".repeat(32), index = IndexEntryKey(9_412_300, 4, setOf(AddressIndexRole.TO))),
    )

    private fun indexed(footer: IndexFooter, contradicted: String? = null) = ready.copy(
        segment = DetailSegment.ACTIVITY,
        activity = ActivityUiState.Shown(
            listOf(indexRow),
            "Transactions sent from this device.",
            index = IndexSection(
                listOf("Also from the addr1 index on Swarm: entries may be missing."),
                footer,
                leftOut = "2 entries of the index are left out: they could not be verified.",
                contradicted = contradicted,
            ),
        ),
    )

    @Test
    fun theAddr1IndexsRowsAreListedWithItsLinesAndTheListNearingItsEndAsksForMore() {
        show(indexed(IndexFooter.More, contradicted = "Warning: the importer's index contradicts the chain."))

        rule.onNodeWithText("Also from the addr1 index on Swarm: entries may be missing.").assertExists()
        rule.onNodeWithText("2 entries of the index are left out: they could not be verified.").assertExists()
        rule.onNodeWithText("Warning: the importer's index contradicts the chain.").assertExists()
        rule.onNodeWithText("Received 0.5 ETH").performClick()
        rule.waitForIdle()

        assertEquals(1, events.count { it == "more activity" }, "the footer composed asks once")
        assertEquals("open activity ${indexRow.opens}", events.last())
    }

    @Test
    fun aRefusalOfTheAddr1IndexIsSaidAndAPassingOneOffersTryAgain() {
        show(indexed(IndexFooter.Refused("The light client is still catching up.", retry = true)))

        rule.onNodeWithTag(ACTIVITY_INDEX_FOOTER_TAG).performScrollTo()
        rule.onNodeWithText("The light client is still catching up.").assertExists()
        rule.onNodeWithText("Try again").performClick()

        assertEquals(listOf("more activity"), events)
    }

    @Test
    fun oldestFirstTheAddr1IndexLoadsOlderEntriesOnlyOnATap() {
        show(indexed(IndexFooter.More).let { it.copy(activity = it.activity.ordered(ActivityOrder.OLDEST_FIRST)) })
        rule.waitForIdle()

        assertEquals(emptyList(), events, "above the rows, in sight from the start: nothing read by itself")
        rule.onNodeWithText("Load older entries").performClick()
        assertEquals(listOf("more activity"), events)
    }

    @Test
    fun afterAPageThatAddedNoRowTheAddr1IndexReadsOnOnlyOnATap() {
        show(indexed(IndexFooter.Paused("The last page of the addr1 index added no row.")))
        rule.waitForIdle()

        assertEquals(emptyList(), events, "nothing read by itself")
        rule.onNodeWithTag(ACTIVITY_INDEX_FOOTER_TAG).performScrollTo()
        rule.onNodeWithText("The last page of the addr1 index added no row.").assertExists()
        rule.onNodeWithText("Load older entries").performClick()
        assertEquals(listOf("more activity"), events, "a tap reads exactly one page")
    }

    @Test
    fun theEndOfTheAddr1IndexsCoverageIsMarkedAndAsksForNothing() {
        show(indexed(IndexFooter.End("The addr1 index starts at block 9,000,000: nothing older comes from it.")))

        rule.onNodeWithTag(ACTIVITY_INDEX_FOOTER_TAG).performScrollTo()
        rule.onNodeWithText("The addr1 index starts at block 9,000,000: nothing older comes from it.").assertExists()
        rule.onNodeWithText("Try again").assertDoesNotExist()
        assertEquals(emptyList(), events)
    }
}
