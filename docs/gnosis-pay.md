# Gnosis Pay accounts

A Gnosis Pay account backs the Gnosis Pay Visa card. On chain it is a Safe on
Gnosis Chain, and the account that controls it is not one of the Safe's
owners: it is a member of a Zodiac Delay modifier enabled on the Safe, or a
key that owns such a member when the member is a Safe. This
page is the contract for recognising such an account and for sending from it
through that module. It records the owner's decisions of 2026-11-15 and the
owner's request of 2026-11-17 (*Control through a member Safe*) and the
owner's ruling of 2026-11-18 (*Adding and removing a member*), which
`.claude/rules/trust.md` (*A Gnosis Pay account sends through its Delay
module*) carries as rules.

## How the account is set up

Gnosis Pay's own SDK, account-kit (github.com/gnosispay/account-kit,
`accountSetup.ts`), turns a 1/1 Safe into a Gnosis Pay account in one Safe
transaction:

- It swaps the user's EOA out as the Safe's owner for the sentinel `0x…02`,
  which no key controls. The Safe's threshold stays 1. No owner signature can
  move the Safe's funds any more.
- It enables two modules on the Safe: a Roles modifier and a Delay modifier,
  each a minimal proxy deployed by Zodiac's `ModuleProxyFactory`
  (`0x000000000000aDdB49795b0f9bA5BC298cDda236`).
- **The Delay** has the Safe as its avatar, target and owner. The user's EOA,
  or several "beneficiaries", are enabled as modules on the Delay: its
  members. A member queues a transaction for the Safe with
  `execTransactionFromModule(to, value, data, operation)`. After the
  cooldown, and until the expiration, anyone executes it with
  `executeNextTx(to, value, data, operation)`, which the Delay passes to the
  Safe's `execTransactionFromModule`. The queue is first in, first out:
  `txNonce` is the next entry to execute, `queueNonce` the next to be added,
  `txHash(n)` the hash of entry `n`
  (`keccak256(abi.encodePacked(to, value, data, operation))`) and
  `txCreatedAt(n)` its time.
- **The Roles modifier** lets Gnosis Pay's spender move the card's currency to
  its settlement address within an allowance. That allowance binds the
  spender, not the members.

Read on Gnosis Chain on 2026-11-15, Gnosis Pay accounts carry a cooldown of
180 s (3 minutes) and an expiration of 1800 s (30 minutes after the
cooldown): every one of 40 accounts set up recently on the current
mastercopy, and 30 of 30 sampled on the retired one. A few differ (the
account the tests read, `0x1884…d4fb`, has 90000 s and 111600 s), so the
screens show each account's own figures, never a constant.

## The mastercopies

The Delay's code is its mastercopy's. Gnosis Pay replaced it on 2026-06-03
(account-kit `f4b9287`), two days after an exploit of 2026-06-01 that went
through the Delay module:

| Mastercopy | Generation | Punicus |
|---|---|---|
| `0x22d903fd45F441F51bcad198D14eBa8a75EA1ef0` | current (Sourcify: exact match of `Delay.sol`) | recognised; a controlling key held here sends |
| `0x4A97E65188A950Dd4b0f21F9b5434dAeE0BBF9f5` | retired | recognised and shown; Send greyed out, saying why |

Both are pinned in `eth.serotine.data.safe.DelayDeployments`. The public
reports of the exploit do not name its root cause. Punicus never sends
through the relayed path described below, signs for it only Gnosis Pay's
card limit change through a Delay of the current mastercopy (*A change
signed for Gnosis Pay's app*), and checks the queue itself before an
execution (*Sending*).

## Detection

A Safe in the wallet on Gnosis Chain is a Gnosis Pay account when one of the
modules its verified state lists is the Safe's own Delay:

1. The module's code, proven by the light client, is byte for byte the
   EIP-1167 proxy of a pinned mastercopy.
2. Its getters, run as verified calls at one block (one number, one hash:
   the first call at the head, the others held to the block it answered at,
   since eight calls do not fit under one five-second head; a held call
   without an answer twice, or refused, is made at the head),
   say that its avatar, target and owner are the Safe. A Delay owned by
   anyone else could have its cooldown changed or its queue skipped by them:
   it is no Gnosis Pay account's, and nothing is sent through it.
3. Its cooldown, expiration, queue and members are read the same way.

The Safe is added as any Safe is: watched (*Watch an address*). No new way to
add an account exists. Its Owners tab then says it is a Gnosis Pay account
and opens the Gnosis Pay screen. That screen offers Send when a key that
controls the Delay by itself is held here on Gnosis Chain (*Control through a
member Safe*). The account then shows Full control, and its own Send opens
the Gnosis Pay screen; its Swap stays greyed out, since only plain transfers
go through the Delay. Otherwise the account's own Send and Swap stay greyed
out, since no owner can sign: a tap on Send says that its transfers go
through the Delay and offers the Gnosis Pay screen.
There, Send is greyed out, and a tap says what is missing: a controlling key;
the key added on Gnosis Chain; the key's watched entry on Gnosis Chain
removed and added again from the held one; the wallet's account list, which
cannot be read right now; who controls the Delay, not all verified yet; a
held key that acts only with co-owners; or that the module is on the retired
mastercopy.

### A read that did not answer

(Owner's decisions, 2026-11-17: "If there is an old state, that remains
until a new state has been confirmed", for an hour at most. The owner also
decided that a held value shows its age, for every value the app shows;
that comes with the app-wide change. Here the Gnosis Pay screen names the
block of the Safe's or the Delay's held answer, the older of the two, and
says that it could not be verified since; for an address on the way to the
keys, which carries no block on the screen, a note says that some are shown
as last verified.)

Each of these reads is made again at new heads, and one of them can find no
peer serving its proof that time while the light client is otherwise fine.
Who controls the account is read from many addresses at once (a member Safe
of nine owners: eleven), so if each such miss reset the answer, the account
would fall back to Watch-only every few heads and hardly ever leave it. So:

- An answer once verified stands, with the block it was verified at, while
  a later read of the same thing only did not answer: no proof could be had
  that time, or the light client is catching up. That holds for the Safe,
  each of its modules, and each address on the way down to the keys.
- **For an hour at most**, counted from the first read that did not answer.
  Then the answer is dropped and the account says what the read says, until
  one answers again. The hour is counted while the app runs: a desktop
  asleep does not count it down.
- A verified answer that differs replaces it at once: an owner removed, a
  threshold raised, a module disabled. One case is held all the same, for
  the hour: a Delay whose getters come to answer what no pinned Delay's do
  (a revert, more members than are read), which the adapter says as a read
  without an answer.
- A reason that stands replaces it too, and ends the hold: the network
  turned off, no engine, a stale checkpoint, a refusal.
- Before the first verified answer nothing is held: what is not read is said
  as not read. The hold is each screen's own: the list's and the Gnosis Pay
  screen's can differ for a moment after one of them is opened.
- **No transfer is planned on a held Safe or Delay.** The Safe's modules,
  which no transfer may go to, and the Delay's periods could have changed
  since: Review says that the account could not be verified at the latest
  block. Everything else a transfer rests on is read fresh as before: the
  queue, the balance, and the run as a verified call, which proves the sender
  and the Safes on its way (*Sending*). An execution rests on the fresh
  queue alone.
- Send stays enabled on a held reading, and Review says why it does not go
  on. Greyed out, it would flicker with every read that misses for a few
  seconds.

`ObserveGnosisPay` (domain) and `VerifiedDelayModules` (data) implement it.

## Control through a member Safe

(Owner's request, 2026-11-17: "it detects a GnosisPay Safe and finds all
indirectly controlling EOAs. If one of those EOAs' secret key is available
show the GnosisPay safe as full controls. Allow to make a tx to send funds".)

Accounts set up since the rollover of 2026-06-04 mostly have a Safe as the
Delay's member, not a key: a Safe of threshold 1, often with passkeys among
its owners. Punicus follows the members down to the keys:

- Each member's kind is proven by the light client: a key (no code, or an
  EIP-7702 delegation), a Safe (a pinned proxy of a pinned singleton), or
  other code. A Safe's owners are read the same way, in turn, at most three
  Safes deep. An account whose key is held here is a key without a read: a
  held key is an account with no Safe code. Addresses `0x…01` to `0x…ff`
  (the placeholder `0x…02`, the sentinel) are never read and control nothing.
- At most 32 addresses are read for one Delay, each a read the light client
  keeps proving while it is shown: the members share them evenly, and a
  Safe what is left of its share among its owners. Past them an address is
  not read, and listed as such; a key held here costs nothing and is found
  anyway. Anyone can build a wide tree on chain cheaply; this bounds what
  watching one costs, on the Gnosis Pay screen and for the list's badge.
- Other code (a passkey signer, a module, an unpinned Safe), a Safe older
  than 1.3.0 (it does not revert when its call fails, so a run through it
  proves nothing), and a Safe past three levels, or already on the way, are
  listed as not followed.
- What is not read yet, or cannot be verified, is never taken for "no key
  here", and neither is what was left unread past the 32: Send says which.
- **A key controls the account by itself** when every Safe on its way needs
  one signature. Then, held here on Gnosis Chain, it sends: the account shows
  Full control. A held key behind a Safe of a higher threshold is a
  co-signer only, and Send says so: Punicus does not gather co-owners'
  signatures for a Delay.
- The Gnosis Pay screen lists the members, what each is ("A Safe: 1 of its 9
  owners signs"), and the keys at the end of the ways down, each with the
  Safes it goes through and whether its key is on this device.

## Sending

A transfer from a Gnosis Pay account is two transactions from a key held here
that controls the Delay by itself: a member, or the owner of a member Safe
(*Control through a member Safe*). That key pays both fees in xDAI. The
shortest way is used: a key that is a member itself sends straight to the
Delay.

1. **Queue.** `execTransactionFromModule(to, value, data, CALL)` sent to the
   Delay, quoted, simulated and signed like any transaction from the key:
   at the normal fee level, which the review names, and never on a quote
   more than a minute old, as on Send. Through member Safes, the key sends it
   to the Safe it owns, and each Safe on the way executes the call of the one
   before it with `execTransaction`: no value, operation CALL, no gas refund
   (`safeTxGas`, `baseGas` and `gasPrice` 0, so the Safe reverts when its
   call fails), confirmed by a pre-validated signature of the owner that
   calls it (`r` = that owner, `v` = 1), which the Safe accepts because that
   owner is the account executing it. The run as a verified call proves the
   way: it reverts unless each Safe needs one signature and the caller is
   its owner, and unless the member Safe is a member of the Delay. The review
   names the Safes it goes through.
2. **Wait.** The cooldown runs from the block that queued it. The screen
   shows when the transfer can be executed, and until when.
3. **Execute.** `executeNextTx` with the same four fields, sent by the key
   that queued it, straight to the Delay: anyone may execute an entry. It is
   offered while that key is held here on Gnosis Chain, whatever the control
   tree says meanwhile: a member Safe's owners changed during the cooldown,
   or not read yet, never strand a transfer queued here. Before it is quoted, the Delay's verified state must show the
   transfer as the next entry (`txHash(txNonce)` equal to its hash), its
   cooldown passed and at least five minutes left before its expiration, so
   that the transaction is mined while the window is open. An entry ahead of
   it is shown, and Punicus waits: it never executes a transaction it did not
   queue. An expired entry ahead is skipped by the Delay's `skipExpired`
   (*Skipping expired entries*).

Every quote rests on one read of the Delay, fresh and at one block: its
code a proxy of the current mastercopy; its avatar, target and owner the
Safe; its nonces and periods; and its queue, searched from the next entry
for the transfer's hash through at most 8 entries. An entry further back is
said to be further back, never gone. A call is told by what the Delay tells
apart: the Safe, the Delay and the call's hash. A transfer whose call still
has an entry in the queue, whoever queued it, or whose queueing from here is
not mined yet, is refused, so no two entries share a hash. So is one whose
entry expired and was never skipped (it waits for `skipExpired`, which the
form then offers). Nothing is
queued while the Delay's next entry has expired, whoever queued it: until it
is skipped, nothing behind it can be executed. Nothing is queued while the
queue already holds 8 entries, as deep as it is read: one more could not be
found again to execute. Nothing is queued through a Delay whose expiration
is five minutes or less, the margin an execution keeps: Send is greyed out
there. The Safe's own transfer is run first, for xDAI as for a token: xDAI
to a contract that does not accept it would revert at every execution. An
execution is refused while one of the same call, sent from here, is not
mined yet: a second would revert once the first is. The Safe must
hold the amount beside what transfers of the same asset queued from here
still move. For a token, the Safe's own `transfer` must not answer false either: a token
that answers false instead of reverting would let the Delay spend its entry
and move nothing.

The queued transfers are found again in the history of what this wallet
sent, and the queue decides which still wait: a queueing not mined yet is
listed while it is on its way; once mined, only while the queue holds an
entry of its call (or may, further back, or cannot be read), and no longer
than its cooldown, its expiration and a day after its queueing, so that the
poll's reads follow the window, not the history. A call queued twice is
one row, the newest queueing's. One executed by
anyone, skipped, or never mined leaves the list, whether or not this device
saw how. The list is this device's: after a reinstall, or on another device,
a transfer queued before is not found, though the Delay's `TransactionAdded`
log holds its fields (open, the owner's to decide).

**Only plain transfers** are queued by Punicus itself: the Safe's xDAI as the value with no
data, or an ERC-20 `transfer(to, amount)` with no value; operation CALL;
never to the Safe itself, to one of its modules or to the Delay. The one
exception is a member of the Delay added or removed (*Adding and removing a
member*, below). Nothing else reaches the queue, so no Safe setting can
change through it, and the Safe refusals of `.claude/rules/trust.md` cannot
be bypassed.

**The relayed path is not used.** The Delay's `moduleOnly` also accepts a
member's EIP-712 signature appended to the calldata, which Gnosis Pay's
relayer sends and pays for. That needs an off-chain service, which the data
sources rule leaves out. Punicus only sends the held key's own transactions.

### A change signed for Gnosis Pay's app

(Owner's request, 2026-11-27: "This is a Gnosispay extend daily card limit to
8000€. Can you add support for this?")

Gnosis Pay's app, connected over WalletConnect, asks a member of the Delay to
sign the relayed path's EIP-712 message for its own relayer: account-kit's
`typedDataForModifierTransaction`, `ModuleTx(bytes data,bytes32 salt)` with
the domain `{chainId, verifyingContract}` the Delay. Punicus contacts no
relayer: it signs, on D5, what the dApp asked, and the dApp sends it.

- **Read exactly** (`ModuleTransaction`): `data` as the Delay's
  `execTransactionFromModule(to, value, data, operation)`, the call the Safe
  makes once the queued entry is executed after the cooldown.
- **Signed only for the card's limit** (account-kit's
  `populateLimitEnqueue`): a Roles v2 `setAllowance(key, balance, maxRefill,
  refill, period, timestamp)` under `keccak256("SPENDING_ALLOWANCE")`, no
  value, operation CALL, to neither the Delay nor the Safe (the account's
  bouncer, account-kit's prediction, not checked here), on Gnosis Chain.
  Shown as the card's spending limit per period, in the card's currency at 18
  decimals (EURe, GBPe), every value raw beside it.
- **Through a verified Delay only**: the contract the message names must be
  the Delay of a Gnosis Pay account in this wallet, as the light client
  reads it (`GnosisPayDelayCheck`: a proxy of the current mastercopy, its
  avatar, target and owner the Safe), with the signer one of its members.
  The screen says so, with the Delay's cooldown and window; anything else
  greys Sign out, saying why. The check is made again when it is signed.
- **Anything else** asked this way is shown, decoded as a Safe transaction
  is with its own warnings, and refused: nothing but the card's limit is
  signed for the relayer, as nothing else but plain transfers and member
  changes is queued here.

## Skipping expired entries

(Owner's request, 2026-11-18: "Handle expired request with skipExpired".)

The queue is first in, first out, and `executeNextTx` reverts on an expired
entry: one entry nobody executed in time, from Gnosis Pay's own app as from
here, blocks every entry behind it for good. The Delay's own
`skipExpired()` moves its next entry past every expired one, stopping at the
first that has not expired. Anyone may call it, and it moves nothing.

- It is offered where an expired entry blocks the user: on the Send form
  when Review was refused for it, and on a transfer queued from here that
  waits behind it ("Skip expired entries"; that transfer's Execute is
  greyed out meanwhile, saying why).
- It is the transaction of a key held here on Gnosis Chain, which pays its
  fee: from a queued transfer's row, the key that queued it, where it is
  held; then a controlling key; else any. No control of the account is
  needed, since the call moves nothing.
- Before it is quoted, the Delay's queue is read fresh, which proves the
  Delay the Safe's own of the current mastercopy, and its next entry must
  have expired by that head; a skip of the same Delay from here not mined
  yet is refused for as long as a sender's record holds its nonce, three
  hours. Its gas limit has room to skip every entry still waiting, since
  more may expire by the block that mines it. The call is then quoted, run
  as a verified call, and reviewed and signed like a queueing. The review
  says that it moves nothing, and that every expired entry from the next on
  can never be executed afterwards.
- It is recorded as a skip (`SentAction.DelaySkip`) and listed in the
  account's activity.

## Adding and removing a member

(Owner ruling, 2026-11-18. It amends *Only plain transfers* above, and "It
enables none" of `.claude/rules/trust.md`, for this one case. The owner's
request of the same day puts the actions in the Safe's settings.)

A member of the Delay is a module enabled on it. Adding or removing one is
the Safe's own call of its Delay, which only the Delay's owner, the Safe, may
make. So it goes through the Delay like a transfer: the queued entry is
`{to: the Delay, value 0, data, CALL}`, where `data` is

- `enableModule(member)` (`0x610b5925`) to add one, or
- `disableModule(previous, member)` (`0xe009cfde`) to remove one, `previous`
  the member before it in the Delay's linked list, the sentinel `0x…01` for
  the first. It is taken from the members as read verified, in the Delay's
  own order (`getModulesPaginated(0x…01, …)`).

The rules:

1. **Only on the account's own Delay of the current mastercopy**, by a key
   that controls it here (the same rule as Send: held here on Gnosis Chain
   and acting alone, as a member or through member Safes of threshold 1),
   queued, then executed after the cooldown exactly like a transfer, with the
   same queue checks before the execution (*Sending*).
2. **Add.** A new member can move all the account's funds after the
   cooldown, so the form and the review say so in plain words. Refused: the
   zero address and every address `0x…01` to `0x…ff` (the sentinel and the
   placeholder owner among them), the Safe itself, the Delay, any of the
   Safe's enabled modules, and an existing member.
3. **Remove.** Refused for the last member: the Safe's one owner is the
   placeholder, so with no member the funds are locked for good. Warned, not
   refused, when the member removed is the way this device controls the
   account (the held key itself, or the member Safe it reaches the Delay
   through): the review says that once it is executed this device can no
   longer send from the account, or that it then controls it only through the
   others, when another way remains.
4. **Never through a Delay whose entries never expire** (owner's decision,
   2026-11-18, on #323). With an expiration of 0, adding and removing a
   member are refused, at the queueing and at the execution, and greyed out
   in the Safe's settings and on the Gnosis Pay screen, saying why. A change
   that can no longer run there (a change of the members queued elsewhere
   ahead of it: both removing the same member, or theirs leaving ours the
   last) would stay the queue's next entry for good, since nothing skips an
   entry that never expires, and every entry behind it, transfers included,
   could never be executed. Transfers through such a Delay stay allowed: one
   that cannot run is cured by funding the Safe.
5. **Nothing else** beyond plain transfers and these two calls is ever
   queued, but for the card limit change signed for Gnosis Pay's relayer
   (*A change signed for Gnosis Pay's app*). *Enabling a Safe module is refused* (a module on the Safe itself)
   stands unchanged.

How it is checked:

- **The quote of the queueing** reads the queue fresh, as for a transfer,
  with the Delay's members at the same block: AlreadyQueued, HeadExpired,
  QueueTooLong and WindowTooShort apply as they do to a transfer. The change
  is planned again against those members; a refusal it now meets is said, and
  a removal whose `previous` is another now is said to need planning again.
  The Safe's own call of its Delay is run as a verified call from the Safe,
  and must not revert. No balance of the Safe's is checked: it moves nothing.
  The key's own transaction is quoted as for a transfer.
- **One change of the members at a time from here.** While another one
  queued from this device still waits, a new one is refused: each is planned
  against the members as they are. It waits while its queueing is on its way,
  or while a fresh read of its own hash finds its entry, not expired, or
  cannot tell; never by its age alone, which on a Delay with no expiration
  would hold every later change back for good once anyone executed it. One
  queued elsewhere is not seen (the queue holds hashes only), and the checks
  at its execution stand for it.
- **A removal refused for an expired entry** ahead in the queue offers Skip
  expired entries on the Gnosis Pay screen's overview, since a removal has no
  form (*Skipping expired entries*).
- **The execution** reads the queue again with the members. A removal that
  would leave the Delay with no member, whoever removed the others
  meanwhile, is refused. The Safe's own call is run again: a removal whose
  `previous` went stale (a member added since goes first in the list), or a
  member added or removed already, reverts, and the screen says so. Such an
  entry is not executed; it expires, and can then be skipped (*Skipping
  expired entries*). That holds only where the Delay's entries expire, which
  is why no member is changed through one whose entries never do (rule 4).
- **The record** keeps the change as it was queued: the Safe, the Delay,
  added or removed, the member and `previous` (`FileSentTransfers` format 12;
  a record of an earlier build reads as before). Queued from this device, it
  is listed with the transfers ("Add member 0x…", "Remove member 0x…") and
  executed from there.

**Where it is offered.** The Safe's settings (its Owners tab's *Safe
settings*) list the Delay's members for a Gnosis Pay account: each member's
address, its name in the wallet, what it is, and whether a key on this
device controls the account through it; then **Add a member**, and
**Remove** per member. Both are greyed out with the reason where they cannot
be done here: Send's reasons, and the last member for Remove (owner ruling,
2026-10-18, #55). A tap opens the Gnosis Pay screen at the add form or at the
removal's review, where it is signed with the same unlock as Send, and Back
returns to the settings. The Gnosis Pay screen's own list of members stays as
it is.

## Delivery

On `feature/gnosis-pay`, in parts:

1. The rules, this page, and detection: the domain port and use case, the
   pinned mastercopies and the engine adapter, with tests.
2. Queue and execute: the calldata, the use cases over the shared quote and
   send, the record of a queued transfer, and the check of the queue.
3. The screens and the wiring: the account's Gnosis Pay card, Send through
   the Delay with its wait, previews, and the hosts' composition roots.

Then, on its own branch: a member of the Delay added or removed (*Adding and
removing a member*): the calls and their refusals (`DelayMemberChange`), the
queue read with the members at its block, the quotes and the record of a
change, the Safe settings' list of members, and the add form and the reviews
on the Gnosis Pay screen.
