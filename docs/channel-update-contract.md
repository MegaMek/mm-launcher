# Fixed-channel and update-check contract

Each official managed installation receives exactly one immutable **Milestone**, **Development**,
or **Weekly** channel when its verified fresh-install or adoption transaction publishes it.
All three follow the explicit membership in published complete suite records. Milestone remains
the new-install default; Weekly is not Nightly. Core Rules, extras, GitHub
titles, prerelease flags, “latest” ordering, tag parity, and a manually selected install tag never
infer a channel. To use another channel, the user chooses **Install another version** and creates a
separate root; an existing installation is never retargeted or cross-channel downgraded.

The fixed track is schema-versioned launcher provenance beside ownership receipts, outside every
application root. It is bound to installation UUID, canonical root, and registration time. Strict
JSON parsing rejects unknown, duplicate, missing, and null fields. Initialization requires the
exact current ownership receipt and happens once under a per-record lock with create-new staging,
flush, and atomic publication. An idempotent retry may accept only the same binding and channel;
a different channel fails with an instruction to install another managed copy. Existing valid
sidecars retain their exact semantics and bytes on read. Corrupt, unreadable, interrupted, stale,
missing, and unavailable state is never reset, inferred, or manually assigned.

The only mutable field is `checkOnOpen`. Its separate setter requires the exact previously read
preference, revalidates registry binding under the same lock, and atomically changes only that
boolean. It fails closed for missing/corrupt/stale state. Imported and pre-launcher folders have
neither ownership receipt nor fixed channel, remain launch-only, and cannot gain update
eligibility through a channel setting alone. The separate explicit adoption transaction must
first reconstruct and verify one exact official ancestor; it is not a channel mutation API.
Registry application preferences, launcher-wide Game Java, pin, compatibility default, receipt, current provenance,
and installed bytes are unchanged by the check toggle. Apply/recovery preserve the fixed channel.

## Fixed official source

The production adapter discovers JSON record assets through the official release inventory:

`https://api.github.com/repos/MegaMek/megamek/releases`

Schema-1 `suite-record-<canonical-version>.json` assets are parsed strictly: duplicate, unknown,
missing, invalid, or trailing fields/documents are rejected. Discovery is bounded to 100 pages of
50 releases, 1 MiB per record, and 32 MiB of aggregate record bodies; reaching a pagination bound
is an error, never a partial success. Drafts and unrelated assets are not records. Release tag,
filename, content identity, positive immutable IDs, and uploaded asset state must agree.
The numerically greatest suite version with the selected membership is authoritative.
There is no website YAML, GitHub latest-release, prerelease, or inferred-membership fallback.

Before exposing any product, all three product references are resolved by exact release ID and
checked for the recorded tag and unique eligible archive's ID, name, size, state, and SHA-256.
The record's SHA-256 is mandatory; an absent GitHub digest does not discard that authority, and
a conflicting or malformed GitHub digest fails. Optional minimum launcher requirements are
checked against the running launcher's generated version resource. Missing or incompatible
channels are explicitly unavailable, without hiding independently available channels.
The programs are independently versioned (`MegaMek <= MegaMekLab <= MekHQ <= suite`); the offered
tag/version belongs to the selected product, not necessarily to the suite record. Reused product
artifacts keep their exact identity. A MekHQ installation still downloads only its complete
MekHQ bundle, not three independently selected packages.

A check makes metadata requests only. It never requests package bytes or writes installed files,
registry, receipts, or current provenance. JSON record bodies are metadata, not game packages.
HTTP errors, malformed/oversized records, unavailable references, malformed digests, unsafe URLs, and API
errors are explicit Unavailable results. Metadata and packages are both obtained from GitHub over
HTTPS. A published digest is **not independent signing**. Record-authorized packages require the
record's SHA-256 even when GitHub omits its digest. Only advanced exact-release or legacy-ancestor
adoption routes may compute local identity for an older official asset without a published digest,
from the one exact size-bounded transfer; this does not add independent authenticity.

Successful imported-copy adoption chooses Milestone (default), Development, or Weekly exactly once and
publishes that fixed preference only after the local copy has matched one exact official
ancestor. Static inspection recognizes independent per-program versions and reports the primary
bundle's version (MekHQ, otherwise Lab, otherwise MegaMek); this is not proof of official ownership.
Automatic adoption compares that normalized observed identity with each available channel's current
product version. A selected-current match is allowed, a shared identity is valid for its matching
channels, and a distinct opposite-current-only match is rejected as a typed channel
mismatch before package download. An identity matching no current product target remains
historical/unknown and may be adopted into the user-selected fixed channel. Title, prerelease flag,
and ordering never classify that historical identity. Failed or cancelled adoption publishes no
channel. Nightly remains unsupported, and there is no adoption-based channel switching.

The source hierarchy is one-directional: the canonical release index is authoritative for release
identity and historical/current channel membership; a build-produced embedded manifest is an
identity projection used to find or check a candidate; and the launcher's receipt/adoption record
and fixed-channel sidecar are local ownership and policy. A marker, filename, displayed build, or
local sidecar alone never proves an official ancestor.

## Comparison and action

The installed side is the latest verified `CurrentUpdateState.tag`, not the original receipt or
display-only inspection. Only exact `v`-prefixed numeric release tags with three or four components
are ordered, using numeric components with leading zeros handled safely. Exact tag identity is
Current; older is Update available; newer is Installed ahead with no recommended downgrade.
Equal numeric values with different tag identities, suffixes, and unsupported forms are
Non-comparable rather than silently current or downgraded. Unconfigured and check failures are
never “up to date.”

Recommended Update captures installation binding, fixed channel provenance, fixed source,
repository, release/asset IDs, target tag, asset name, size, mandatory record digest, and notes URL. Preparation
and Apply revalidate the captured source and metadata; a change requires a new attempt and consent.
The route first shows a simple styled update consent with the application, current and new
versions, and rounded MiB download size. Its copy and **Update** action authorize download,
verification, internal planning, and Apply as one complete operation. Cancel, Escape, or window
close fetches no package body; Update is the default action. It downloads the exact full package
once, retains it only for that active attempt, prepares and verifies the plan internally, then
immediately calls Apply with the existing explicit backend confirmation token rather than asking
for a second UI authorization. Under the captured root gate, Apply refreshes the
channel/preference/target metadata and re-verifies/re-extracts the retained archive; it never
silently downloads a second package or retargets. The advanced exact-release path remains
available without changing the fixed channel. Clean success silently reloads Installations;
preserved decisions do not create a completion dialog, while a cleanup warning remains visible in
the styled progress surface.

For a new installation, **Fetch releases** performs bounded record discovery and shows one page
of product targets referenced by records of the selected membership. Reused tags are deduplicated
by their newest referencing record. Page one contains the current product exactly once; older
rows are known channel history, not guessed membership. The current record and displayed records'
three-product references must validate. Browsed choices retain their exact record asset source
through planning and transfer revalidation, so a newer record cannot silently retarget them.
Explicit advanced CLI exact-release and legacy-ancestor adoption routes remain available, but are
not channel discovery or a fallback that assembles a suite. See [the CI update proposal](../ci_update.md).

Channel metadata checks remain outside the package-operation gate and retain their existing
startup/manual behavior. A package download started from a recommendation uses the shared typed
progress/cancellation dialog and the same atomic transaction cutoff as manual Update while
preserving the one-full-package-body attempt. A late cancellation cannot interrupt the transaction
or retarget the recommendation.

Check failures are eligible for asynchronous sanitized local error logging in
`<registry-name>.launcher-logs/`; showing a failure never waits for disk I/O. No check or log is
uploaded, opened in a browser, attached to an issue/project, or used to change fixed-channel
metadata.
An unsafe/corrupt registry prevents log creation and reports that logging failure without changing
the original **Unable to check** result.

The main first-run **Install latest MekHQ Milestone** action explicitly persists Milestone for the MekHQ
repository. Its eight split-menu routes cover MegaMek/MegaMekLab Milestone and
MekHQ/MegaMek/MegaMekLab Development and Weekly; each persists exactly its displayed channel while sharing
the same verified normal installer. The menu has no redundant MekHQ Milestone or exact-picker row.
Opening the styled menu while versions are loading or available starts no request and changes no
channel, Main selection, or setting. Unavailable rows are disabled with explicit reasons; only
the Retry command refreshes an unavailable/partially available snapshot. A Weekly-only bootstrap
enables Weekly rows while leaving the Milestone primary disabled. The deferred
current-or-history picker remains on Installations after a copy exists or in relevant problem
navigation. Normal, exact historical, CLI, and successfully adopted managed installations always
initialize their per-installation check-on-open value to true. There is no configurable global or
new-install default. The normal quote binds its exact repository/product set/channel/source
version and registry/default snapshot without carrying a check preference or Settings revision.
This is a pre-release schema break, not a migration: every existing valid channel preference is
read as written, while old launcher Settings schemas are rejected rather than migrated.
Absent/corrupt/unknown legacy channels remain Unknown or Unavailable and launch-only with no
inference or assignment path. The CLI fresh installer requires a channel and initializes its
check-on-open value to true.

Eligible opt-in checks run serially and independently off the event-dispatch thread, do not disable
healthy launch or page navigation, are cancelled on disposal or full state reload, and discard results after
Main, record, fixed-track setting, or package changes. Off, unknown, corrupt, and launch-only copies are not
reported as checked. Manual Installations checks may inspect the selected copy regardless of its
on-open switch. There is no automatic Apply, automatic downgrade, launcher self-update, Nightly
support, component download, game launch, or website/game CI change in this contract.

The per-installation `ChannelPreference.checkOnOpen` boolean is the sole automatic-check
authority. There is no launcher-wide gate. The card checkbox saves only that boolean immediately;
it never changes channel/binding/receipt and never cancels a check already running. Manual
per-card Check/Retry remains available for eligible managed records.
Home aggregates physical-installation results and cannot call unchecked, unavailable,
non-comparable, or imported launch-only records current. There is no bulk update.
