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
channel neither authorizes an imported copy nor changes registry schema 2 application
preferences, selected Java, pin, compatibility default, receipt, current provenance, or installed
bytes. Apply/recovery leave it alone.

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
and Apply revalidate the captured source and metadata; a change requires a new attempt and consent.
The route downloads its exact full package once, retains it only for that active attempt, shows the
read-only report, then uses a separate explicit Apply confirmation. Under the captured root gate,
Apply refreshes the channel/preference/target metadata and re-verifies/re-extracts the retained
archive; it never silently downloads a second package or retargets. The advanced exact-release path
remains available without changing the preference.

Channel metadata checks remain outside the package-operation gate and retain their existing
startup/manual behavior. A package download started from a recommendation uses the shared typed
progress/cancellation dialog and the same atomic transaction cutoff as manual Update while
preserving the one-full-package-body attempt. A late cancellation cannot interrupt the transaction
or retarget the recommendation.

Check failures are eligible for asynchronous sanitized local error logging in
`<registry-name>.launcher-logs/`; showing a failure never waits for disk I/O. No check or log is
uploaded, opened in a browser, attached to an issue/project, or used to change channel metadata.
An unsafe/corrupt registry prevents log creation and reports that logging failure without changing
the original **Unable to check** result.

The main first-run **Install latest MekHQ Milestone** action explicitly persists Milestone for the MekHQ
repository. Its five split-menu routes cover MegaMek/MegaMekLab Milestone and
MekHQ/MegaMek/MegaMekLab Development; each persists exactly its displayed channel while sharing
the same verified normal installer. The menu has no redundant MekHQ Milestone or exact-picker row.
Opening the styled menu while versions are loading or available starts no request and changes no
channel, Main selection, or preference. Closing and reopening after unavailable rows have been
viewed is the explicit snapshot retry and still changes no installation state. The deferred exact
picker remains on Installations after a copy exists or in relevant problem navigation. Normal and
advanced new GUI installs use one launcher Settings check-on-open default: it is true while
initially absent, and a user's explicit false is preserved for later new copies. The normal quote
binds its exact repository/product set/channel/source version, Settings revision,
registry/default snapshot, and On or Off state without displaying a passive update row or
technical pane. A changed, corrupt, or unavailable settings configuration blocks transfer and
publication until a fresh quote is accepted. This is not a migration: every existing explicit
false remains false, and absent/corrupt/unknown legacy channels remain Unknown or Unavailable with
no inferred choice. The CLI remains false when its legacy option is omitted.

Eligible opt-in checks run serially and independently off the event-dispatch thread, do not disable
healthy launch or page navigation, are cancelled on disposal/mutation, and discard results after
Main, record, preference, or package changes. Off, unknown, corrupt, and launch-only copies are not
reported as checked. Manual Installations checks may inspect the selected copy regardless of its
on-open switch. There is no automatic Apply, automatic downgrade, launcher self-update, Nightly
support, component download, game launch, or website/game CI change in this contract.

Automatic checks additionally require the launcher-wide
`checkInstalledVersionsOnOpen` master setting. Its absent/schema-1 migration value is enabled to
preserve prior startup behavior. Turning it off gates all startup checks but does not rewrite the
per-installation `checkOnOpen` value or channel; therefore explicit Off remains Off after either
master transition. Manual per-card Check/Retry remains available for eligible managed records.
Home aggregates physical-installation results and cannot call unchecked, unavailable,
non-comparable, or imported launch-only records current. There is no bulk update.
