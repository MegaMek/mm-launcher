# Fixed-channel and update-check contract

The MegaMek suite (MegaMek, MegaMekLab and MekHQ) ships as one version on two channels:

- **Core** is for playing. It is built from `releases/0.MM.LL` branches and is the default.
- **Experimental** helps us test and may break things. It is built from `main` weekly and on
  demand, and it is opt-in.

Versions are `0.MM.LL.B`. The build counter `B` is unpadded and shared by both channels on a line:
for example Experimental `0.51.02.1` to `0.51.02.32`, then Core `0.51.02.33`, after which
Experimental moves to `0.51.03.1`.

Each official managed installation receives exactly one immutable **Core** or **Experimental**
channel when its verified fresh-install or adoption transaction publishes it. Only the `channel`
field of a published suite record assigns a release to a channel. GitHub titles, prerelease flags,
“latest” ordering, version numbers, and a manually selected install tag never infer one.
Experimental GitHub releases are prereleases, but the launcher never reads that flag. To use the
other channel, the user installs a separate managed copy; an existing installation is never
retargeted or cross-channel downgraded.

The fixed channel is schema-versioned launcher provenance beside ownership receipts, outside every
application root. The current sidecar schema is 2. It is bound to installation UUID, canonical
root, and registration time. Strict JSON parsing rejects unknown, duplicate, missing, and null
fields. Initialization requires the exact current ownership receipt and happens once under a
per-record lock with create-new staging, flush, and atomic publication. An idempotent retry may
accept only the same binding and channel; a different channel fails with an instruction to install
another managed copy. Valid sidecars retain their exact bytes on read. Corrupt, unreadable,
interrupted, stale, missing, and unavailable state is never reset, inferred, or manually assigned.

There is no compatibility with earlier launchers. A schema-1 sidecar from the retired Milestone,
Development, or Weekly channels is read as **Retired**: the copy stays launch-only, its sidecar is
never rewritten or migrated, and a check reports `RETIRED_CHANNEL` (**Retired channel; launch
only**) without a network request or retry. To get updates, install a new Core or Experimental
copy.

The only mutable field is `checkOnOpen`. Its separate setter requires the exact previously read
preference, revalidates registry binding under the same lock, and atomically changes only that
boolean. It fails closed for missing/corrupt/stale/retired state. Imported and pre-launcher folders
have neither ownership receipt nor fixed channel, remain launch-only, and cannot gain update
eligibility through a channel setting alone. The separate explicit adoption transaction must
first reconstruct and verify one exact official ancestor; it is not a channel mutation API.
Registry application preferences, launcher-wide Game Java, pin, compatibility default, receipt,
current provenance, and installed bytes are unchanged by the check toggle. Apply/recovery preserve
the fixed channel.

## Fixed official source

The production adapter discovers JSON record assets through the official release inventory:

`https://api.github.com/repos/MegaMek/megamek/releases`

Only schema-2 `suite-record-<version>.json` assets are accepted, parsed strictly: duplicate,
unknown, missing, invalid, or trailing fields/documents are rejected. A record's `channel` is
`core` or `experimental`; its `version` has exactly four components; and every product's version
and tag equal the suite version and tag, with the archive named `<Product>-<version>.tar.gz`.
There are no independent product versions and no reused product artifacts. Retired schema-1
records are skipped rather than failing discovery; any other schema is an error. Discovery is
bounded to 100 pages of 50 releases, 1 MiB per record, and 32 MiB of aggregate record bodies;
reaching a pagination bound is an error, never a partial success. Drafts and unrelated assets are
not records. Release tag, filename, content identity, positive immutable IDs, and uploaded asset
state must agree. For each channel, the record with the numerically greatest four-part version is
authoritative, so a Core install follows new lines automatically (`0.51.01.x` to `0.51.02.x`).
There is no website YAML, GitHub latest-release, prerelease, or inferred-channel fallback.

Before exposing any product, all three product references are resolved by exact release ID and
checked for the recorded tag and unique eligible archive's ID, name, size, state, and SHA-256.
The record's SHA-256 is mandatory; an absent GitHub digest does not discard that authority, and
a conflicting or malformed GitHub digest fails. Optional minimum launcher requirements are
checked against the running launcher's generated version resource. Missing or incompatible
channels are explicitly unavailable, without hiding the other channel when it is available.
A successfully read inventory with no record for the followed channel is the distinct
`CHANNEL_NOT_PUBLISHED` check state, not a failed request or an up-to-date result.
Home and Installations say **Core/Experimental update information is not available yet**
and retain an explicit retry. CLI checks report `status=channel_not_published`.
Malformed, incomplete, incompatible, or unreadable metadata remains a check failure; it
never becomes this empty-inventory state or authorizes a fallback target.
A MekHQ installation still downloads only its complete MekHQ bundle, not three separately
selected packages.

A check makes metadata requests only. It never requests package bytes or writes installed files,
registry, receipts, or current provenance. JSON record bodies are metadata, not game packages.
HTTP errors, malformed/oversized records, unavailable references, malformed digests, unsafe URLs,
and API errors are explicit Unavailable results. Metadata and packages are both obtained from
GitHub over HTTPS. A published digest is **not independent signing**. Record-authorized packages
require the record's SHA-256 even when GitHub omits its digest. Only advanced exact-release or
legacy-ancestor adoption routes may compute local identity for an older official asset without a
published digest, from the one exact size-bounded transfer; this does not add independent
authenticity.

Successful imported-copy adoption chooses Core (default) or, when the player has turned on
**Show Experimental releases**, Experimental exactly once, and publishes that fixed preference
only after the local copy has matched one exact official ancestor. Static inspection reports the
primary bundle's version (MekHQ, otherwise Lab, otherwise MegaMek); this is not proof of official
ownership. Automatic adoption compares that normalized observed identity with each channel's
current suite version. A selected-current match is allowed, and a match with only the other
channel's current version is rejected as a typed channel mismatch before package download. An
identity matching no current target remains historical/unknown and may be adopted into the
user-selected fixed channel. Title, prerelease flag, and ordering never classify that historical
identity. Failed or cancelled adoption publishes no channel, and there is no adoption-based
channel switching. A successfully read complete inventory containing no suite records has no
current identities: automatic adoption logs that condition and still searches for one exact
official ancestor. It does not manufacture a channel or an update target. The full package
verification and local file comparison still apply, and checks remain `CHANNEL_NOT_PUBLISHED`
until a valid record for the selected channel is published. Inventory errors still stop adoption.

The source hierarchy is one-directional: the published suite records are authoritative for release
identity and channel; a build-produced embedded manifest is an identity projection used to find or
check a candidate; and the launcher's receipt/adoption record and fixed-channel sidecar are local
ownership and policy. A marker, filename, displayed build, or local sidecar alone never proves an
official ancestor.

## Release-day migration from historical packages

Publish and validate the first Core and Experimental records before telling users to migrate to
the launcher. Existing non-suite releases do not need retroactive records or modified archives:
import remains launch-only until explicit adoption verifies the exact historical official
package. The user selects the fixed future channel; historical titles/prerelease flags
do not assign it. Once adopted, the old verified three-part tag is older than that channel's
four-part record, so the normal recommended update is offered.

The release-day regression covers imports of old `v0.50.06` and `v0.50.07` packages
following Core and Experimental respectively, against newly published channel metadata,
through automatic ancestor matching, adoption, recommended preparation and Apply. It verifies
in-place identity, unchanged channel/check preferences, preserved saves, custom content,
configuration and local data overrides, updated runtime/new managed files, and an exact-current
check afterward. Each attempt downloads its old ancestor once and its new update once;
Apply reuses the retained update. This is local fixture coverage, not evidence that live
records or release assets have been published.

## Comparison and action

The installed side is the latest verified `CurrentUpdateState.tag`, not the original receipt or
display-only inspection. Only exact `v`-prefixed numeric release tags with three or four components
are ordered (a missing fourth component counts as zero), using numeric components with leading zeros handled safely. Exact tag identity is
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
of product targets referenced by records of the selected channel, newest first. Page one contains
the current product exactly once; older rows are that channel's history. The current record and
displayed records' three-product references must validate. Browsed choices retain their exact
record asset source through planning and transfer revalidation, so a newer record cannot silently
retarget them. The picker offers Core only unless **Show Experimental releases** is on.
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

The main first-run **Install latest MekHQ Core** action explicitly persists Core for the MekHQ
repository. By default its split menu adds only MegaMek Core and MegaMekLab Core. Settings has a
**Show Experimental releases** option, off by default and stored in the launcher settings file
(schema 5), with the warning that Experimental builds may break things. When it is on, the menu
also offers MekHQ, MegaMek, and MegaMekLab Experimental. The option only widens what is offered;
it never changes an installed channel. Changing it discards the cached quick-install snapshot so
the next Home visit loads the matching choices. Each route persists exactly its displayed channel
while sharing the same verified normal installer. The menu has no redundant MekHQ Core or
exact-picker row. Opening the styled menu while versions are loading or available starts no
request and changes no channel, Main selection, or setting. Unavailable rows are disabled with
explicit reasons; only the Retry command refreshes an unavailable/partially available snapshot.
With Experimental shown, an Experimental-only bootstrap enables Experimental rows while leaving
the Core primary disabled. The deferred current-or-history picker remains on Installations after
a copy exists or in relevant problem navigation. Normal, exact historical, CLI, and successfully
adopted managed installations always initialize their per-installation check-on-open value to
true. There is no configurable global or new-install default. The normal quote binds its exact
repository/product set/channel/source version and registry/default snapshot without carrying a
check preference or Settings revision. Settings schemas 3 and 4 hold no channel data; they read
with **Show Experimental releases** off and are rewritten as schema 5 on the next change. Older
Settings schemas are rejected rather than migrated. Absent/corrupt/unknown channels remain Unknown
or Unavailable and launch-only with no inference or assignment path. The CLI fresh installer
requires `--channel core` or `--channel experimental` and initializes its check-on-open value to
true.

Eligible opt-in checks run serially and independently off the event-dispatch thread, do not disable
healthy launch or page navigation, are cancelled on disposal or full state reload, and discard results after
Main, record, fixed-track setting, or package changes. Off, unknown, corrupt, and launch-only copies are not
reported as checked. Manual Installations checks may inspect the selected copy regardless of its
on-open switch. There is no automatic Apply, automatic downgrade, launcher self-update, component
download, game launch, or website/game CI change in this contract.

The per-installation `ChannelPreference.checkOnOpen` boolean is the sole automatic-check
authority. There is no launcher-wide gate. The card checkbox saves only that boolean immediately;
it never changes channel/binding/receipt and never cancels a check already running. Manual
per-card Check/Retry remains available for eligible managed records.
Home aggregates physical-installation results and cannot call unchecked, unavailable,
non-comparable, or imported launch-only records current. There is no bulk update.
