# Clear signing: built-in ERC-7730 descriptors, bound to verified code

Before a dApp transaction, a typed-data message or a Safe transaction is
signed, Punicus says in words what it does: "Swap 1,000 USDC for at least
0.31 ETH on Uniswap, sent to you, until 14:05". This page is the contract for
how those words come about. It records the owner's decisions of 2026-11-25
(issue #240), and those of 2026-11-26 on phase B (*Phase B*), which
`.claude/rules/data-sources.md` (*The clear-signing descriptors are built
in*) and `.claude/rules/trust.md` (*What a dApp sends is untrusted input*,
*A clear-signed line*, *A verified trace*) carry as rules.

Today Punicus decodes only ERC-20 `transfer` and `approve`, Safe's own
methods, the Uniswap calls it builds itself, and the typed data of
`TypedDataMeaning` (Permit, Permit2, CoW orders). Everything else is shown raw
and marked "not decoded" (`DappCallMeaning.NotDecoded`). This page widens
that, and nothing it adds narrows what is already shown.

## The owner's decisions (2026-11-25, #240)

1. **Built in at build time.** The descriptors come from the ERC-7730
   registry. They are pinned, checked and converted at build time, and ship
   in every build. The app never fetches a descriptor: not from
   clearsigning.org, not from GitHub, not from a CDN. A fetch at signing time
   would tell a server which contract the user is about to use, and it would
   be a data source outside the engine. The precedents are the built-in token
   list (owner ruling, 2026-10-16) and the pinned Public Suffix List
   (`scripts/public-suffix/generate.py`).
2. **The first list is as proposed** (question 1): *The first list*, below.
3. **A contract that changed after the release is not decoded** (question 2).
   When the code at the address no longer hashes to the pinned hash, or a
   proxy points at an implementation other than the pinned one, the call is
   not decoded and the review says why, until a release moves the pin. Binding
   by address alone, as ERC-7730 itself does, was declined.
4. **Every shipped descriptor is reviewed in our PR** (question 3). An auditor
   attestation from the registry's `sigs/` is shown where one exists. No
   attestation from a named auditor is required.
5. **Descriptors of our own, later** (question 4). The contracts Punicus pins
   and the registry lacks (the Swarm PostageStamp and chequebook, the Gnosis
   Pay Delay module) get descriptors written here, in a part after phase A,
   and contributed upstream.
6. **A refresh is a step of every release PR** (question 5): the pins move
   through that PR, reviewed like code.
7. **This work item is phase A** (*Phase A*, below), on
   `feature/clear-signing`. The owner allowed the session that asked to merge
   the part PRs into it, once each is done under the Workflow (the
   feature-branch skill: the answer lives in that conversation, and a later
   session asks again). The PR into `main` is the
   owner's. Phase B was decided on 2026-11-26 (*Phase B*); phase C stays
   open (*Open decisions*).

## ERC-7730 in short

- [ERC-7730](https://eips.ethereum.org/EIPS/eip-7730) is a JSON descriptor
  per contract, or per EIP-712 message type, that says how each parameter is
  shown. It is a draft. The schema line Punicus takes is **v2** (2.1.0 when
  this was written). A descriptor whose `$schema` has a major version the
  runtime does not support is refused, at build time.
- Ledger started it. Since May 2026 the Ethereum Foundation looks after it,
  at clearsigning.org.
- **The registry** is ethereum/clear-signing-erc7730-registry, CC0-1.0:
  descriptors in `registry/<entity>/calldata-*.json` and `eip712-*.json`,
  shared `ercs/` files (ERC-20, ERC-721, ERC-2612 Permit), test vectors in
  `registry/<entity>/testsv2/*.tests.json`, auditor attestations in
  `registry/<entity>/sigs/`, the schema in `specs/erc7730-v2.schema.json`,
  and a linter, the `erc7730` Python package. In its own words: "Inclusion
  means only that a descriptor passed the registry's automated checks and
  basic review." It is not an audit.
- A descriptor has four parts: `context` (the deployments, or an EIP-712
  domain), `metadata` (the owner, constants, maps, enums), `display.formats`
  (keyed by the ABI fragment or the EIP-712 `encodeType`, each with an
  `intent`, an optional `interpolatedIntent` and its `fields`), and
  `includes` (other files merged in).
- A descriptor binds to a contract by chain id and address only. It knows no
  code hash. Punicus adds one (decision 3).

## What a clear-signed line proves, and what it does not

- **Proven: what is signed.** Every value shown is decoded here from the exact
  bytes being signed: the amounts, the addresses, the deadline. The code it
  calls is verified, at the verified head, to be the code the descriptor was
  pinned for.
- **The descriptor's claim: what it means.** The intent ("Swap"), the labels
  ("Minimum received") and the choice of fields are its author's words.
  Registry review is basic. A descriptor can be wrong, and a malicious
  contract can be described perfectly.
- **So the words are labelled.** The intent is shown as described by the
  name in the descriptor's `metadata.owner`, which its author wrote and the
  registry does not prove: "Described by <name>, as the descriptor states
  (ERC-7730 registry, reviewed for this release)", with the auditor's
  attestation where one exists. An attestation (`sigs/`, an EAS off-chain
  attestation over the descriptor's ERC-8176 hash) is shown only once its
  signature recovers to the auditor its file names and its hash is that of
  the descriptor shipped. Neither is checked yet, so no attestation is
  shown: a later part brings the check (the ERC-8176 hash computed at build
  time, the EAS signature recovered here). Revocation is not read.
- **How a value is formatted is the descriptor's claim too.** The raw words
  are proven; which token an amount is in (`tokenPath`, a constant `token`),
  a `unit`'s decimals and base, an `enum`'s names, a `threshold` word
  ("Unlimited"), a `date`'s encoding, and the ABI types the bytes are read
  as (tied to the contract by the 4-byte selector alone) are the
  descriptor's. So each formatted value keeps its raw decoded value beside
  it, in the detailed view at least.
- **Nothing is hidden for good.** Every ABI parameter that no field displays,
  and every field the descriptor marks as not visible, appears under "Other
  parameters" as raw decoded values. A descriptor orders and labels what is
  shown; it cannot remove anything.
- **The refusals come first.** Every Safe refusal (`SafeTxRefusal`;
  docs/wallet-ux.md, screen 17), among them the fallback handler and the
  module of `.claude/rules/trust.md`, a delegate call (#57), gas terms and an
  undecoded self-call, applies whatever a descriptor says. An approval
  (`approve`, `setApprovalForAll`, a Permit2 grant) is flagged loudly
  whatever its description.

## Phase A

### A1. Build time: pin, check and convert

`scripts/clear-signing/generate.py`, after `scripts/public-suffix/generate.py`:

1. **Pinned source.** A snapshot of the chosen descriptor files, committed
   under `scripts/clear-signing/registry/`, pinned by the registry's commit id
   and each file's sha256 in `PINNED`. A file whose hash differs is refused.
2. **Curated list.** `scripts/clear-signing/contracts.json` says which
   descriptor applies to which deployment, per network (Ethereum, Gnosis
   Chain, Sepolia). The registry's `deployments` are intersected with it:
   nothing outside it ships, even where a descriptor names more chains.
3. **Validation.** Each file is checked against the pinned
   `erc7730-v2.schema.json`, and by the registry's pinned `erc7730 lint`, in
   a CI job of their own (the app never runs Python; the linter reads
   Sourcify, and its warnings do not fail the job). Every selector is unique. Every key and value of a
   descriptor must be one the runtime implements (A3), not `format` and
   `params` alone, and each format has the parameters it needs. Every path,
   `…Path` parameter and placeholder must name a parameter of the function
   or the message, the container, or the descriptor's metadata; every field
   has a label, every descriptor an owner's name. A repeated key, or a string
   with anything outside printable ASCII, is refused, since a reader of the
   diff would not see it. Anything else unsupported fails the build, and is
   never dropped silently.
4. **Code hashes, and how the code can change.** For every deployment
   `contracts.json` pins the keccak256 of its runtime code, and records how
   the code it runs can change: not at all; through a named storage slot
   (EIP-1967's, the ZeppelinOS slot of Circle's `FiatTokenProxy`), with the
   implementation it holds and that implementation's code hash; or through a
   verified call (an Aragon `AppProxyUpgradeable` such as Lido's stETH asks
   its Kernel), with the value it must return. An empty EIP-1967 slot never
   means "not a proxy": code that can DELEGATECALL or CALLCODE (PUSH data and
   solc's metadata trailer skipped) and names no implementation is refused,
   and so is an implementation that can itself, unless `contracts.json` says
   by hand what it delegates to (a multicall into its own code, such as
   SwapRouter02's; linked libraries at addresses fixed in the code, such as
   the Aave v3 Pool's), which the PR's reviewer checks against the verified
   source. The script checks what it can of such a declaration: each library
   is an address the code pushes, its code is pinned, and it delegates only
   to libraries the declaration links it to; the declaration names the code
   hash it was read for, and a refresh refuses it once that code moved. A contract that forwards by CALL to a pointer it keeps (USDT's
   `deprecated`/`upgradedAddress`) is not seen this way, so it stays off the
   list until the script reads such a pointer. A deployment whose pointer cannot be named and read this way
   stays off the list. The values are read through Myotis, never from an RPC
   provider: `scripts/clear-signing/pin_code_hashes.py` asks a local
   `myotis-rpcd` (the Myotis repository's daemon over its verified light
   client, on loopback only) for the code, the proxy slots and the call, at
   one block, hashes the code here and checks it against the account proof's
   `codeHash`. The reviewer sees the values in the PR's diff.
5. **Output.** `data/.../clearsigning/ClearSigningCatalog.kt`, generated as
   `PublicSuffixRules.kt` is: the catalog as compact JSON with its sha256
   beside it, `includes` resolved, paths checked, every selector and EIP-712
   type hash computed. The runtime parses no ERC-7730 JSON from outside the
   build. Build and Test regenerates it and fails on a difference.
6. **Conformance tests.** The registry's `testsv2/*.tests.json` for the chosen
   files, vendored and pinned with them, become tests of the runtime
   formatter (A3). They run in `:data` (part 4), which reads JSON:
   `:domain` reaches JSON only through a port (`.claude/rules/architecture.md`),
   so its own tests write the descriptors out by hand. A descriptor the
   formatter cannot render as its own tests expect fails the build.

**Refresh** (decision 6): only through a PR that moves `PINNED`, the commit
id and the hashes, its diff reviewed like code, as a step of every release
PR (`.claude/skills/release/SKILL.md`, *3b. Refresh the clear-signing
descriptors*).
Never something the app does.

### The first list

(Decision 2.)

| Network | Contracts |
|---|---|
| Ethereum | the built-in tokens (with `ercs/calldata-erc20-tokens.json`), WETH, Permit2 (calldata and EIP-712), the Uniswap Universal Router and SwapRouter02, CoW's settlement contract and its EIP-712 order, Seaport (EIP-712), Lido stETH and wstETH, the Aave v3 Pool, the ENS ETH registrar controller, Safe's MultiSend and MultiSendCallOnly |
| Gnosis Chain | the built-in tokens, WXDAI, Permit2, CoW, and the Uniswap and Safe contracts deployed there |
| Sepolia | the contracts of Ethereum's row where they are deployed, for testing |

A contract the registry does not describe is left out, until the part of
decision 5 writes its descriptor.

**What part 2 ships** (registry commit `225ecc69`, 2026-11-25). The registry
describes less of the list than the plan assumed. How it is applied, not
part of the decisions:

- **Ethereum:** WETH, Permit2 (its `approve`, and its three EIP-712 structs,
  of which only `PermitBatch` applies, since `TypedDataMeaning` claims
  `PermitSingle` and `PermitTransferFrom`), SwapRouter02, Seaport 1.6, Lido
  wstETH, the Aave v3 Pool, and the ENS ETH registrar controller the
  registry names (`0x59E1…E547`).
- **Gnosis Chain:** Permit2 and the Aave v3 Pool.
- **Sepolia:** WETH and Permit2.
- **Left out, its code delegating where the pin cannot follow:** Lido's
  stETH. Its implementation can DELEGATECALL (Aragon's `EVMScriptRunner`,
  whose executor comes from a registry that can change), and no reviewed
  declaration says what. Its descriptor is not vendored.
- **Left out, not in the registry at that commit:** the Universal Router,
  CoW's GPv2Settlement (the registry describes only another deployment of
  it), Safe's MultiSend and MultiSendCallOnly, WXDAI, and Seaport's EIP-712
  order (`eip712-opensea.json` describes Wyvern). CoW's order stays with
  `TypedDataMeaning`.
- **The ERC-20 descriptor is not shipped**: its two functions, `transfer` and
  `approve`, are the ones `Erc20` already claims, so it could apply to
  nothing (*The hand-written decoders take precedence, by type*), and
  shipping it would mean pinning the code of every built-in token.

### A3. `:domain`: the model and a pure formatter

- **`ClearSigningDescriptor`**: the deployments with their pinned code
  hashes, the formats by selector or by `encodeType` hash, the fields, enums
  and constants. A plain immutable value, with no JSON.
- **`ClearSignFormatter`**, a pure function of the descriptor, the call
  (chain id, `to`, `from`, value and calldata, or the typed data) and lookups
  through ports: a token's metadata from the built-in list alone, as the dApp
  review's hand-written decoders name tokens today; an address's name from
  the built-in tokens and the pinned contracts (the wallet's own accounts, and
  ERC-7730's `wallet` type with them, are not wired yet: a later part); an
  address's verified kind; and the binding
  check of A4, for a nested call's callee.
  It returns `ClearSigned` (the intent, the interpolated intent, the fields
  shown, the other parameters, the nested calls) or `NotCovered(reason)`.
- **Strict decoding.** The ABI is decoded exactly: lengths and offsets
  checked, no trailing bytes, dynamic types bounded. An error is
  `NotCovered`, never a partial display. A call of fewer than four bytes
  matches no format: Seaport's `fulfillBasicOrder_efficient_6GL6yc` has the
  selector `0x00000000`.
- **The formats v1 supports:** `raw`, `amount`, `tokenAmount`,
  `addressName`, `date`, `duration`, `unit`, `enum`, `chainId`, `calldata`,
  `nftName` and `tokenTicker`; `visible` as a word (`always`, `never`,
  `optional`) or a rule: `ifNotIn` shows a field unless its value is listed,
  and `mustMatch` makes any other value `NotCovered`, never a field hidden.
  Refused at build time: the `interoperableAddressName` format, the
  `encryption` field attribute, an object intent, `display.definitions`,
  `metadata.maps` and a `factory` context, which binds by a deploy event
  rather than a listed deployment.
- **A token outside the built-in list in a `tokenAmount`** shows the raw
  amount and the contract's address, marked as not in the built-in list. Its
  own symbol is never shown as if it were real, as the dApp review and
  Activity's unlisted-token rows already do (#324). The same holds on D5 and
  D6 for hand-decoded and clear-signed calls alike.
- **A nested call** (`calldata`) is formatted with its own descriptor, up to
  depth 3, only when its callee passes A4's steps itself, read as its
  parent's are. Otherwise it stays raw inside its parent, with its reason;
  its bytes are shown either way. Past depth 3 the bytes are not read, so
  the whole call is not covered: an approval down there could not be seen.
  An approval is flagged at every depth, by its selector, covered or not:
  an `approve` or a Permit2 grant inside a batch is still an approval. A
  nested call keeps the user's sender and value only when it goes into the
  contract's own address and the deployment's reviewed declaration says
  that contract delegates into itself (a multicall, A1 step 4); any other
  is the contract calling out, and its value, which the bytes do not say,
  is unknown, never zero.
- **Arrays.** A parameter's path that walks the same array as its field
  (`tokenPath: details.[].token` beside `details.[].amount`) is read at the
  field's own item. A `bundled` group shows its children item by item and
  refuses arrays of unequal length; a `sequential` one shows each child
  over all its items. A group's label is shown over its fields.
- **Addresses.** An `addressName` shows the wallet's own name for an address
  beside the address, never an ENS name (`sources` other than `local` are
  not looked up); a value among its `senderAddress` reads "Sender", the
  decoded address kept as its raw value; an address whose verified kind is
  not among its `types` is flagged, and so is one whose kind could not be
  read in time ("not verified"), never taken for the kind expected.
- **NFTs.** An `nftName` names a collection only when it is a built-in one
  (`.claude/rules/data-sources.md`, *An account's NFTs*; none is yet, so
  every collection is shown by its address); any other is shown
  as "#id of <address>", marked as not built in, never by the name its own
  contract returns.
- **The hand-written decoders take precedence, by type.** Where one claims a
  struct type (`encodeType`) or a selector, its answer stands, "not decoded"
  included: `TypedDataMeaning` (Permit, Permit2, CoW; a GPv2 order with a
  fee is left undecoded on purpose; Safe's `SafeTx` and `SafeMessage`; Smart
  Sessions' `MultiChainSession`), ERC-20 `transfer` and `approve`, the token
  calls of `TokenCalls` (`transferFrom`, `increaseAllowance`,
  `setApprovalForAll`, ERC-721's and ERC-1155's `safeTransferFrom`,
  `safeBatchTransferFrom`; this takes Lido wstETH's `transferFrom` and
  `increaseAllowance` from its descriptor, the owner's to approve)
  (`Erc20`), the swap reads of SwapRouter02, and Safe's own methods. A
  descriptor covers only the types and selectors none of them claims.

### A4. `:data`: the binding check and the resource

A descriptor applies only when every step holds; otherwise it fails closed:

1. The chain id is the request's network. For typed data, the domain's
   `chainId` must be present and be that network.
2. `to`, or for typed data the domain's `verifyingContract`, is a pinned
   deployment on that network. A domain without `verifyingContract` has no
   code to bind to; a domain without `chainId` could be verified on another
   chain. Each is not covered, with its reason.
3. The code at that address, read with `VerifiedReads.getCode` at the
   verified head, hashes to the pinned hash. Where its code can change
   (A1, step 4), the pinned pointer (a slot read with `getStorageAt`, or a
   verified call) holds the pinned implementation, whose code hashes to its
   own pin. Each read is a verified one at a fresh head (owner's decision,
   2026-11-25, on #382, replacing "every read of one check is at one
   block"); a call pin is held to the block the code
   was proven at, a slot is read at the head (the engine proves no hashed
   slot at a given block), and the implementation's code is proven in
   turn. An upgrade between two reads either shows in one of them, and the
   call is not described, or is caught at the next check: the transaction is
   included later than any read in any case. A declared library of an
   implementation (A1, step 4) is pinned at build time only: its address is
   fixed in the implementation's code, which the binding pins, and code at an
   address does not change.
4. The selector, or the typed data's `encodeType` hash and domain, matches a
   format.

Each failure is shown as the call not decoded, with its reason. For a
contract changed after the build (decision 3): "This contract changed after
this release was built; its description no longer applies." The reads go
through the existing Myotis adapter; nothing new crosses `io.myotis.api`.

### A5. `:ui`: where it shows

- **D6, a dApp's `eth_sendTransaction`** (docs/walletconnect.md): the intent
  on top, then the fields in the descriptor's order, then "Other
  parameters"; the verified simulation stays below; approvals flagged loudly.
- **D5, a dApp's `eth_signTypedData_v4`**: the struct types no
  hand-written decoder claims.
- **Safe transactions** (docs/wallet-ux.md, screen 17, and a dApp's Safe
  transaction): the inner call formatted; the `safeTxHash` still shown in
  full, to compare with a hardware signer's screen. The Safe refusals come
  first. How it is applied, not part of the decisions: each call inside a
  MultiSendCallOnly batch gets its own line once #57 is built; until then
  every delegate call stays refused, and a delegate call to MultiSend
  itself stays refused after it too. A Safe transaction's call that the
  Safe review does not decode itself (a contract call, operation CALL) is
  described with the Safe as its sender, on every screen of it (the
  overview, signing, the reviews, a held signature), asked again with each
  *Check again*. A dApp's Safe transaction is D6's, described there. A
  `SafeTx` an owner here is asked to sign as typed data (Safe{Wallet} over
  WalletConnect), and the Safe transaction an owner's `execTransaction`
  runs, are decoded and described the same way, the Safe as the sender
  (docs/walletconnect.md, *A Safe transaction to sign*).
- **Not yet:** a nested call whose selector a hand-written decoder claims
  (ERC-20 `transfer`, `approve`, and the token calls of `TokenCalls`) is
  shown raw inside its parent, flagged as
  an approval where it is one, not put in words. No shipped deployment nests
  one today; MultiSend batches would, once #57 is built.
- **Previews** for every state: described; described with other parameters;
  nested; not covered, one per reason; contract changed; an unlisted token in
  a `tokenAmount`; and the dark theme. How it is applied, not part of the
  decisions: simple mode keeps the intent, the fields and a short
  "Described by <name>" line; the attestation and the raw values beside the
  formatted ones are left to the detailed view.

## Delivery

Through `feature/clear-signing`, one part PR per step, each described along
the five steps of docs/architecture.md:

1. **Rules and docs**: this page, the rule sections, docs/walletconnect.md
   (D5, D6) and docs/wallet-ux.md (screen 17).
2. **The generator and the pins** (A1), with the first list and the CI job
   that runs the schema check and the linter.
3. **`:domain`** (A3): the model, the strict ABI decoder, the paths, the
   formatter and the binding check as pure functions, tested on the
   registry's vectors written out by hand.
4. **`:data`** (A4): reading the catalog, the verified code reads for the
   binding check (`ContractCodes`), the use case `DescribeClearSigning`, and
   the conformance tests over every vendored vector: 56 of the 57 that run
   read as the registry expects; the one other is refused on purpose
   (trailing bytes).
5. **`:ui`** (A5): the wiring in the composition roots and `AppGraph`, D6
   first, then D5 and the Safe review, with previews, screenshots and the
   `CHANGELOG.md` entry.
6. **The final PR**, `feature/clear-signing` → `main`.

Then, later parts: the attestations' check, and the descriptors of decision 5.

Phase B lands through `feature/verified-trace`:

1. **Rules and docs**: phase B here, in `.claude/rules/trust.md` and
   `.claude/rules/data-sources.md`, and D6 and screen 17 in
   docs/walletconnect.md and docs/wallet-ux.md.
2. **`:domain`**: the trace port, the interpreter of the effects, the check
   against a description, the use case.
3. **`:data`**: the adapter over `traceCall`, routed per network (Android
   and the desktop; iOS answers "not available" until Myotis exposes the
   call there), and its entry in docs/embedding-myotis.md.
4. **D6**, with previews.
5. **The Safe review** (screen 17), with previews and the `CHANGELOG.md`
   entry.
6. **The final PR**, `feature/verified-trace` → `main`.

## Phase B: a verified trace

(The owner's decisions, 2026-11-26, on #240: questions 6 and 7, and what a
disagreement does.)

The description of phase A is its author's claim of what a call does. A
trace is what the call does when it runs at the verified head: Myotis's Rust
engine runs it (`VerifiedReads.traceCall`, `debug_traceCall` with the
`callTracer` and its logs; VesperBats/myotis#716) against state it proves slot
by slot, as it runs the simulation D6 already shows. Phase B shows the trace's
effects beside the description, and warns where the two disagree.

### The owner's decisions (2026-11-26)

1. **Where, and how labelled** (question 6): D6, a dApp's transaction, first;
   then the Safe review (screen 17), a Safe transaction traced as the Safe's
   own call. The effects are labelled as proven at the verified head, and
   shown beside the description and the simulation.
2. **A description and a trace that disagree are warned about loudly, and the
   call stays signable.** The owner's examples: a "Swap" whose trace moves a
   token the call does not name, or moves the user's tokens to an address
   other than the shown recipient.
3. **`diffMode` later** (question 7): token movements and approvals come from
   the trace's logs (`callTracer` with `withLog`), the network's currency
   from the call tree. Myotis 0.1.24 refuses the `prestateTracer`'s
   `diffMode` (exact balance and storage changes); it is asked for once
   phase B shows what is missing without it.
4. **The plan lands through `feature/verified-trace`.** The owner allowed the
   session that asked to merge the part PRs into it, once each is done under
   the Workflow. The PR into `main` is the owner's.

### What a trace proves, and what it does not

- **Proven: the run at the verified head.** The transaction's sender,
  recipient, value and data run against the state the light client verified
  at that head, a block the trace is asked at by its number (the engine
  applies it or refuses it; its answer names no block): every contract it
  reaches, how (a call, a delegate call, a static call, a creation), every
  log it emits, and whether and why it reverts.
- **Not given: the gas limit and the fees.** The trace runs as the
  simulation does, as a call without them: no fee is charged to the sender
  (and none to a Safe, which never pays for its own execution). A contract
  that branches on the gas left or the gas price can do otherwise when the
  signed transaction runs.
- **Not proven: the run at inclusion.** The transaction is mined later, on
  other state. A contract that branches on the block, a price or another
  transaction can do something else then.
- **The effects are what the contracts log.** A token that moves or grants a
  right without logging it is not seen, and a token that logs falsely is
  believed: a trace with nothing alarming in it is no proof that nothing
  else happens. Exact balance changes need `diffMode` (decision 3).
- **An approval moves nothing at that moment**: it is shown as a right
  granted, not a movement.
- **A signed message never runs.** Typed data (D5) has no trace.

### How it is applied, not part of the decisions (the owner's to approve on #240)

**The effects** (B2). A pure interpreter reads the call tree and its logs,
counting a frame only when neither it nor any frame above it failed:

- **The network's currency**: the value of each CALL, CREATE, CREATE2 and
  SELFDESTRUCT frame to or from the account, from the call tree alone
  (decision 3). A DELEGATECALL or CALLCODE frame carries its caller's value
  and moves nothing, so it is not counted. The network's own log of a
  currency transfer (EIP-7708, a `Transfer` from `TokenTransfers.ETH`, as
  Sepolia logs one) is that same move and no token: it is not read. Wrapped
  currency is read from WETH's and WXDAI's `Deposit` and `Withdrawal`,
  emitted by their pinned addresses, since they log no `Transfer` for it.
- **Tokens** the account sends and receives: ERC-20 `Transfer` (three topics)
  and ERC-721 `Transfer` (four topics), ERC-1155 `TransferSingle` and
  `TransferBatch`, with the counterparty. A log of one of these events that
  is not its exact encoding is not read, and the effects say how many were
  not.
- **Rights granted**: an ERC-20 or ERC-721 `Approval`, and an
  `ApprovalForAll` set to true, with the account as owner. The `Approval`
  a token logs while its spender spends an allowance (from inside the
  spender's `transferFrom`) is no grant. It is told apart by who called the
  token: the sender of the token's own frame, the `from` of the nearest frame
  at or above the log that is not a DELEGATECALL (`msg.sender` where the log
  was emitted; for a token behind a proxy, the frame into the proxy). An
  `Approval` with that sender as its spender is a spend when the same frame
  also logs a `Transfer` of that token from the account, and a grant when it
  does not (a `permit` signed earlier and relayed by its spender). Permit2's
  `Approval` and `Permit` count only as emitted by the pinned Permit2.
- **Tokens are named as the description names them** (A3): by the built-in
  list, any other by its address with its amount raw.
- **The addresses it calls** (no code is read for them, so a payee is among
  them: not every one is a contract), each named where the wallet names it (a
  built-in token, a pinned deployment), and **a delegate call into code the
  wallet cannot name** marked as such. Most proxies delegate to their
  implementation, so this is a marker beside the contract, not a warning.
- **A revert**, with its reason where the contract gives one. A revert in
  the trace alone stops nothing: the simulation's revert stops the send, as
  D6 says; one the trace shows while the simulation succeeds (the head moved
  between the two) is warned about like a disagreement.

**What the trace is held against.** The claims of what the call does: its
description (A3) where one is shown; otherwise the meaning a hand-written
decoder gives it (`DappCallMeaning`, `SafeTxAction`: a payment names its
recipient and the currency; a token `transfer` its token and recipient; an
`approve` its token, and grants a right). A call neither describes is shown
with its effects alone, under "not decoded".

- **The tokens it names**: each `tokenAmount`'s token (or the network's
  currency), `tokenTicker` and `nftName` collection, the nested calls'
  included; or the decoder's token.
- **The addresses it shows**: every `addressName` (a recipient, a spender, a
  debtor, a seller alike: an address the user is shown), the sender's own
  where one stands for the sender; or the decoder's recipient or spender.
  The contract the call is sent to is one too: the review names it, so what
  it is paid and keeps (a name's price) is no disagreement.
- **Whether it grants a right**: the approval flag (A3), or an `approve`.

**The disagreements** (decision 2):

- **A token the claims do not name**: the account sends a token, wrapped
  currency or an NFT that the claims do not name. A token the account burns
  (sends to the zero address, as a lending pool's `withdraw` burns its shares,
  or a repaid loan its debt token) is not sent to anyone. It counts only where
  nothing of value comes to the account or an address the claims show (an
  asset the claims name, a built-in token, the currency or its wrapped token),
  and the account pays nothing the claims name. The network's currency the
  transaction itself carries (its value, shown on the screen already) is no
  disagreement.
- **The user's tokens ending elsewhere**: an asset (each the account sends or
  receives, each the claims name, and the currency where the account moves it
  or the claims show an amount of it, the currency and its wrapped token held
  as one) ends up, more of it than it started with, with an address that is
  none of: the account, an address the claims show (claims that show none are
  held to the account alone, and the contract called), the zero address or the
  token's own contract (a burn, as wrapped currency is burnt when it is
  unwrapped), and, for an asset the account pays with (the currency and its
  wrapped token counting as one), a counterparty that pays on. A counterparty
  pays on when a chain of the trace's moves leads from it to the account or a
  shown address, the last move of that chain an asset of value (one the claims
  name, a built-in token, the currency or its wrapped token: any contract can
  log a junk token's move) or a mint (its issuer paying in its own shares, as
  a lending pool or a vault does for what it takes). Moves further up the
  chain may be in any token, since a route's middle token is often in no list.
  A contract that only takes is never a counterparty, however much code it
  runs. What passes all the same, and the trace cannot tell: a counterparty
  that pays back dust, a contract that takes the tokens and mints its own
  shares for them, and a thief whose move of a junk token into a real
  counterparty is logged. The trace proves where tokens go, not that the price
  is fair.
- **A right the claims do not grant**: the trace grants a right (as above)
  that the claims do not: they grant none, or not over that token (for
  Permit2, the token it lets be taken), or not to that spender (an address
  they show).

The unit tests hold the checks against the traces of ordinary calls (a swap
in one hop and in several, a wrap, a deposit into a lending pool and its
withdrawal, a fee) as well as the owner's examples, so that a warning is
rare and meant (docs/balance-reconciliation.md, D3).

**Where it shows** (B4, B5):

- **D6**, a dApp's transaction, an account's or a Safe's (a dApp's Safe
  transaction traced as the Safe's own call): "What it does now", the
  effects, beside the verified run of the simulation, with the block they are
  proven at.
- **The Safe review** (screen 17): a Safe transaction's call (operation CALL)
  traced as the Safe's own call: the Safe as its sender, its value and data,
  no fee. The trace leaves out what `execTransaction` adds: the refund of the
  *Gas and refund terms* and the Safe's own checks. A delegate call stays
  refused and is not traced.
- Simple mode, on the screens that have one (screen 17), shows the effects
  with their label (now, not when it is mined) and when they were traced in
  place of the block, as `.claude/rules/ui.md` has it for a value the user
  acts on. D6 has no simple form; it names the block, as its quote does.
- A disagreement is said loudly in the trace and again beside the button
  that signs, which stays as it is.

**Limits**:

- **Android and the desktop trace; iOS does not yet.** Both run Myotis's Rust
  engine, whose JVM API offers `traceCall` (`supportsCallTracing`). The iOS
  engine (`myotis-engine-ios`) has the call in its native library but does
  not expose it on its chain handle in 0.1.24: until Myotis does, iOS says
  the trace is not available. Asking Myotis for it is the owner's.
- **A deep call fetches many proofs.** The wallet waits 30 seconds for a
  trace; past that the screen says it could not be traced in time. The
  engine's own run goes on to its budget (90 seconds) and is not stopped, so
  *Try again* waits for that run's end rather than starting a second one.
  A trace the engine finds infeasible (the value is more than the sender
  holds, for one) is said so.
- **At a fresh verified head only**, as the simulation: while the light
  client catches up, nothing is traced.
- **A trace too large to read** (over 16 Mi characters of the engine's
  answer, a contract that calls with much data again and again), or one not
  read exactly, is no verified answer: the screen says none could be had.

## Past transactions, described on a tap

(Owner's decisions, 2026-11-27, in the session that built it: a button, not by
default; an upgradeable contract described with a caveat; no wait for an
unmetered connection, since the screen says how much it downloads.)

- **Only on the user's tap.** An Activity row's detail (docs/wallet-ux.md,
  screen 26) offers *Describe this transaction* wherever it names a
  transaction. Nothing is read, described or kept before the tap, and nothing
  after it outlives the screen.
- **Its call is read verified.** A transaction this device sent is read from
  its own record of the signed bytes: no block is downloaded, only the
  contract's code is checked. Any other is read from its block's transactions
  (`BlockTransactions`), verified against the block's header; the button says
  beforehand how much that usually is (about 150 KB on Ethereum, at most about
  2 MB; about 50 KB on Gnosis Chain, at most about 1 MB; about 100 KB on
  Sepolia, at most about 2 MB), and for a block below the light client's
  window up to about 0.6 MB more, for the walk of up to 1,024 headers down
  from the checkpoint table that verifies it. It does not wait for an
  unmetered connection. A block no peer serves now says so.
- **Greyed out where it cannot work, the tap saying why** (owner ruling,
  2026-10-18, #55): a network that is off; a device whose engine reads no
  block by number (iOS, for now); a block older than the last 512 on Sepolia,
  whose light client reads no deeper; a transaction no block is known for.
- **Described as a call to sign would be** (A3, A4): the built-in
  descriptions, the hand-written decoders first, a Safe's `execTransaction`
  as what the Safe ran. The contract's code is checked as it is now: the
  result says it is described after the fact, against today's code.
- **An upgradeable contract** (a pinned proxy), the description's or a
  nested call's, is still described, with a line saying its code when the
  transaction ran may have been other than the code checked now.
- **Said of what ran**, in the past tense, naming its sender: a third party
  may have sent it. Its addresses cannot be selected when a third party sent
  it (address poisoning protection, #324), and while amounts are hidden
  nothing of what it moved is said.
- Not done yet: the description is not held against the transaction's
  verified logs, as a trace is on D6 and screen 17; the row's own verified
  movements are shown above it.

## Later phases (open)

- **C. Verified ABIs for the long tail.** A build job runs lib-sourcify over a
  curated list, writing code hash → ABI (and NatSpec for a `perfect` match),
  checked at runtime against a verified `getCode` hash as in A4. It names the
  function and its parameters, with no intent. Never on the device.

## Open decisions

The owner's, recorded here and in `.claude/rules/data-sources.md` before
their code lands (#240):

- Whether the Safe refusals (#63, #54) reach what a trace proves: on
  screen 17 a call can change the Safe itself through a module already
  enabled (`execTransactionFromModule` back into the Safe, logging
  `EnabledModule` or `ChangedFallbackHandler`), which the effects above do
  not read. Raised by the review of part 1; the owner's.
- Ordinary calls that still warn under the checks above, the logs alone
  unable to tell them from a theft (raised by the review of part 2): Aave's
  `repay`, whose asset stays with the reserve's aToken, which pays nothing
  back; an allowance lowered (`decreaseAllowance`) or used up in the same
  call (`supplyWithPermit`), read as a right granted. Whether to accept
  these warnings or narrow the checks further is the owner's.
- Phase C: where the build takes the sources from, and what a contract on no
  list shows (question 8).
