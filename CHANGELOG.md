# Changelog

Every release of Punicus (called Serotine before 0.4.0), newest first. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions follow [Semantic Versioning](https://semver.org/). Each release's
installers are on its [GitHub release](https://github.com/VesperBats/punicus/releases);
every release so far is a pre-release. The release PR adds its entry here
(`.claude/skills/release/SKILL.md`); numbers in parentheses are pull requests
and issues of this repository.

## [Unreleased]

### Added

- Gnosis Pay's app can ask, over WalletConnect, for a change of the card's
  daily limit, and Punicus reads it: "Sets the Gnosis Pay card's spending
  limit to 8,000 a day", every value raw. It is signed only through the
  verified Delay of a Gnosis Pay account in this wallet, by one of its
  members; anything else asked that way is shown and refused.

- Requests from public wallet-testing sites are recognised: MetaMask's test
  dApp and Reown's sample dApp (both over WalletConnect) and Walletbeat's
  test playground. A known test signature says which test it is, byte for
  byte; the addresses MetaMask's test dApp uses as bad actors in its
  malicious demos are named in red. Token calls beyond `transfer` and
  `approve` are now decoded on dApp transactions and Safe transactions:
  `transferFrom`, `increaseAllowance`, `setApprovalForAll` (an approval of a
  whole NFT collection, warned about loudly), and NFT and ERC-1155 transfers.

- What Safe{Wallet} asks over WalletConnect is decoded by default: a Safe
  transaction to sign (`SafeTx`) shows what it does as the Safe's own screen
  does (a payment, a token or an NFT sent, an approval, a change of owners or
  threshold, a CoW order pre-signed, a batch call by call), with the Safe as
  the light client reads it, its nonce and its hash; an owner's Execute
  (`execTransaction`) shows the transaction it runs; an on-chain approval
  (`approveHash`) and a Safe message (`SafeMessage`) are named, with what
  their requests cannot show. The Safe refusals now hold there too: a delegate
  call, gas terms, a module enabled, an unpinned fallback handler, an
  undecoded call of the Safe itself, or a change leaving the Safe an n-of-n is
  shown and never signed. A Safe transaction whose call is not decoded says
  that this is unusual.
- A session key grant (Rhinestone's Smart Sessions, as ENS's and other dApps'
  smart accounts ask for one) is read and put in words: the account, the
  calls allowed, the policies, the expiry and who holds the key, each
  unlimited power warned about first.

- The first start says what to expect: a note, shown once until OK, that
  the first start takes a few minutes while Punicus finds computers of the
  network to talk to, that nothing works until a network is checked, and
  that Try again often helps since Punicus keeps trying while it is open.
  The website's guide says the same under *When you run Punicus for the
  first time*, and the downloads point to it.
- The account history from the addr1 index now covers Gnosis Chain as well
  as Ethereum: a Gnosis Chain account's Activity also lists the verified
  entries of the one-year Gnosis Chain index on Swarm, signed by its own
  importer key, `0xE779…9335`, pinned in this release. The experimental
  setting names both keys; it is on by default for both, and turning it off
  turns both off. Sepolia has no such index. Needs Myotis 0.1.25, which
  reads each chain's index under its own key.
- Clear signing of dApp transactions, and of Permit2's batch permits among
  typed data: a call, or such a message, that Punicus did not decode before is
  now put in words when a built-in ERC-7730 description covers its contract
  (WETH, Permit2, Uniswap's SwapRouter02, Seaport 1.6, Lido's wstETH, the
  Aave v3 Pool and the ENS registrar controller on Ethereum; Permit2 and the
  Aave v3 Pool on Gnosis Chain; WETH and Permit2 on Sepolia). The
  descriptions are built into the app, never fetched, and each is used only
  while the contract's code, checked by the light client, is the code this
  release was built for: a contract changed since says so and stays not
  decoded. The words are the description's author's and are labelled so;
  every value is decoded from what you sign and shown with its raw form, and
  every parameter a description does not show is listed under "Other
  parameters". An approval is flagged whatever its description says (#240).
- What a dApp's transaction, or a Safe transaction, does when it runs now:
  the light client runs it at its verified head and Punicus shows what it
  sends and receives, the rights over your tokens it grants, the addresses it
  calls and whether it reverts, labelled as proven now and not when it is
  mined. Where that disagrees with what the call says it does (a token it
  does not name, tokens ending with an address it does not show, a right it
  does not say it grants), a loud warning says so; the call can still be
  signed. A Safe transaction is run as the Safe's own call, a delegate call
  not at all. On Android and the desktop; iOS says it cannot trace yet
  (#240).

### Changed

- The embedded Myotis engine is 0.1.25 (was 0.1.24). It reads each chain's
  addr1 index under its own importer key (Myotis #933). It brings a bundled
  checkpoint table for Gnosis Chain (Myotis #934): a Gnosis Chain block or
  its receipts deeper than the light client's 512-block window now verifies
  against it, as on Ethereum, which every older row of Gnosis Chain's addr1
  index rests on. The table adds about 1.55 MB per processor type to the
  apps. It also extends Ethereum's checkpoint table to block 26,497,669 and
  moves the embedded checkpoints of Ethereum, Gnosis Chain and Sepolia to
  2026-11-26.

## [0.6.0] - 2026-11-25

### Added

- Per-app addresses: each site a dApp connects from is offered an address
  of its own, made from your recovery phrase, so that no site can see your
  activity on another. The connection request preselects it, and the sites
  are remembered. Per-app addresses lists them, with each one's site,
  networks, phrase and path, and restores one by site. A dApp that moved
  may use its old site's address, after a question saying the two sites can
  then link your activity. The signing screen names the address's site and
  warns when a sign-in is for another site (#252; #297, #311, #313, #326,
  #328, #329, #330).
- Add an account from its private key: Import phrase or key (formerly
  Import recovery phrase) now asks for a seed phrase or a private key, 64 hex
  digits with or without 0x. The key is checked, then sealed on this device
  under the wallet password like every key, and never shown again. Such an
  account has no recovery phrase, so New account from the same phrase and
  per-app addresses are not offered for it; only the key brings it back
  (#376).
- Account history from the addr1 index (desktop and Android, experimental,
  on by default in Settings → Experimental): an account's Activity also lists its
  older transactions from the addr1 address index on Swarm, each entry
  checked against the chain before it is shown, one by one as the list
  scrolls. Which entries the index holds rests on its importer, whose key the
  release pins and the list names; the list says what the index does not
  cover, how many entries it left out, and warns when the chain contradicts
  one. The engine's Swarm reader comes with Myotis 0.1.23; with an engine
  without it, the setting is greyed out, saying so (#334). What was read is
  kept between runs: opening an account's Activity again shows its verified
  rows at once and reads only the newer entries, which come in at the top.
  Only rows of finalized blocks are kept; the newest blocks, and any range
  Swarm did not deliver, are read again (#369). On Android too (#367). With
  Myotis 0.1.24, each row shows the date and time of its block, from the
  block the engine verified it in, instead of "Date not available" (#377).
- Activity at a glance: the rows are grouped by day under a heading
  ("Today", "Yesterday", then the date), each with its time in your time
  zone; a green arrow for value in and a red one for value out, and the
  amount on the right, signed and shortened for reading ("+250 USDC",
  "−0.25 ETH", "1.23M"). The exact amount stays in the row's detail (#350).
- Buy an exact amount: type the amount to buy, on Uniswap or CoW, and the
  swap buys exactly that for at most its estimate plus your tolerance. The
  first screen says what it would cost, and when your balance cannot cover
  the most it may take (#343).
- With CoW chosen, the first screen shows CoW's estimate and the rate, marked
  as not checked on chain. CoW then learns the amounts you compose (#343).
- Swaps on Gnosis Chain: Uniswap v3's Gnosis deployment, with WXDAI as the
  wrapped xDAI, and CoW Protocol. Uniswap holds little there, so most pairs
  go to CoW. CoW Protocol is offered on Ethereum too (#341).
- Swarm's xBZZ (`BZZ`) is a built-in token on Gnosis Chain, with no price
  (#341).
- Address poisoning protection on Send. A recipient that starts and ends
  like an address you sent to from this device (or one of your accounts) but
  differs in the middle blocks the send, with both addresses shown and the
  differing middle highlighted; it can only be changed. A recipient that a
  transfer of nothing, dust or an unlisted token ties to your account in
  your history, or one checked while the history of what you sent cannot be
  read, blocks the send until you confirm you checked it. A new recipient
  gets a notice. The review shows the recipient in full in groups of four,
  and the sender of a cautioned transfer in Activity has no Copy (#324).
- A website, `website/`: what Punicus does in plain words, the downloads and
  a guide to adding accounts and Safes, published to GitHub Pages by the
  `Website` workflow (#316).
- Gnosis Pay: a Gnosis Pay account's Safe settings list the members of its
  Delay module, who control it, and a key held here that controls the
  account can add a member or remove one, never the last, queued through
  the Delay and executed after its wait like a transfer. Adding one says
  that the new member can move all the account's funds; removing the way
  this device controls the account says it ends that control (#321, #323).
- Gnosis Pay: "Skip expired entries" clears an expired entry that blocks the
  Delay module's queue, with the module's own `skipExpired()`, which moves
  nothing. It is offered on the Send form and on a transfer queued here that
  waits behind such an entry (#319).
- On a wide window (840 dp and wider: a desktop window, a tablet, an unfolded
  phone) the Accounts tab shows the list and the account chosen side by side.
  From 1200 dp on, a transaction tapped in an account's Activity opens its
  detail in a third column beside the account, which stays as it was, the
  row drawn selected (#372).
- Per-app addresses: each site a dApp connects from over WalletConnect gets
  an address of its own, derived from the recovery phrase, so that no site
  sees what you do on another (#252, #331).
- Swarm nodes: an account on Gnosis Chain is recognised as a Swarm (Bee)
  node once a chequebook or a postage batch is proven its own. Its postage
  batches can be bought, topped up and diluted, and its chequebook withdrawn
  from, through Bee's pinned contracts only; the node's account is watched,
  with no `swarm.key` imported (#357).
- In simple mode, each network's accounts come under a header that says its
  sync state in a few words ("Up to date", "Syncing · 3 of 12 periods",
  "Off"), and a tap on it folds them away and back (#371).
- A day's heading in an account's Activity stays on top while that day's
  rows scroll under it (#370).

### Changed

- Simple mode: the switch for heavy downloads on metered connections says
  what it holds back, catching up on activity since you added an account.
  It said "older activity", which in simple mode names the rows from the
  addr1 index, and those read on any connection, mobile data too (#375).
- The repository moved to <https://github.com/VesperBats/punicus>: the update
  screen's link and the website's downloads point there (#333).
- The website's address is <https://punicus.eth.limo/>; the old GitHub Pages
  address no longer answers since the move to VesperBats (#373).
- The embedded Myotis engine is 0.1.24 (was 0.1.21-rc1). It brings the
  engine's address history with its Swarm reader on the desktop and Android,
  each checked entry with its block's verified time (Myotis 0.1.24), the
  mainnet checkpoint table up to block 26,491,578, and the embedded
  checkpoints of 2026-11-25 for Ethereum, Gnosis Chain and Sepolia. The
  reader runs only on a read, while an account's Activity is open with the
  addr1 setting on; it adds about 11 MB to the Android app, which carries
  the engine for two processor types. The reader runs inside the app: a
  crash of the reader on what a Swarm peer sends ends the app, a limit Myotis
  accepts for now (freedom-hq/ant#271; #352, #366, #377).
- A CoW order no longer depends on Uniswap: its minimum is CoW's own
  estimate less your tolerance, and every pair CoW quotes is offered. The
  review says the minimum rests on CoW's estimate, which is not checked on
  chain, and shows no Uniswap figure beside it (#341).

### Fixed

- Gnosis Pay: an account's Delay module is verified again on a phone: its
  getters are read at one block by holding every call to the block the first
  answered at. Read at the head alone, eight calls straddled two blocks
  nearly every time, so the account stayed a plain watched Safe (#318).
- Gnosis Pay: an account a key held here controls no longer falls back to
  Watch-only whenever one of the reads behind it finds no peer serving its
  proof at a head. The answer verified before stands, for an hour at most,
  until one that differs or a reason that stands (the network turned off);
  no transfer is reviewed on it (#318).

## [0.5.0] - 2026-11-16

### Added

- Gnosis Pay: a Gnosis Pay account is recognised when watched, and a member
  whose key is held here sends a plain transfer through its Delay module,
  queued first and executed after the cooldown; the current mastercopy only,
  no relayer (#305, #306, #309, #310).
- A fresh checkpoint when a network's checkpoint is too old: "Catch up…"
  offers to fetch one, taken when the checkpoint servers agree and a
  long-known peer confirms it, or, with no such peer in reach, only on the
  user's "Use it anyway", besides "Catch up anyway" (#301).
- ENS names: names resolved to an account are remembered and listed as NFTs
  once proven held, "Add an NFT" takes an ENS name alone, and an account's
  header shows the names pointing to it (#274, #299).
- Funding: a Sponsor button and a Funding section in the README (#280, #296).
- A privacy policy, `docs/privacy-policy.md`, linked from the README (#296).
- A security policy, `SECURITY.md`: how to report a vulnerability privately
  (#303).
- `docs/how-keys-are-protected.md`: how Punicus protects keys, in plain words
  with the code (#302).
- Gradle dependency locking and checksum verification: lockfiles for every
  project and the build logic, `gradle/verification-metadata.xml`, the Gradle
  distribution's SHA-256 in the wrapper, and the `gradle pins` check that
  fails a build on a dependency that is not pinned (#281, #298).
- This changelog (#281, #298).

### Changed

- The embedded Myotis engine is 0.1.21-rc1 (was 0.1.20); it brings the
  `myotis-logindex-presets` module to every app (#301).
- Kotlin 2.4.21 (was 2.2.21), with R8 9.1.57 and lint 9.5.1 on AGP 8.13
  (#312).
- Background re-proofs of different accounts are spaced out, one address at a
  time with a random gap, so they no longer reach a peer all at once (#253,
  #300).

### Fixed

- Watch address: an address already in the wallet shows that account on an
  "already in your wallet" card (#273, #296).
- An empty wallet runs its light clients from the start and shows them, so
  the first account added is not left waiting for a sync (#66, #296).

### Security

- Turning biometric unlock on checks that the hardware key exists first, so a
  lost Android Keystore key is reported instead of silently remade, which
  made the loss read as a wrong password later (audit finding N3, #307).

## [0.4.0] - 2026-11-10

The first release under the name Punicus.

### Added

- Lenient mode, off by default: prepare and sign a transaction while the
  light client syncs; nothing is sent before a check at a fresh head (#258, #269).
- NFTs: an account's NFTs below its tokens, found in its verified log index,
  with ENS names decoded (#246, #263).
- WalletConnect on every network: Ethereum and Gnosis Chain together, or
  Sepolia alone (#259).
- Android: scan a dApp's WalletConnect QR code, and open `serotine://wc` deep
  links (#245).
- Send from another wallet: hand a transfer to MetaMask or another wallet app
  as an `ethereum:` payment link (#255, #257).
- Settings: heavy downloads on a metered connection are opt-in; the history
  walk waits for them (#85, #272).
- Swap: the amount to receive and the rate are shown while composing (#237).
- Activity: every row is dated and ordered by date, the order can be turned
  round from the menu, and Safe transactions are decoded (#235).
- Settings shows a pre-release's version (#236).

### Changed

- The displayed name is Punicus, no longer Serotine (#276).
- Every known Safe release and proxy build is recognised on Ethereum, Gnosis
  Chain and Sepolia (#277).
- Swap: ETH for WETH and back is a wrap through WETH9, not a refusal (#238).
- The network stays on briefly after the app leaves the foreground (Android
  60 s, iOS 25 s) (#239).
- One WalletConnect project ID for debug and release builds (#244).
- Myotis engine 0.1.20 (#270).

### Fixed

- Activity: the network's log of an ETH transfer is shown as ETH, not as an
  unlisted token (#271).

## [0.3.0-rc1] - 2026-11-04

### Added

- dApps connect over WalletConnect v2, on Sepolia (#22, #231).
- Hide amounts: a closed eye at the top left masks every amount (#215).
- The Android release certificate is pinned (#214).
- A security-invariants check in CI (#217).

### Changed

- Send: the asset is chosen from a dropdown, not a radio list (#232).
- Myotis engine 0.1.20-rc1; account answers carried by block access lists
  are anchored (#233).

## [0.2.0] - 2026-10-31

### Added

- Networks: Ethereum and Gnosis Chain beside Sepolia, one chain per account
  (#145, #173).
- CoW Protocol swaps beside Uniswap; the user chooses the venue (#144, #156).
- Safe settings: add, replace or remove an owner, change the threshold,
  disable a module, remove the guard (#185).
- Accounts: add further accounts of a stored recovery phrase (#211).
- Touch ID unlock on a Mac's Secure Enclave (#157).
- Simple mode by default, with the detailed view opt-in (#210).
- GNO, COW and SAFE on every network they exist on, priced by a pinned
  Uniswap pool's 30-minute average where no Chainlink feed is pinned (#199,
  #205, #208).
- Built-in Sepolia tokens: EURC, PYUSD, WETH, GHO and Aave's DAI, USDT, WBTC
  and AAVE, each with an icon (#143).
- Rate widget: choose the network, the price and the currency; the watched
  asset's icon in its corner; a filter in its setup (#187, #200, #212).
- Account detail: Activity as a tab (Assets, Activity, Owners, Queue), the
  assets sorted from the top bar's menu, and Send, Swap and Receive on one
  line (#139, #142, #149).
- A watched account is named by the ENS name it was added by, and the name is
  kept checked (#138).
- Debug tab: a log of every Myotis API request, turned on in Settings (#146).
- Punicus's logo and app icons (#140).

### Changed

- Past the weak-subjectivity window, an answer is used only on the user's
  word, on every network (#175, #176).
- A balance or price is proved again only when a verified block may have
  changed it (#163).
- Safes deployed before 1.3.0 (1.0.0, 1.1.1, 1.2.0) are recognised (#198).
- The swapped assets are chosen from a dropdown menu (#159).
- Ghost rows hold a list's room while its rows are read (#152).
- Widget: a smaller refresh button beside the 24h change (#137).

### Fixed

- Debug filter: the cursor stays while typing, and the text across tab
  switches (#184).

## [0.1.0] - 2026-10-21

The first pre-release: a wallet for Ethereum accounts and Safe{Wallet} smart
accounts on Sepolia, on the embedded Myotis light client, for Android, desktop
(macOS, Linux) and iOS.

### Added

- Wallet UX for EOAs and Safe{Wallet} accounts (#77).
- In-app swaps on Uniswap v3, from an account and from a Safe (#99).
- Transaction history from the verified log index: a Safe's events and every
  token transfer (#117), and the transactions this device sent, kept with how
  each ended (#102).
- Activity: a row opens its detail, with every address and hash to copy, and
  Add token for an unlisted ERC-20 (#136).
- The last verified balance is shown, dated and kept across restarts, and the
  light-client strip is coloured by what it can verify (#84); the strip is on
  every screen that depends on it (#119).
- Watch address: an ENS name is accepted, resolved with verified calls (#121).
- Warnings before a network upgrade this version does not support (#23, #30).
- Android: a home-screen widget with the ETH rate and its 24h change (#135).
- The iOS app, on the embedded Myotis engine (#125, #131, #134).
- Settings: a debug-build switch that allows screen recordings (#132); the
  engine's own log in the debug builds (#129).
- Safe transactions that remove the fallback handler carry a warning (#113).

### Changed

- The Assets screen is named Accounts; a tap on an asset row opens Send (#124).
- A Safe's check is retried within a budget and made before a key is asked
  for (#81, #82).
- Form buttons stay reachable above the on-screen keyboard (#127).
- Control badges: the icon beside the text, the icon alone where the text
  would wrap (#128).

[Unreleased]: https://github.com/VesperBats/punicus/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/VesperBats/punicus/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/VesperBats/punicus/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/VesperBats/punicus/compare/v0.3.0-rc1...v0.4.0
[0.3.0-rc1]: https://github.com/VesperBats/punicus/compare/v0.2.0...v0.3.0-rc1
[0.2.0]: https://github.com/VesperBats/punicus/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/VesperBats/punicus/releases/tag/v0.1.0
