---
description: "Trust. Everything about the chain is verified by the embedded Myotis engine or shown as not available; the Safe refusals; network-upgrade warnings; a parameter is applied or refused; what a dApp sends; what a clear-signed line proves; key material and how keys are held."
paths:
  - "domain/**/*"
  - "data/**/*"
  - "ui/**/*"
  - "android-app/**/*"
  - "desktop-app/**/*"
  - "ios-app/**/*"
  - "native/**/*"
  - "*.kts"
  - "docs/**/*"
---

# Trust

Loaded for every code module and for `docs/`. These rules bind every change.
docs/wallet-ux.md applies them to the screens. docs/keys-and-signing.md is
the contract for keys.

## Verified, or not available

- **Peer-trusted is never an option.** Everything the wallet shows about the
  chain is cryptographically verified by the embedded Myotis engine, or shown
  as not available. The engine's only trust anchors are sync-committee
  signatures and its embedded accumulators, and, in the Myotis releases that
  carry its rule D1 (owner's decision there, 2026-11-20, Myotis #900), its
  bundled checkpoint table: the hash of every 1024th block, which verifies a
  block deeper than the light client's window.
- No JSON-RPC provider, no indexer API and no "temporary" fallback supplies
  chain state, in any build type. One bounded exception, below: the addr1
  index's rows, each verified before it is shown.
- **One older answer is shown, labelled as one** (owner's request,
  2026-10-19; docs/wallet-ux.md). While no verified head is fresh enough to
  read at, a balance the light client proved earlier (in this run, or kept
  across a restart) stays on screen: dated, marked as possibly changed since,
  left out of totals and never acted on. The one exception is lenient mode,
  below: a draft may be built and signed on it, labelled, and is sent only
  after the check at a fresh head (owner rulings, 2026-11-06).
- **In simple mode a value may be read a while ago, and says how long**
  (owner's decisions, 2026-10-26; docs/simple-ui.md). On a network without
  block access lists (EIP-7928), simple mode proves a balance, and the price
  it is valued at, when its screen opens and on pull-to-refresh, not at every
  head. While the light client verifies, such a balance is shown with its age
  and counts in totals, at the price read with it, for up to an hour. Older,
  it is dimmed and left out of totals, as above.
  Detailed view reads at every head, as before. In strict mode, a quote, a
  review and a signature always read fresh state, in either view. In lenient
  mode a review and a signature may read the state last verified, labelled,
  and the check before sending always reads fresh state (owner rulings,
  2026-11-06).

## Lenient mode: sign early, never send early

(Owner rulings, 2026-11-06, on #258, accepting every proposal of
docs/lenient-mode.md, which has the detail.)

- **A switch, off by default.** Settings → Advanced → *Sign while syncing*,
  one for the app, off in every build. Off is strict mode: everything
  refused while syncing stays refused, as before.
- **Nothing leaves the device before a check at a fresh verified head:** no
  broadcast, no CoW order, no Safe QR code or text, no `approveHash`, no
  execution, no answer to a dApp. Only the signature moves earlier.
- **A draft is built on what the light client verified last**, each value
  labelled with its age: the balances, the account's count of mined
  transactions and the network's fee quote, kept across restarts; for a
  Safe, its nonce, owners and threshold. A pinned gas ceiling per kind of
  call stands in for the estimate. A draft has no simulation, no ENS name
  and no fiat value. With nothing kept, the draft waits.
- **The check makes the strict quote again, fresh, and compares.** The
  quote must be made, enough of the asset included. Only its own test of the
  fee is left out: the funds test below replaces it. The nonce must be the
  one signed. The fresh estimate must be within the signed gas limit.
- **The check's fees and funds use the signed figures.** The signed fee cap
  must cover 1.125² times the next base fee, plus the signed tip. The fresh
  balance must cover the signed transaction's upfront cost (its value plus
  its gas limit times its fee cap) and what the account's transactions not
  mined yet may take. A peer tests the signed bytes, not a fresh quote.
- **A conflict deletes the signed bytes** and asks for a new signature.
- **A held Safe signature is checked before it is shown to another owner:**
  the Safe verified as a Safe, its `SafeTx` nonce not used yet, the signer
  a current owner. A signer that is no longer an owner is told so, and the
  request is discarded.
- **The outbox holds one queued transaction per account.** It is sent by
  itself once it checks out, within an hour of its signature; later, only
  on the user's tap.
- **The outbox runs while the app runs**, and during Android's grace period.
  It says when it waits for the app to be opened.
- **Queued signed bytes are neither public data nor key material.** Whoever
  has them can broadcast them, and they move only what the user signed.
- **They are sealed:** an app-private file of their own, sealed with a
  hardware key of their own that needs no authorization
  (docs/keys-and-signing.md). Never logged, never in a UiState. Where
  Punicus has no hardware key to seal with, they are kept in memory only. A
  lost seal key loses the queued transaction, and says so.
- **Strict in both modes:** swaps and dApp requests. Wraps and Create Safe
  stay strict for now. The Safe refusals below apply when drafting.
- Turning the switch off leaves what is queued queued: it is checked and
  sent, or discarded by the user.
- **A network that is off is not syncing.** Its actions stay greyed out with
  "Turn on" as the way to get them, in either mode: nothing is drafted on
  it.

## The addr1 history: every row verified, its completeness on the importer's word

(Owner ruling, 2026-11-21, #334, and the owner's decisions of that day on
#335; `.claude/rules/data-sources.md`, *The history may also take the addr1
index from Swarm*.)

- An addr1 entry is shown only once the engine verified it against verified
  chain data. For a block deeper than the light client's window that rests on
  Myotis's checkpoint table (rule D1, above). Nothing about the chain is
  taken from the index's word.
- What the index alone vouches for is that no entry is missing: the
  completeness of the list. That rests on the importer key pinned in the
  release, and the list says so. A contradicted entry is a warning against
  that key, said in plain words.
- When the engine raises its checkpoint table's alarm (the table and the
  beacon chain disagree, Myotis's `StatusSnapshot.checkpointAlarm`), every
  read resting on the table is refused. The app shows the alarm, and what
  is kept of the lists (below) is deleted: for now when a read meets the
  alarm, as for the rows shown; #363 brings the alarm itself into the app.
- **A row verified earlier may be shown again without a new check** (owner's
  request, 2026-11-25: "The results will never change. So it's ok to store
  them locally"). How it is applied, not part of the request: only a row of
  a block the beacon chain had finalized when it was verified, since a
  finalized block cannot change, and neither can the engine's verdict on
  it. The index is read again at a block whose verdict was not final yet.
  A row's detail still checks its entry anew.

## Past the weak-subjectivity window only on the user's word

(Owner's request, 2026-10-24.)

- A light client whose last trusted checkpoint is older than its network's
  weak-subjectivity bound refuses to sync. The bound is the engine's
  default (Myotis's `NetworkConfig.wsBoundPeriods()`), in sync-committee periods:
  13 on Ethereum and Sepolia, about 15 days of 27.3-hour periods; 3 on
  Gnosis Chain, about 34 hours of 11.4-hour periods.
- Accounts warns of it in that network's section, in plain words: what
  catching up has to trust, and what a forged chain could and could not do.
- Only the user's confirmation lets it catch up (the engine's
  `acceptStaleAnchor`). Per network, and for that run of the app alone:
  never automatically and never stored. A restart that still finds the
  checkpoint too old asks again.
- The bound itself is never raised: `setWsBoundPeriods` is not called.

## Or from a fresh checkpoint the checkpoint servers and a long-known peer agree on

(Owner's request, 2026-11-14: "In case of a weak subjectivity window expiry
there should be now the option to find a new checkpoint". It applies Myotis's
carve-out of 2026-11-10 and its rulings of 2026-11-11 and 2026-11-13 on #761;
`.claude/rules/data-sources.md`, *Checkpoint servers*.)

- The same warning offers a second way on: **Fetch a fresh checkpoint**, the
  engine's `refreshAnchorFromCheckpointz`. It is offered first and called
  recommended, since it starts from a recent checkpoint instead of walking
  forward from an old one.
- Only on the user's tap, and only while that network is parked on a stale
  checkpoint. Never automatically, never remembered for now: the engine's
  `setStaleAnchorPolicy` is not called.
- The engine takes a checkpoint only when every checkpoint server that
  answers names the same finalized epoch and root, run by at least two
  operators, and a long-known peer (pinned in the release, or known to this
  device for a week) names it too. One server that differs refuses it, and
  the outlier is named; the screen shows the engine's reason.
- **That checkpoint is the servers' word**, as the embedded one is the
  release's. The dialog says so before the tap, and the outcome names the
  agreeing operators and how many long-known peers confirmed it. Everything
  synced from it is verified as usual.
- When the servers agree but no long-known peer could be reached, the
  dialog says an attack on the connection cannot be ruled out and offers
  **Use it anyway**. That is the only call with `allowUnconfirmed`, asked
  again every time and never remembered.

## A Safe's fallback handler is set only to a pinned one

(Owner ruling, 2026-10-18, #63.)

- A Safe transaction that sets a fallback handler other than the pinned
  canonical CompatibilityFallbackHandler 1.4.1 of its network, or sets none
  (the zero address, which removes it), is shown and never signed, approved
  or executed here.
- Why: the handler answers EIP-1271 signature checks for the Safe. One that
  accepts every signature lets anyone spend what the Safe has approved.
- A guard removed and a module disabled are signed with a warning, not
  refused.
- A call of the Safe itself that is not decoded exactly (a settings change,
  or the empty zero-value rejection) is refused whatever its selector, since
  on chain it could still set the handler.

## Enabling a Safe module is refused

(Owner ruling, 2026-10-18, on #54: play it safe.)

- A Safe transaction that enables a module, whatever the module, is shown
  and never signed, approved or executed here. A module moves the Safe's
  funds without its owners.
- Disabling a module keeps its warning and is not refused.
- Which modules may be enabled is still open (#54). A temporary, warned
  "dangerous mode" past it is planned in #78 and not built.

## A Gnosis Pay account sends through its Delay module

(Owner's decisions, 2026-11-15; docs/gnosis-pay.md is the contract.)

- **Recognised on chain.** A Safe on Gnosis Chain is a Gnosis Pay account
  when one of its enabled modules is a Delay modifier: its code, proven,
  byte for byte the minimal proxy of a pinned mastercopy, and its avatar,
  target and owner the Safe itself, read with verified calls at one block.
  It is detected on a Safe added to the wallet; no other way in.
- **A member sends.** A member of that Delay (a module enabled on it) whose
  key is held here, as an account on Gnosis Chain, may queue a transfer for
  the Safe and then execute it. Both are its own transactions, each quoted,
  simulated and signed like any other, and it pays both fees.
- **So does a key that owns a member Safe** (owner's request, 2026-11-17).
  Members are followed down through Safes to the keys, each address's kind
  proven (a key held here is taken as a key, unread), at most three Safes
  deep and 32 addresses per Delay. A key held here whose every Safe on the
  way needs one signature controls the account by itself: the account shows
  Full control, and the key queues through those Safes, each executing the
  call with `execTransaction` as its one owner's transaction (no value,
  CALL, no refund, a pre-validated signature of the owner that calls it).
  A Safe of a higher threshold on the way makes the key a co-signer only:
  nothing is sent. What is not read is never taken for no key.
- **A verified answer stands over a read that did not answer** (owner's
  decisions, 2026-11-17; docs/gnosis-pay.md, *A read that did not answer*):
  the Safe's state, its Delay's and each address's kind on the way to the
  keys stay as last verified, and are said to be, while a later read only
  found no proof or the light client catches up. For an hour of the app
  running at most; a verified answer that differs, or a reason that stands,
  replaces it at once. No transfer is planned on a held Safe or Delay.
- **Only plain transfers**, and the two calls below. The Safe's xDAI as the
  value with no data, or an ERC-20 `transfer` with no value; operation CALL;
  never to the Safe, its modules or the Delay. Anything else is refused.
- **A member of the Delay is added or removed the same way** (owner ruling,
  2026-11-18; docs/gnosis-pay.md, *Adding and removing a member*). It amends
  *Only plain transfers* and "it enables none" below for this one case:
  - Only on the account's own Delay of the current mastercopy, by a key that
    controls it here (as for Send: held here and acting alone), queued, then
    executed after the cooldown exactly like a transfer, with the same queue
    checks before the execution. The call is the Safe's own call of its
    Delay: `enableModule(member)` or `disableModule(previous, member)`, no
    value, operation CALL.
  - **Add.** A new member can move all the account's funds after the
    cooldown; the review says so in plain words. Refused: the zero address
    and `0x…01` to `0x…ff` (the sentinel among them), the Safe itself, the
    Delay, any of the Safe's enabled modules, and an existing member.
  - **Remove.** Refused for the last member: the Safe's one owner is a
    placeholder, so with no member its funds are locked for good. Warned, not
    refused, when the member removed is the way this device controls the
    account (the held key itself, or the member Safe it reaches the Delay
    through): after its execution this device can no longer send from the
    account, unless another way remains.
  - **Never through a Delay whose entries never expire** (owner's decision,
    2026-11-18, on #323): with an expiration of 0, adding or removing a
    member is refused, at the queueing and at the execution, and both are
    greyed out saying why. A change that can no longer run there (one of
    the members queued elsewhere ahead of it) would stay the queue's next
    entry for good, since nothing skips an entry that never expires, and
    hold every entry behind it back. Transfers through such a Delay stay
    allowed.
  - Nothing else beyond plain transfers and these two calls is ever queued,
    but for the card limit signed for Gnosis Pay's relayer (below).
    *Enabling a Safe module is refused* (modules on the Safe itself) stands
    unchanged.
- **The current mastercopy only.** `0x22d9…1ef0`, deployed after the exploit
  of 2026-06-01, is sent through. The retired `0x4A97…F9f5` is recognised,
  and Send is greyed out, saying why.
- **The queue is checked before an execution.** The transfer must be the
  Delay's next entry, past its cooldown and before its expiration. An entry
  ahead of it is shown and never executed by Punicus.
- **An expired entry is skipped with the Delay's own `skipExpired()`**
  (owner's request, 2026-11-18; docs/gnosis-pay.md, *Skipping expired
  entries*): a key held here sends it straight to the Delay, after a fresh
  read proves the Delay the Safe's own of the current mastercopy and its
  next entry expired. It moves nothing, so it needs no control of the
  account, and it queues nothing.
- **No relayer.** The Delay's signed, relayed path needs an off-chain
  service and is not used.
- **One exception: Gnosis Pay's card limit, signed for its relayer**
  (owner's request, 2026-11-27: "This is a Gnosispay extend daily card limit
  to 8000€. Can you add support for this?"). Asked by a dApp over
  WalletConnect, a member's `ModuleTx` signature for the Delay is signed only
  when its call is exactly the Roles `setAllowance` under
  `SPENDING_ALLOWANCE`, a plain call of neither the Delay nor the Safe, on
  Gnosis Chain, and the Delay is verified as the current mastercopy's, of a
  Gnosis Pay account in this wallet, with the signer one of its members.
  Punicus contacts no relayer: the dApp sends it. Every other `ModuleTx` is
  shown, decoded, and refused.
- **The card's allowance is never a cap.** The Delay's cooldown and
  expiration are the limits. The Roles allowance binds Gnosis Pay's spender,
  not the member; it is shown, display-only, once where today's accounts keep
  it is known.
- This uses a module already enabled. It enables none on the Safe, and
  *Enabling a Safe module is refused* stands. On the Delay it enables a member
  only as the owner ruling of 2026-11-18 above allows.

## A Swarm node's chequebook and postage batches

(Owner's decisions, 2026-11-23: a node's account is watched, the four
actions below, and how a chequebook and a batch are found. The detail is
docs/swarm.md's, which is the contract.)

- **A node's account is watched.** No `swarm.key` is imported, and the
  Swarm screens import no key. Where the node's address is an account held
  here, its key signs what only the node may sign. Its key may be imported
  in hex on the import screen like any key (owner ruling, 2026-11-25,
  amending "no raw key is imported" of 2026-11-23).
- **Recognised on chain.** An EOA on Gnosis Chain is a Swarm node when a
  chequebook or a postage batch is proven its own, with verified calls at
  one block. A chequebook: its code hashes to the pinned EIP-1167 proxy of
  the pinned master copy, the pinned factory's `deployedContracts` says it
  deployed it, its `issuer()` is the node and its `token()` is BZZ. A batch:
  the pinned PostageStamp's `batches(id)` names the node as its owner.
- **Four actions, and nothing else**, each a transaction from an EOA whose
  key is held here on Gnosis Chain, quoted, simulated and signed like any other, to the
  pinned contracts only, after a fresh read proves their code:
  - **Buy a batch** for the node: any such EOA pays, the node owns it.
    `approve` of exactly the total, never unlimited, then `createBatch`.
  - **Top up a batch**: any such EOA pays. `approve` of exactly the
    total, then `topUp`.
  - **Dilute a batch** (`increaseDepth`): the node's own key only. The review
    shows the time to live halving with each step.
  - **Withdraw from the chequebook** (`withdraw`): the node's own key only,
    to the node's own address, up to the liquid balance. The review warns
    that uncashed cheques may bounce, which marks the chequebook for good.
- **The node's own key is also Bee's.** Where it signs, the review warns to
  stop Bee first, and nothing is sent when the key's verified mined count
  moved since the quote by a transaction not sent from here.
- **Strict in both modes**: none is drafted in lenient mode for now.
- **No cheque is cashed and no Bee node is called.** Cheques live in Bee,
  off chain. Staking and the redistribution game are not part of this.

## Network-upgrade warnings are peer reports, and say so

(Owner's request, 2026-10-17: warn ahead of a breaking hard fork, notify even
while the app is closed, and after it do nothing but ask for the update.)

- The engine's upgrade advisory is derived from what peers announce and is
  not verified. It is the one thing the wallet shows about the network that
  the light client does not prove.
- It is never chain state: no balance, nonce or transaction depends on it,
  and nothing in verification reads it. Every text showing it says peers
  report it.
- Only the light client's own state may make the app claim it cannot follow
  the network. The update screen, which replaces every other, needs BOTH an
  activation peers confirmed (not merely an announced time passing) AND a
  light client that is not verifying (not synced, or a stale head). That is
  Myotis's rule for its hosts.
- On peers' word alone the app shows a banner and notifications, never that
  screen (docs/embedding-myotis.md, *Network upgrades*).

## A parameter that can change the answer is applied or refused

(Myotis rule, learned the hard way in its #314.) Never accept a parameter and
silently ignore it. A well-formed result computed against something other
than what the caller asked for cannot be told from a correct one. Every
port's KDoc says what each argument does. An adapter that cannot honour one
fails the call.

## What a dApp sends is untrusted input

(Owner rulings, 2026-11-03, #22; docs/walletconnect.md.)

- **A closed list of methods:** `eth_sendTransaction`, `personal_sign`,
  `eth_signTypedData_v4`, and `wallet_switchEthereumChain` to a network the
  session holds. Every other method gets an unsupported-method error. A
  switch to a network outside the session, from testnet to real funds above
  all, gets the unrecognized-chain error (owner ruling, 2026-11-05, on #22).
- **`eth_sign` is refused in every case.** It signs a raw digest the dApp
  chose, which is what phishing asks for. A digest Punicus signs for a dApp
  is one it computed itself: EIP-191 for `personal_sign`, EIP-712 for typed
  data.
- A request is parsed strictly, with size limits. One that names another
  chain, or a `from` or an address that is not one of the session's accounts,
  is refused, never ignored.
- **The review shows only what Punicus works out itself:** the calldata
  decoded for the ABIs it knows and the built-in ERC-7730 descriptors, bound
  to verified code; the descriptor's words labelled as its claim, every
  parameter still shown; anything else raw and marked as not decoded; a
  simulation through the verified `call` and gas estimate (owner's
  decisions, 2026-11-25, #240, widening "the ABIs it knows" of 2026-11-03);
  and the verified trace at the head, beside them (owner's decisions,
  2026-11-26, #240; *A verified trace*, below).
- **A dApp's `eth_sendTransaction` is answered only with an Ethereum
  transaction hash.** For a Safe, the hash of the `execTransaction` sent,
  never the `safeTxHash`. A request that cannot be met before it expires gets
  an error.
- A dApp's Safe transaction keeps every Safe refusal above.
- **No key reaches the WalletConnect layer.** A local signature goes through
  the signer port.
- **Session secrets are not key material**, since they cannot move funds:
  an app-private file, out of backups and device transfers, never logged,
  and not under the wallet password.

## A clear-signed line: the raw values proven, the rest the descriptor's claim

(Owner's decisions, 2026-11-25, #240: built in, bound to code, a contract
changed after the release not decoded, reviewed in our PR;
docs/clear-signing.md, *What a clear-signed line proves*.)

- **Bound to verified code.** A built-in descriptor applies only when the
  request's network is the pin's, its `to` (for typed data, the domain's
  `verifyingContract`, with a `chainId` that is the network) is a pinned
  deployment, and the code there, read with a verified `getCode` at the
  verified head, hashes to the pinned hash. Where that code can change (a
  proxy's slot, a forwarding call), the pinned pointer holds the pinned
  implementation, whose code hashes to its own pin.
  ERC-7730 itself binds by address alone; that was declined.
- **A contract changed after the release is not decoded**, and the review
  says why, until a release moves the pin. Any other failed step is not
  decoded either, with its reason. Never a partial display.
- **A nested call is bound the same way**: its callee passes every step
  itself, or the call stays raw inside its parent.
- **The raw values are what is signed**, decoded here from the exact bytes,
  the ABI strictly (lengths and offsets checked, no trailing bytes).
- **The words and the formatting are the descriptor's claim**: its intent,
  its labels, its owner's name (stated in the file, not proven), and how a
  value is formatted (the token an amount is in, a unit, an enum's names, a
  date's encoding). Shown as described, reviewed for this release, with an
  auditor's attestation where one exists once a later part checks it (none
  is shown yet).
- **A descriptor hides nothing.** Every parameter no field shows, and every
  field it marks as not visible, is listed raw under "Other parameters".
- **The refusals come first.** Every Safe refusal (`SafeTxRefusal`;
  docs/wallet-ux.md, screen 17), those above among them, holds whatever a
  descriptor says, and an approval is flagged loudly whatever its
  description, at any depth.
- How it is applied, not part of the decisions (the owner's to approve on
  #378):
  - A formatted value keeps its raw decoded value beside it, in the detailed
    view at least. Simple mode keeps the "described by" label.
  - A token outside the built-in list is shown by its address, its amount
    raw, never by its own symbol, as the dApp review's hand-written decoders
    do today; so is an NFT collection that is not a built-in one, never by
    its own `name()`.
  - Calls nested deeper than three are not read, so the whole call is not
    covered; a nested call out of the contract has no value but what its
    bytes say.
  - The binding's reads are each verified at a fresh head (docs/clear-signing.md,
    A4, step 3), a call pin held to the block the code was proven at; a
    library declared for an implementation is pinned at build time only
    (owner's decision, 2026-11-25, on #382, replacing "all read at one
    block").
  - The hand-written decoders take precedence by type: where one claims a
    struct type or a selector (`TypedDataMeaning`'s Permit, Permit2 and CoW
    order, Safe's `SafeTx` and `SafeMessage`, Smart Sessions' grant,
    `Erc20`'s `transfer` and `approve`, the token calls of `TokenCalls`,
    SwapRouter02's swaps, Safe's own methods), its answer stands, "not
    decoded" included.

## A verified trace: what the call does at the head

(Owner's decisions, 2026-11-26, on #240: questions 6 and 7;
docs/clear-signing.md, *Phase B*.)

- **Run by the engine on verified state.** A dApp's transaction (D6), and a
  Safe transaction's call (screen 17, as the Safe's own call), is traced with
  the engine's `debug_traceCall` at the verified head, and its effects shown
  beside the description and the simulation. No other source traces it.
- **Proven at the head, never at inclusion**, and labelled so.
- **Token movements and rights come from the trace's logs**, the network's
  currency from the call tree; exact balance changes (`diffMode`) come later.
- **A description and a trace that disagree are warned about loudly; the call
  stays signable.**
- How it is applied, not part of the decisions (the owner's to approve on
  #240):
  - A frame counts only when neither it nor a frame above it failed; a
    delegate call moves no value of its own.
  - What a contract does not log is not seen: a clean trace proves nothing
    more than what was logged.
  - A token outside the built-in list is named by its address, its amount
    raw, as for a description.
  - The trace is held against the call's claims: its description, or a
    hand-written decoder's meaning. The disagreements checked, and how a
    right granted is told from an allowance spent (by `msg.sender` where the
    log was emitted): docs/clear-signing.md, *Phase B*.
  - Not traced is said so, never guessed: no tracing engine (iOS for now), no
    fresh head, or past the wallet's 30-second wait.

## Send from another wallet signs nothing here

(Owner rulings, 2026-11-05, #255.)

- A watched EOA may hand a transfer to another wallet app as a payment link.
  Watched still means Punicus signs nothing: the other wallet must hold the
  key, and the screen says so.
- Nothing the other wallet does is taken on its word. A transfer counts only
  when a verified block holds it: from the account, to the recipient, that
  amount. Another amount, or the same from another address, is said as such
  and never counted as the account's.

## Key material

An EOA's private key, a seed, a Safe owner key: it lives in the key-store
adapter and nowhere else. Never in a UiState, a log line, a `toString()`, a
crash report, or any serialized form that leaves that adapter. Signing is a
port that takes a request and returns a signature. The UI never touches a key.

### The exceptions are a closed list

Each exception is an owner ruling, dated here, with its conditions in
docs/keys-and-signing.md. A case not on the list follows the rule. Adding one
is the owner's decision, never a PR author's. Today the list has two entries:

- **The mnemonic screens** (owner ruling, 2026-10-15): the backup words shown
  at creation, and the words typed at restore.
  - They travel only as a dedicated mnemonic type with a redacted
    `toString()`, along one path: from the adapter through a use case and
    the ViewModel into the seed UiState at creation, and back the same way
    at restore.
  - Nothing on that path stores, logs or caches them.
  - The UiState is cleared when its screen is left. "Left" means: the app
    goes to the background, the screen turns off, or the screen is popped.
    A configuration change (a rotation, a fold, a resize, a theme or font
    change) is not a leaving: the words stay in the ViewModel's memory
    across it, never in a saved state or a Bundle (owner ruling,
    2026-10-18, #39).
  - Everything the adapter derives from the words, the seed and the keys,
    stays under the rule above.
  - Open, the owner's to decide: whether Create account, one screen using
    both legs of the path, is within the entry (#40).
- **A private key typed in hex** (owner ruling, 2026-11-25), on the import
  screen, instead of a seed phrase; docs/keys-and-signing.md, *Importing a
  private key*.
  - It travels only as the dedicated `PrivateKey` type with a redacted
    `toString()`, one way: typed on the import screen, through its ViewModel
    and a use case, into the key store. It never goes back: it is never
    shown or exported.
  - Nothing on that path stores, logs or caches it. The input is cleared
    when the screen is left, as the words are (the entry above).
  - The key store checks it (64 hex digits, an optional `0x`, a valid
    secp256k1 key) and seals it in an envelope of its own under the wallet
    password and the device's hardware secret, as every key is.
  - Such an account has no seed: New account from the same phrase and
    per-app addresses are greyed out for it, a tap saying why.
  - For per-app addresses (owner's decision, 2026-11-25, on PR #376): a
    per-app address is made from a phrase the user picks, not from an
    account, so a bare key is left out of the phrases offered, not listed
    greyed out. Where no phrase is held, the dApp connection's new per-app
    address is greyed out, its reason naming the keys imported on their own.
    New account from the same phrase is greyed out on the account itself.

### Privacy Pools note secrets are key material

(Owner rulings, 2026-11-11: D2 and D11 on #288, D11 amended on #290;
docs/privacy-pools.md; docs/keys-and-signing.md, *Privacy Pools note
secrets*.)

- **New deposits take Kohaku's derivation**: the key at
  `m/28784'/1'/<accountIndex>'`, a note's secrets from its EIP-712 signature
  (docs/kohaku.md, *Privacy Pools note secrets*).
- **Notes of 0xbow's derivation are found and spent too** (D11 amended,
  2026-11-11): the notes privacypools.com made from the same phrase, under
  its current and its legacy keys. Their change notes keep 0xbow's
  derivation, so its app still finds them, and always under its current
  keys: the change of a legacy note goes to the current keys at index 0,
  as 0xbow's own migration does. No commitment is written under the legacy
  keys, and no new deposit takes 0xbow's derivation.
- **0xbow's legacy keys are weak** (about 53 bits of each key survive its
  float conversion). A legacy note is shown as such, and the user is offered
  to move it.
- **This key material, of both derivations,** exists only inside the
  key-store adapter, inside one call, as byte arrays zeroed before it
  returns, and in the native library that call hands it to: Kohaku's signing
  key and note signatures; 0xbow's two account keys (`m/44'/60'/0'/0/0` and
  `m/44'/60'/1'/0/0`) and its master nullifier and secret, current and
  legacy; and every note's nullifier and secret. They are not an exception
  to *Key material*: they follow it.
- **Proving runs inside that call**: the adapter hands the secrets to the
  native prover (docs/architecture.md, *Native code*).
- **The native library zeroes every buffer of its own that held a secret**,
  Poseidon's inputs and the witness alike, and never logs an input. Copies a
  third-party prover keeps are checked in Part 2 and, where not zeroed,
  said in docs/keys-and-signing.md.
- **Only public values leave the adapter**: a precommitment, a nullifier
  hash, a commitment, a finished and verified proof.
- **Those public values may be cached**, so that discovery runs without an
  unlock. They are not key material, and the cache never holds a secret.
  - The cache is an app-private file, out of backups and device transfers,
    never logged.
  - It tells more than any one value: it ties each withdrawal to the deposit
    it spends, the one link the pool hides, and recognizes the next ones.
  - **It serves discovery only.** A deposit's precommitment is derived
    inside the unlock that signs that deposit; a deposit carrying another
    one is not signed.

## How keys are held

**docs/keys-and-signing.md is the contract** (owner decisions, 2026-10-15).
This is the summary a reviewer holds a diff against.

- **Two factors, both necessary.** No platform holds secp256k1 in hardware,
  so an EOA key or a mnemonic-derived owner key is a software key wrapped at
  rest. On the always-present password path BOTH factors must be
  cryptographically necessary to unwrap it: the Argon2id-stretched password
  AND a secret only this device's hardware key store yields. A password that
  is merely checked, or a wrap that opens without the hardware, is a finding,
  whatever it saves. The desktop cases below, by dated rulings, stand on the
  password path alone.
- **Biometric unlock** of that envelope is the user's opt-in (owner ruling,
  2026-10-15), bound to a key and never to the UI: a second hardware key with
  per-use Class 3 biometric authorization. Disabling it deletes that key.
  Weak biometrics never qualify.
- **The plaintext key exists only inside the key-store adapter**, creation
  and import included (the mnemonic words leave it, and a typed private key
  reaches it, only under the exceptions above). In signing it exists only inside the call, in native code, as a
  byte array zeroed afterwards. The ciphertext is excluded from cloud backup
  AND from device-to-device transfer.
- **A Safe may be owned by a hardware P-256 key directly.** Such a key is
  never the sole owner of a Safe holding value: a recovery owner or a
  recovery module is part of creating the account. EOAs are secp256k1 only.
- **Key storage closes the theft path only.** The hardware signs whatever
  digest the app hands it. For a Safe holding value the app's own preview is
  never the sole check; the policy lives on chain and in an independent
  co-signer, as the doc sets out. No dApp browser runs in the signing
  process.
- **An imported mnemonic's envelope holds its BIP-39 seed**, so further
  accounts can be derived without the words (owner ruling, 2026-10-15).
- **Argon2id's cost, where the password stands alone**, is sized by the
  device's memory (owner ruling, 2026-10-18, #36): 256 MiB × 4, 128 MiB × 8
  or 64 MiB × 16, about the same time each. Android starts at the tier of
  its total memory and low-RAM flag; the desktop starts at 256 MiB × 4. A
  seal whose memory cannot be had steps down a tier, on any device, never
  below the 64 MiB floor. The user is told when a lighter tier is used: a
  device's lighter tier with its protection, and a seal that stepped down
  when the key is stored. An envelope keeps the tier it was sealed at.
  Argon2id short of memory is its own outcome (sealing, opening, signing),
  never "the key store does not work", and a failed seal stores nothing.
  The hardware path's 64 MiB × 3 and stored envelopes are unchanged
  (docs/keys-and-signing.md, *Costs*).
- **A desktop without a hardware key store** stands on the password path
  alone, with Argon2id run harder, and the app tells the user so.
- **A Mac with a Secure Enclave** uses it as iOS does (owner ruling,
  2026-10-15), the app signed or not (owner ruling, 2026-10-16). Its key is
  then bound to the Mac but not to the app. Binding it to the app takes a
  different design: the key in a signed app's keychain access group.
- **iOS's envelope waits for the iOS app** (owner ruling, 2026-10-16).
- **Windows and Linux stand on the password path alone for now**, a TPM
  present or not. The TPM path of docs/keys-and-signing.md's rule 9 comes
  later. Until then the app tells the user that it does not use the TPM yet
  (owner ruling, 2026-10-16).
- **Deleting an account's key needs no wallet password** (owner ruling,
  2026-10-18, #17): removing the account deletes its envelope, and the app
  asks the user to confirm the removal first (`RemoveAccountDialog`).
- **A Safe owner's key may live in an external wallet** (owner ruling,
  2026-11-03, #22), reached over WalletConnect. Its backup and its unlock
  are that wallet's; Punicus keeps only the address and the session. Safe
  owners only; an EOA's key in another wallet comes later or never. It is
  asked for `eth_signTypedData_v4` of the `SafeTx` alone, never a bare hash
  to sign blind, and its signature is kept only when it recovers, locally,
  to the session's account and that account is a current owner.
- **Open, the owner decides**, recorded here when made: the synced-passkey
  owner tier, hardware external signers (#42), and threshold signing with
  Myotis.
