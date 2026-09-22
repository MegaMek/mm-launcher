# Fixed-channel and update-check contract

Each official managed installation receives exactly one immutable **Milestone** or
**Development** channel when its verified fresh-install or adoption transaction publishes it.
Milestone maps
only to the official website's `stable` scalar and Development maps only to `dev`. A future
Nightly channel will likewise require a separate managed installation. Core Rules, extras, GitHub
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

The replaceable production adapter reads only:

`https://raw.githubusercontent.com/MegaMek/megamek.github.io/main/_data/current_releases.yml`

The bounded safe YAML parser requires one scalar mapping, exactly one valid three- or
four-component numeric `stable` value and one `dev` value, rejects duplicates, aliases, anchors,
custom objects, excessive nesting/size, and structured fields, and tolerates unrelated scalar
fields. It constructs the exact `v` + version tag and asks the existing `ReleaseCatalog` for that
tag in the installation's already verified repository. Existing assessment rules require one
correctly named full-bundle asset with a positive bounded size, an optional valid SHA-256, and exact official
URL. A MekHQ receipt therefore checks only the MekHQ bundle; Core Rules never becomes a channel or
a mixed bundle.

A check makes metadata requests only. It never requests package bytes or writes installed files,
registry, receipts, or current provenance. HTTP errors and redirects from the fixed YAML endpoint,
malformed/oversized YAML, unavailable exact tags, malformed digests, unsafe URLs, and API
errors are explicit Unavailable results. Metadata and packages are both obtained from GitHub over
HTTPS. A published digest remains preferred but is **not independent signing**. For an older
official asset without one, the launcher computes local identity only from the one exact,
size-bounded transfer; this does not add independent authenticity.

Successful imported-copy adoption chooses Milestone (default) or Development exactly once and
publishes that fixed preference only after the local copy has matched one exact official
ancestor. Automatic adoption first compares the normalized observed identity with both canonical
current pointers. A selected-current match is allowed, a shared stable/dev identity is valid for
either selection, and a distinct opposite-current-only match is rejected as a typed channel
mismatch before package download. An identity matching neither current pointer remains
historical/unknown and may be adopted into the user-selected fixed channel. Title, prerelease flag,
and ordering never classify that historical identity. Failed or cancelled adoption publishes no
channel. Nightly adoption remains disabled until the durable Nightly identity/history contract
exists, and there is no adoption-based channel switching.

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
repository, target tag, asset name, size, digest, and notes URL. Preparation
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

For a new installation, one **Fetch releases** action reads both fixed YAML pointers and one
bounded GitHub history page for the selected product. It includes the selected current identity
and unknown history, excludes a distinct identity known only as the other current channel target,
and includes a shared stable/dev identity for either selection. Title and `prerelease` remain
mutable and do not record later promotion (including the known 0.51.0 promotion case), so most
history is intentionally unknown and appears under both channel selections. The selected Channel
becomes only the created installation's fixed update channel; it is not evidence that an unknown
release belonged to that channel. Every chosen row re-enters the normal planner through its exact
repository/tag for metadata, destination, registry, asset URL/name/size/digest validation before
consent and again before package transfer. See `../ci.md` for the immutable channel history target
state.

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
repository. Its five split-menu routes cover MegaMek/MegaMekLab Milestone and
MekHQ/MegaMek/MegaMekLab Development; each persists exactly its displayed channel while sharing
the same verified normal installer. The menu has no redundant MekHQ Milestone or exact-picker row.
Opening the styled menu while versions are loading or available starts no request and changes no
channel, Main selection, or setting. Closing and reopening after unavailable rows have been
viewed is the explicit snapshot retry and still changes no installation state. The deferred
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
