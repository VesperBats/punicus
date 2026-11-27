# CLAUDE.md

Instructions for an AI coding agent working in this repository; humans read
them too. Punicus is the sibling of
[biafra23/myotis](https://github.com/biafra23/myotis): a Compose Multiplatform
wallet for Ethereum accounts (EOAs) and Safe{Wallet} smart accounts, built on
the embedded Myotis engine. **The rules of development are Myotis's.** On
2026-10-26 their text was split into the files below and reworded in shorter
sentences; the rules, the owner rulings and their dates are unchanged. Myotis
still carries the original wording in its one CLAUDE.md.

Words used throughout: **the owner** is the repository owner (biafra23). An
**owner ruling** is a decision only the owner can change; it is quoted with
its date and, where there is one, its issue or PR number, like "(owner
ruling, 2026-10-18, #55)". **A finding** is something a reviewer must flag.
**A port** is an interface in `:domain`; **an adapter** implements it in
`:data`; **a use case** is one user-visible capability; **a host** is an app
module with the one **composition root** that wires adapters to ports.
**Verified** means proven by the embedded Myotis light client.

## How these instructions are organized

This file is always loaded. It holds the short form of every rule and says
where the full text is, so a session loads only what it needs. A rule file
(`.claude/rules/`) loads by itself when a file matching its `paths:` is read
or edited. A skill (`.claude/skills/<name>/SKILL.md`) loads on demand:
`/<name>`, or when the task matches its description.

| Rule file | Loads for | Full text of |
|---|---|---|
| `architecture.md` | `domain/`, `data/`, `ui/`, the hosts, `native/`, the root build files | Architecture; SOLID, tests per layer, the feature checklist |
| `platform.md` | every `.kt` and `.kts` file, `gradle/` | Kotlin conventions; Platform & language direction; the minSdk 29 JDK-API budget |
| `ui.md` | `ui/`, the screen docs | the greyed-out rule, previews, how a screen is laid out |
| `hosts.md` | `android-app/`, `desktop-app/`, `ios-app/` | composition roots, the two Android apps, signing |
| `trust.md` | `domain/`, `data/`, `ui/`, the hosts, `native/`, the root build files, `docs/` | Trust: verification, the Safe refusals, dApp requests, key material, how keys are held |
| `data-sources.md` | `domain/`, `data/`, `ui/`, the hosts, `native/`, the root build files, `docs/` | Data sources: networks, prices, tokens, Safe co-signing, history, swaps, CoW, WalletConnect |
| `github-workflows.md` | `.github/`, `scripts/` | the invariants of the workflows; how fork PRs are reviewed |
| `instructions.md` | this file, `.claude/`, `docs/` | how to change these instructions |

| Skill | Use it when | Role |
|---|---|---|
| `pr` | you reach step 3 of the Workflow, or handle a review comment or CI on a PR | developer |
| `screenshots` | a PR changes what the user sees | developer, UX designer |
| `feature-branch` | a work item needs more than one PR | developer |
| `release` | the owner asks for a release, a version bump or a `v*` tag | developer |
| `reviewer` | you review a diff or a PR | reviewer |
| `ux` | you design or change a screen, a flow or the words the user sees | UX designer |
| `security-fix` | you work on, or come across, a vulnerability that is not public yet | developer |

**Roles.** A developer starts with this file; the rules load as code is
touched; `/pr` comes at step 3 of the Workflow. A reviewer starts with
`/reviewer`. A UX designer starts with `/ux`. **Before you change code, make
sure `architecture.md` and `trust.md` have loaded**; a rule loads on a Read,
Write or Edit of a matching file, not on a grep. If your tool does not load
rules or skills by itself, read the files named above for your task before
you start. **Agents other than Claude Code:** read this file, then the files
it names for your task.

## Build & Run

```bash
# Build all modules: compile, unit tests (JVM + Android unit), the headless Compose UI tests,
# Android lint, the debug + release APKs. Needs JDK 21 and an Android SDK (ANDROID_HOME, or
# sdk.dir in a git-ignored local.properties). The iOS targets build on macOS only.
./gradlew build
./gradlew :ui:desktopTest                                                   # one module's tests
./gradlew :domain:desktopTest --tests "eth.serotine.domain.model.AddressTest" # one class
./gradlew :desktop-app:run                                                  # the desktop app
./gradlew :android-app:installDebug                                         # the debug app on a device
# The iOS app (macOS, Xcode, `brew install xcodegen`): the Xcode project is generated, not committed
cd ios-app/xcode && SEROTINE_DEVELOPMENT_TEAM=<team id> xcodegen generate && open Serotine.xcodeproj
./gradlew :ios-app:linkDebugFrameworkIosSimulatorArm64                      # the framework alone
./gradlew :desktop-app:packageDeb   # Linux;  packageDmg on macOS;  packageMsi on Windows (WiX 3)
# Every screen's previews as PNGs in build/screenshots/, to look at locally (CI commits docs/screenshots/)
./gradlew :ui:desktopTest --tests '*PreviewsTest' --rerun -Pscreenshots=build/screenshots
./gradlew -q :printReleaseVersion   # the release version the tag guard compares against
```

## Architecture

Full text: `.claude/rules/architecture.md`. **docs/architecture.md is the
contract.**

- Clean architecture with MVVM. Modules: `domain` (pure Kotlin, no Android
  target), `data` (the adapters, the Myotis engine adapter among them), `ui`
  (Compose Multiplatform; depends on `:domain` only), and the hosts
  `android-app`, `desktop-app`, `ios-app` (one composition root each).
- **The dependency rule: arrows point inward only.** `:domain` depends on
  nothing of ours. `:ui` and `:data` depend on `:domain`. The hosts depend on
  `:ui` and `:data` and alone know which adapter implements which port. A
  violation is a finding.
- **MVVM.** A screen is a pure function of an immutable UiState. A ViewModel
  exposes `StateFlow<UiState>`, calls use cases, never a repository, and
  holds no platform reference. Errors the user must see are UiState variants.
- **The Myotis boundary.** All chain data comes from the embedded engine
  through the `io.myotis.api` contract and nothing else. The adapter lives in
  `:data`. `:domain` and `:ui` never import `io.myotis.*`.
- **Dependency injection** is manual constructor injection from the
  composition roots. No DI framework; adding one is the owner's decision.

## Conventions

Full text: `.claude/rules/platform.md`, `.claude/rules/ui.md`,
`.claude/rules/hosts.md`.

- Kotlin, official style, 120 columns, immutable by default, structured
  concurrency only, KDoc on every public type. Tests per layer, all run by
  `./gradlew build`.
- **A missing prerequisite greys an action out, never hides it** (owner
  ruling, 2026-10-18, #55). A tap on the greyed item says what is missing and
  how to get it.
- **Every screen has previews** (owner's request, 2026-10-17): one per state
  plus one in the dark theme, rendered by a test, so a broken preview fails
  the build.
- Adding a feature follows the five-step checklist at the end of
  docs/architecture.md, and the PR describes it along those steps.
- Two Android apps come out of `:android-app`, release and debug. **Never
  give the release build type a `signingConfig`.**

## Workflow — the mandatory steps for EVERY work item

Full text of steps 3 to 5: `.claude/skills/pr/SKILL.md`. Do every step, in
order. None is optional, and none waits to be asked for. The PR is skipped
only when the user explicitly says not to open one, or for an embargoed
security fix (below the steps).

1. **Code** the change on the designated branch (an embargoed security fix:
   a local branch, never pushed).
2. **Review** your own diff: `/code-review` in Claude Code; in another tool,
   `/reviewer` on your own diff. Fix what it finds.
3. **PR.** Open the pull request: base `main`, or the feature branch for a
   part of a multi-PR plan. The description follows the PR rules below.
4. **Wait for review comments, and make sure they reach you.** In the
   desktop app, right after `gh pr create` returns: `mcp__ccd_pr__set_monitor`
   with the PR's URL, `auto_fix: true`, `address_comments: true`; it is per
   PR and off by default (owner ruling, 2026-10-09). In a claude.ai/code
   cloud session, `subscribe_pr_activity` at the same moment, without asking
   and without sending the owner to the Auto-fix toggle (owner ruling,
   2026-11-10, #284). "Pushed" is not "done".
5. **Address every comment** and drive CI to green. Keep watching until the
   PR is merged or closed.

**An embargoed security fix** (a vulnerability that is not public yet,
reported or found by you; a found one is told to the owner first) takes
the `security-fix` skill instead of steps 3 to 5: no PR, and no push to any
branch, the designated one included, before the owner has merged the fix;
neutral wording until the advisory is published (owner's request,
2026-11-15, on Myotis #870).

**MERGING IS THE OWNER'S ACTION. NEVER MERGE A PR YOURSELF** (owner ruling,
2026-08-31). Adding a PR to `main`'s merge queue merges it. Green CI plus
every comment answered means: report "ready to merge" and keep watching.
The one exception is a part PR into a feature branch when the owner said
yes for that plan (the `feature-branch` skill).
In the Claude Code desktop app the session renames itself (owner's request,
2026-10-09): `IS#<n>: <title>` once the issue is known, `PR#<n>: <title>`
once the PR exists (the `pr` skill).

## Multi-PR plans land through a feature branch, never piece by piece on `main`

Full text: `.claude/skills/feature-branch/SKILL.md`.

- **`main` must be releasable at every commit** (owner ruling, 2026-09-30).
- A work item of more than one PR gets `feature/<topic>` first, from `main`.
  Every part PR targets the feature branch. The final PR is
  `feature/<topic>` → `main`.
- Ask the owner, once per plan and before the first part is coded, whether
  you may merge the part PRs into the feature branch yourself. Without a
  "yes" in this conversation, the owner merges them.
- A PR into `main` is never yours to merge.

## Releases — ask before cutting one

Full text: `.claude/skills/release/SKILL.md`.

- Everything a release needs goes into ONE release PR.
- **The tag is the owner's action.** Never push a `v*` tag on your own
  initiative (owner rulings, 2026-09-02 and 2026-09-11 in Myotis).
- Moving the version pin is mandatory, not a question (owner ruling,
  2026-09-24): `version = "X.Y.Z-SNAPSHOT"` in the root `build.gradle.kts`.
- So is the release's entry in `CHANGELOG.md` (owner's request, 2026-11-09,
  #281): `[Unreleased]` becomes the release, in the same release PR.

## Pull requests and code review

Full text: `.claude/skills/pr/SKILL.md`; the reviewer's side,
`.claude/skills/reviewer/SKILL.md`.

- **Everything on GitHub is in English** (owner ruling, 2026-09-17): title,
  description, commits, replies.
- A PR description ends with the session link, never with the "Generated
  with Claude Code" footer (owner ruling, 2026-10-09).
- A PR that changes the UI shows it in screenshots, or says "No UI change"
  (owner's request, 2026-10-19). CI renders every screen into
  `docs/screenshots/` and commits the changed ones to the PR's branch; never
  commit one yourself (owner rulings, 2026-11-04). The `screenshots` skill.
- Reply to every review comment, one by one, on its own thread, with a plain
  verdict: Accepted, Disputed or Deferred. Verify the claim first.
- Fix review comments on the PR's own branch, never in a separate PR (owner
  ruling, 2026-10-09). Drive CI to green. Never merge.
- **Fork PRs are reviewed manually, never by the CI reviewer** (owner's
  decision, 2026-08-15).

## Platform & language direction

Full text: `.claude/rules/platform.md`.

- **Android first**: minSdk 29. No JVM-only API that desugaring cannot
  cover. No `java.net.http`. HTTP, when needed at all, through Ktor.
- The minSdk 29 JDK-API budget lists what is safe and what is not;
  `scripts/check_apk_min_api.py` enforces it on the built APKs.
- Java 17 class files for everything Android loads.
- Kotlin and Compose Multiplatform everywhere; new code in `commonMain`.
- Toolchain pins mirror Myotis's (`gradle/libs.versions.toml`).
- **Every Gradle dependency is pinned by version AND hash** (owner's request,
  2026-11-09, #281): a bump regenerates the lockfiles and
  `gradle/verification-metadata.xml` with `scripts/update_gradle_pins.sh`.
  Never re-pin a verification failure you did not cause.

## Trust

Full text: `.claude/rules/trust.md`. These rules bind every change.

- **Peer-trusted is never an option.** Everything shown about the chain is
  verified by the embedded Myotis engine or shown as not available. No
  JSON-RPC provider, indexer or "temporary" fallback, in any build type.
  The addr1 index's rows are shown only verified; its importer key vouches
  for the list's completeness alone (owner ruling, 2026-11-21, #334).
- One older answer is shown, labelled as one (owner's request, 2026-10-19):
  a balance proved earlier stays on screen, dated, out of totals, never acted
  on. Past the weak-subjectivity window only on the user's word (owner's
  request, 2026-10-24), or from a fresh checkpoint the checkpoint servers and
  a long-known peer agree on, fetched on the user's tap (owner's request,
  2026-11-14). In simple mode, on a network without block access
  lists, a balance is read on demand and counts in totals with its age for
  up to an hour (owner's decisions, 2026-10-26). In lenient mode, off by
  default, a transaction may be signed on the state last verified; nothing
  is sent before a check at a fresh head (owner rulings, 2026-11-06, on
  #258; docs/lenient-mode.md).
- A Safe transaction that sets a fallback handler other than the pinned one,
  or none (owner ruling, 2026-10-18, #63), or enables a module (owner ruling,
  2026-10-18, on #54), is shown and never signed, approved or executed.
- A Gnosis Pay account sends through its own Delay module: a member whose
  key is held here queues a plain transfer, then executes it after the
  cooldown; the current mastercopy only, no relayer, the queue checked
  first (owner's decisions, 2026-11-15; docs/gnosis-pay.md). So does a key
  that owns a member Safe, through Safes of threshold 1 (owner's request,
  2026-11-17). Such a key may also add or remove a member of that Delay the
  same way, never its last one, and never through a Delay whose entries never
  expire (owner ruling and decision, 2026-11-18). A member may sign Gnosis
  Pay's card limit change for its relayer, through a verified Delay of the
  wallet's account, and nothing else that way (owner's request, 2026-11-27).
- A Swarm node is added as a watched address (no `swarm.key` import); its
  batches and chequebook are managed through pinned contracts only (owner's
  decisions, 2026-11-23; docs/swarm.md). Its key may be imported in hex like
  any key (owner ruling, 2026-11-25).
- Network-upgrade warnings are peer reports and say so (owner's request,
  2026-10-17).
- A parameter that can change the answer is APPLIED or REFUSED, never
  accepted and silently ignored (Myotis #314).
- What a dApp sends is untrusted input, held to a closed method list;
  `eth_sign` is refused in every case (owner rulings, 2026-11-03, #22). A
  call is clear-signed only when bound to verified code, the descriptor's
  words labelled as its claim (owner's decisions, 2026-11-25, #240). A dApp's
  or a Safe's transaction shows its verified trace at the head, a
  disagreement with its description warned about, still signable (owner's
  decisions, 2026-11-26, #240).
- **Key material** lives in the key-store adapter and nowhere else. The
  exceptions are a closed list of owner rulings: the mnemonic screens (owner
  ruling, 2026-10-15), the words travelling as the dedicated mnemonic type
  along one path and cleared when the screen is left; and a private key
  typed in hex, the same way, one way only (owner ruling, 2026-11-25).
- How keys are held: docs/keys-and-signing.md is the contract. On the
  password path BOTH the Argon2id-stretched password AND a secret of the
  device's hardware key store must be cryptographically necessary to unwrap a
  key; a password that is merely checked is a finding.

## Data sources

Full text: `.claude/rules/data-sources.md`. docs/wallet-ux.md applies these
rulings to the screens.

- The only source of chain data is the embedded Myotis engine. HTTP to a node
  is for debugging only.
- The engine asks the checkpoint-sync servers for a fresh checkpoint only at
  a stale checkpoint and on the user's tap; it is a trust anchor, never chain
  state (owner's request, 2026-11-14).
- Networks: Ethereum, Gnosis Chain and Sepolia; one chain per account; no
  current network; each network can be turned off; Sepolia keeps every
  testnet warning and is never added to a total with real funds (owner
  ruling, 2026-10-22).
- Prices come from Chainlink aggregators read through the light client, and
  are display-only: a price never reaches a signature (owner ruling,
  2026-10-16). The token list is built in
  (owner ruling, 2026-10-16). Safe co-signing uses no off-chain service
  (owner ruling, 2026-10-16).
- Clear signing's ERC-7730 descriptors are built in, pinned with each
  contract's code hash; never fetched (owner's decisions, 2026-11-25, #240;
  docs/clear-signing.md).
- An account's history comes from the engine's verified log index (owner
  rulings, 2026-10-20, #85). Its walk back is a heavy download: it waits
  off a metered connection unless the user allows it in Settings (owner
  ruling, 2026-10-20).
- *Activity* may also take the addr1 address index, which the engine reads
  from Swarm: verified rows only, on the desktop and Android (owner's
  decisions, 2026-11-24 and 2026-11-25), behind an experimental setting that
  names the importer key pinned in the release (one per network with an
  index: Ethereum and Gnosis Chain, owner's request, 2026-11-26), on by
  default and the user's to turn off; an off survives a new key (owner's
  decisions, 2026-11-22, #334). Its completeness rests on that importer, and the list says so; a contradicted entry warns against the key (owner ruling,
  2026-11-21, #334, and decisions on #335).
- Swaps: Uniswap first, then CoW Protocol; the user chooses the venue (owner's
  directions, 2026-10-18 and 2026-10-22). CoW's order API is used only for a
  swap the user sends to CoW (owner ruling, 2026-10-22), and, with CoW
  chosen, for the compose step's estimate (owner ruling, 2026-11-23). A CoW
  order does not depend on Uniswap: its minimum is CoW's estimate less the
  tolerance (owner ruling, 2026-11-23).
- An account's NFTs are found in its verified log index and shown only once
  proven held; on-chain metadata only; unlisted collections collapsed; older
  NFTs added by contract and id (owner rulings, 2026-11-05, #246), and ENS
  names also by name (owner ruling, 2026-11-15, #274).
- Where no Chainlink feed is pinned, a Uniswap pool's 30-minute average
  prices a token, labelled and counted in totals; a stale feed never falls
  back to it (owner ruling, 2026-10-29, #205).
- dApps connect over WalletConnect v2, whose relay never supplies chain
  state; the project ID is never committed (owner rulings, 2026-11-03, #22;
  docs/walletconnect.md). A dApp session holds Ethereum and Gnosis Chain
  together, or Sepolia alone, never both (owner ruling, 2026-11-05, on #22).
- Send from another wallet hands a transfer to another wallet app as an
  `ethereum:` payment link; nothing comes back, and the outcome is read from
  verified blocks alone (owner rulings, 2026-11-05, #255).
- Swarm: no Bee node or Swarm service is contacted (owner's decisions,
  2026-11-23; docs/swarm.md).
