# Channel selection and update-check contract

Each registered installation has an independent, explicit **Milestone** or **Development**
preference. Milestone maps only to the official website's `stable` scalar and Development maps
only to `dev`. Nightlies, Core Rules, extras, GitHub titles, prerelease flags, “latest” ordering,
tag parity, and a manually selected install tag are not channels and never infer user intent.
Pre-existing and legacy-CLI records remain Unknown until explicitly configured.

The preference is schema-versioned JSON beside ownership receipts, outside every application root.
It is bound to installation UUID, canonical root, and registration time. Strict JSON parsing rejects
unknown, duplicate, missing, and null fields. Publication uses a per-record try-lock, create-new
staging, flush, and atomic replacement. A corrupt, unreadable, interrupted, or stale sidecar is
Unavailable rather than absent and is not reset. This metadata is not provenance: selecting a
channel neither authorizes an imported copy nor changes registry schema 1, selected Java, pin,
default selection, receipt, current provenance, or installed bytes. Apply/recovery leave it alone.

## Fixed official source

The replaceable production adapter reads only:

`https://raw.githubusercontent.com/MegaMek/megamek.github.io/main/_data/current_releases.yml`

The bounded safe YAML parser requires one scalar mapping, exactly one valid three- or
four-component numeric `stable` value and one `dev` value, rejects duplicates, aliases, anchors,
custom objects, excessive nesting/size, and structured fields, and tolerates unrelated scalar
fields. It constructs the exact `v` + version tag and asks the existing `ReleaseCatalog` for that
tag in the installation's already verified repository. Existing assessment rules require one
correctly named full-bundle asset with a positive bounded size, SHA-256 digest, and exact official
URL. A MekHQ receipt therefore checks only the MekHQ bundle; Core Rules never becomes a channel or
a mixed bundle.

A check makes metadata requests only. It never requests package bytes or writes installed files,
registry, receipts, or current provenance. HTTP errors and redirects from the fixed YAML endpoint,
malformed/oversized YAML, unavailable exact tags, invalid/missing digests, unsafe URLs, and API
errors are explicit Unavailable results. Metadata and packages are both obtained from GitHub over
HTTPS; the same-source digest detects corruption but is **not independent signing**.

## Comparison and action

The installed side is the latest verified `CurrentUpdateState.tag`, not the original receipt or
display-only inspection. Only exact `v`-prefixed numeric release tags with three or four components
are ordered, using numeric components with leading zeros handled safely. Exact tag identity is
Current; older is Update available; newer is Installed ahead with no recommended downgrade.
Equal numeric values with different tag identities, suffixes, and unsupported forms are
Non-comparable rather than silently current or downgraded. Unconfigured and check failures are
never “up to date.”

Changing channel only changes what is watched. Recommended Update captures installation binding,
preference, fixed source, repository, target tag, asset name, size, digest, and notes URL. Preview
and Apply revalidate the captured source and metadata; a change requires new consent. The route
still uses the existing read-only full-package preview and separate explicit Apply confirmation.
The advanced exact-release path remains available without changing the preference.

GUI startup has no network by default. A per-copy check-on-open preference is off until explicitly
enabled. Opt-in checks run independently off the event-dispatch thread, do not disable healthy
Launch/Manage actions, are cancelled on disposal, and discard results after home, record,
preference, or package changes. There is no automatic Apply, automatic downgrade, launcher
self-update, Nightly support, component download, game launch, or website/game CI change in this
contract.
