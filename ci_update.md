# CI and release-train update

This is a proposal, not a description of deployed CI. It summarizes the agreed
direction for MegaMek, MegaMekLab, MekHQ, `mm-data`, the official release
metadata, and MegaMek Launcher. It supersedes the proposed Nightly channel,
mutable channel pointers, and promotion-based publication design.
Do not change an existing user's installation or publish releases merely by
adopting this document.

## Release model

| Product | Channel | Trigger | Publication |
| --- | --- | --- | --- |
| Game suite | Weekly | Tuesday 05:00 UTC, or on demand | One new, complete game-suite version per successful run |
| Game suite | Development | On demand only | One new, complete game-suite version |
| Game suite | Milestone | On demand only | One new, complete game-suite version |
| Launcher | Release (no game channel) | Explicitly authorized, on demand | One independent launcher version and its native installers |

Nightly is replaced, not retained as a fourth channel or a parallel daily
build. An on-demand Weekly uses the **same** build, numbering, verification,
and publication path as a scheduled Weekly. Development and Milestone have no
scheduled publication. Ordinary PR/main builds are validation artifacts, not
user releases.

Each published suite has **one record version**, while its three programs retain independent versions,
for example `0.51.01` (Weekly), `0.51.02` (an on-demand Weekly), `0.51.03`
(Development), and `0.52.00` (Milestone). The existing two-digit padding is
a minimum width, not a cap: `0.51.99` is followed by `0.51.100`. The channel
is recorded in release metadata, not encoded as a separate version component or inferred from a
GitHub title or prerelease flag. The first allocated number must be checked
against actual tags, including the exceptional MegaMek-only four-component
point releases; these examples are not reserved numbers. Numerically equivalent
spellings such as `0.51.01` and `0.51.1` may not both be allocated, and all new
tags and archives use the padded canonical spelling. The fourth component
remains reserved for exceptional point releases, not a Weekly counter.
A source commit, timestamp, or CI run ID is internal provenance, **not
another user-facing version**.

Each publication consumes a never-before-used suite version across all three game
repositories. Weekly and Development advance the patch component; Milestone
advances the middle release-line component and resets patch to zero. The
coordinator reserves the next unused numeric version from release tags and
assigns that value only to products that require rebuilding, without committing a
`Version.properties` change for every scheduled release. It must reserve
without racing another scheduled/manual invocation, validate each product's own version in its displays,
embedded identity, tags, archive roots and asset names, and never move a tag
or replace a published asset. A failed attempt must not make its version
installable; retry policy must distinguish a retry of identical, unexposed
inputs from a new publication. The base version source currently lives in
MegaMek's `megamek/resources/Version.properties`; CI must apply the reserved
versions consistently without independent jobs reading or changing that file at
different times. The existing `-PextraVersion=nightly-...` convention is not
the proposed public version scheme. Development and Milestone builds also get
new record versions, possibly reusing all product artifacts; editing an old record's membership is not part of
this proposal.

The ordering is `MegaMek <= MegaMekLab <= MekHQ <= suite`. MegaMek or data changes rebuild all
three products; Lab changes rebuild Lab/HQ; HQ-only changes rebuild HQ. Unaffected products reuse
their exact previously attested release/asset IDs, names, sizes, SHA-256, and bytes. A new membership
can publish a record with all three products reused. Reused archives are not relabelled or modified;
an older MegaMek archive may carry older unrelated Lab/HQ snapshot pins.

The launcher keeps its own independent numeric product version. Publishing
game version `0.52.00` does not require publishing launcher version `0.52.0` or
rebuilding the launcher. A suite that requires newer launcher functionality
must declare a minimum launcher version, and older launchers must report the
incompatibility rather than attempt an unsupported update.

## Shared game-suite publication

Use one coordinating release workflow in `MegaMek/megamek`, not three
independent schedules. Its Tuesday 05:00 UTC scheduled and on-demand Weekly
entry points call the same publication procedure; the manual entry point
also supports explicitly selected Development and Milestone releases. Freeze
the exact protected `main` commits for MegaMek, MegaMekLab, MekHQ, and
`mm-data` before building anything. MekHQ and MegaMekLab currently consume MegaMek, and MekHQ
also consumes MegaMekLab; the published dependency revisions must agree with
the snapshot. Record these commits, the allocated suite record version, and each product's version in the
resulting metadata. Every complete record references all three game archives, but only affected
products are newly built and uploaded. The
historical MegaMek-only Core Rules release is not a template for exceptions
in the new pipeline.

Before reserving a Weekly version, compare all four pinned source commits
with the newest complete record. If they are unchanged and that record is already Weekly, both scheduled
and manual Weekly runs report a verified no-op without building or publishing. A change of membership
can instead publish a new record with all products reused. If the
comparison is unavailable or ambiguous, fail explicitly; do not silently
skip or allocate a duplicate version.

For each publication:

1. Reserve a globally unique suite version and freeze the four source commits.
   Serialize competing publication runs, including manual Weekly fixes.
2. Build and test affected pinned products and data, and attest exact reusable artifacts;
   reject mismatched dependency
   revisions, versions, Java requirements, or package layouts. Choose one
   launcher-installable archive per product. The current Nightly matrix emits
   both JDK 21 and JDK 25 artifacts; qualification for additional JDKs may
   remain a test concern, but it must not create ambiguous install assets.
3. Produce the existing full `.tar.gz` game bundles, with a versioned,
   machine-readable embedded identity (product, its own version, exact source
   commits, data revision, and required launcher/runtime compatibility).
   Preserve the launcher's required archive structure and protected user-data
   rules. Component/delta updates are a later project, not a Weekly prerequisite.
4. Calculate each finished archive's exact filename, byte size and SHA-256;
   validate each new archive's internal version/layout and upload it to a durable, public
   official GitHub Release for that product's exact immutable tag. GitHub
   Actions artifacts alone are not the launcher delivery mechanism. Verify
   the uploaded metadata and bytes before exposing a complete suite.
5. Publish a **single complete JSON suite record as an asset of the MegaMek
   GitHub Release last**, only after every required product asset has
   succeeded. It identifies the channel, version, exact
   release IDs/tags, products, assets, sizes, digests, source commits,
   compatibility constraints, and release notes. The launcher discovers
   complete records, not three independent "latest" GitHub releases. A
   failed/cancelled/partial run publishes no discoverable complete record.

No mutable `weekly`, `dev`, or `stable` version pointer is needed. The
launcher selects the greatest valid version **within the chosen channel**
from complete suite records; sorting GitHub's publication date or choosing
three repositories' latest releases independently is not sufficient.
Define a durable, public, bounded way to list those records and fetch one
exact record, including retention, pagination, integrity checking, and
atomic visibility. Keep the MegaMek Release draft or otherwise invisible to
new clients until the complete record and all three assets are verified.
The website's present `stable`/`dev` YAML is not the new authority;
the launcher has not been publicly released, so there is no legacy launcher
client that requires continued pointer updates. Incomplete or malformed top
candidates must produce an explicit unavailable result, not an apparently
successful fallback.

A normal bad-Weekly fix is another on-demand Weekly publication with the next
version. Users may explicitly install an older Weekly in a **separate root**;
saves migrated by a newer build may not work in it. Automatically moving an
existing installation backward is not part of this plan. Withdrawal/pausing
the newest broken release before a replacement exists can be a later
explicit policy, not a prerequisite for the ordinary fix workflow.

## Changes by repository or publisher

### MegaMek

- Replace the daily `.github/workflows/nightly-ci.yml` schedule with the
  coordinated Tuesday 05:00 UTC Weekly entry point. Today's
  Nightly workflows are not synchronized (`03:00 UTC` in MekHQ/Lab,
  `05:00 UTC` in MegaMek). GitHub schedules can start late.
- Accept the coordinator's pinned snapshot and allocated product version,
  validate the version in the binary and archive, and publish the exact
  full-bundle asset. Preserve historical point-release data without adding
  a MegaMek-only route to the new three-product release pipeline.
- Provide reusable build/test/upload steps for manual Weekly, Development
  and Milestone invocations rather than implementing a special hotfix route.

### MegaMekLab

- Stop independently scheduling/publishing a Nightly. Build the coordinator's
  pinned MegaMek and `mm-data` revisions under its own allocated version when affected;
  otherwise attest the reused artifact. Reject mismatched dependencies.
- Test and publish the existing launcher-compatible MegaMekLab full archive
  and its verified metadata. Keep PR validation independent of release
  publication.

### MekHQ

- Stop independently scheduling/publishing a Nightly. Pin MegaMek,
  MegaMekLab, and `mm-data` to the same coordinated snapshot.
- Test and publish the existing MekHQ full archive and metadata. Ensure its
  bundled program revisions match the suite record, not whichever default
  branches happen to be current when its job starts.

### mm-data

- Record and pin the exact data commit consumed by each game build, and
  validate its contents bundled in each archive. Do not add an independent
  data release, fourth user-facing game version, or separate data download.

### Official website / release metadata

- Add a website PR to derive displayed Weekly, Development, and Milestone
  releases from the complete suite records instead of manually maintained
  `stable`/`dev` values. Coordinate migration with the website's existing
  consumers; no publicly released launcher depends on those values.
- Present channel, version, release notes, and archive links from the
  complete records. Do not infer authoritative channel membership from
  GitHub prerelease flags, title text, or release ordering.

### MegaMek Launcher

- Add **Weekly** as an opt-in managed-installation channel; retire any
  Nightly proposal rather than implementing Nightly alongside it. Preserve
  existing Milestone/Development installations and their channel provenance.
  A different channel uses a separate installation root; an existing root is
  not silently retargeted or downgraded.
- Replace the new-client website-pointer lookup with strict parsing and
  validation of complete suite records, including bounded discovery,
  product/repository/tag/version agreement, exact public URL, asset size,
  digest, pinned compatibility data, and freshness before Apply. Keep the
  metadata-only check and download-on-consent behavior. Compare only
  installed and offered versions within the installation's fixed channel.
- Retain the independent launcher CI matrix: test and inspect five unsigned
  native installer/checksum pairs on PRs and relevant `main` pushes, without
  publishing them as releases. Add a separate manually authorized release
  path that takes a reviewed launcher version/tag, validates the artifacts
  and checksums, and publishes the official **unsigned** native installers
  with an accurate disclosure in the release notes. The Windows
  MSI updater currently reads the latest non-prerelease numeric launcher
  release; keep its version and installer-upgrade rules independent of the
  game channels. Signing/notarization is not currently provided by CI.

## Migration and verification gates

- Test coordinated scheduled and on-demand Weekly paths as the **same**
  version-allocation/publication procedure; test manual Development and
  Milestone, plus two overlapping runs trying to claim a version.
- Test mismatched source revisions, missing or duplicate assets, wrong
  version/tag, incomplete upload, invalid digest/size, failure just before
  final record publication, repeated invocation, and rejection of
  conflicting retries. No partial suite may appear as current.
- Test launcher Weekly discovery, per-channel ordering, fresh installs,
  updates, website compatibility, mismatched suite records, an older
  Weekly chosen for a separate installation, and unsupported minimum
  launcher versions.
- Prove the public asset path stays usable without Actions authentication
  or artifact retention. Define archive and suite-record retention before
  considering any cleanup; keep immutable published tags/assets intact.
- Coordinate the website's existing `stable` and `dev` consumers; no public
  launcher migration is required. Remove the three daily Nightly
  schedules, badges, and Nightly-facing documentation when Weekly replaces
  them. There is **no Nightly channel** in the target design.

The exact JSON schema, initial numeric version, cross-repository publication
permissions, and failure/retry rules still require implementation and
verification. The coordinator, schedule, record host, padded version spelling,
bundled data, and unsigned launcher publication policy are decided. This
document does not authorize publication or change CI.
