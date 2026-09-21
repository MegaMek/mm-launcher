# Durable release-channel metadata: CI proposal

## Problem statement and current workaround

The website's current `_data/current_releases.yml` file publishes only the current `stable` and
`dev` version pointers. It is authoritative for those two current targets, but it is not a
historical channel ledger. GitHub release titles and `prerelease` flags are not durable channel
truth: a release can be promoted after it is published (the 0.51.0 promotion is a known example),
and editing a title or flag cannot express when or why a promotion happened.

Release identity also spans repositories. MegaMek, MekHQ, MegaMekLab, and the website pointers
must agree on repository, tag, version, assets, sizes, and SHA-256 digests. A launcher cannot
accurately label or filter old releases as Milestone or Development without immutable,
cross-repository metadata that records that history.

Until that index exists, the launcher's single **Fetch releases** adapter reads both current
pointers from the fixed website YAML once and one bounded GitHub-history page from only the
selected product repository. It includes the selected channel's authoritative current identity
and identities whose history is unknown, excludes a distinct identity known only as the other
current target, and accepts a shared stable/dev identity for either channel. It performs an exact
selected-target lookup only when needed for canonical eligible metadata. Most historical rows
therefore appear under both channels. The selected channel becomes the newly installed copy's
immutable local update track and does not assert an unknown release's historical origin.

This document recommends future CI and publication work. It does not claim that any repository
already implements these capabilities.

## One official release source, projected into package identity

There is one logical official release source of truth, not three peer authorities. Product CI
creates immutable release artifacts and an embedded identity projection. The canonical release
index accepts and validates those identities, then authoritatively records channel membership,
promotion history, and each channel's current target. GitHub titles, ordering, and `prerelease`
flags are transport/display metadata, not another release authority.

Launcher-owned provenance is local installation policy rather than release truth. During a
verified fresh install, the installer records exactly one fixed track—Milestone, Development, or
later Nightly—bound to that installation UUID, canonical root, registration time, and ownership
receipt. CI does not mark individual user installations. Promotion may place identical release
bytes in several channels, but it does not retarget installations that already chose a track.
Using another track means installing another managed copy in a separate root.

Safe uninstall consumes the launcher's latest verified local ownership/current-provenance
manifest. CI-produced package manifests therefore remain file-exact inputs: generated artifacts
must not include mutable user locations, links, aliases, or paths outside the documented ownership
allowlist. The launcher never interprets archive membership alone as deletion authority.

Product CI should place a schema-versioned manifest at one fixed path inside every official
archive, for example `release-manifest.json`. It should be generated before packaging and covered
by the published archive digest. At minimum it should contain:

- schema version and product;
- normalized application version and exact tag/build identifier;
- source repository and immutable source commit;
- compatibility-suite ID;
- build timestamp and producing workflow identity;
- expected package layout/products;
- Java/runtime requirements;
- a content-manifest identity when one exists.

The embedded manifest must not contain the SHA-256 of its enclosing archive because that would be
self-referential. The canonical external index records the finished archive name, size, and
SHA-256 after packaging.

The embedded manifest must not expose one mutable `channel` value as permanent truth. It is a
projection of immutable build identity, not a record of current channel policy. Launchers must use
the canonical index for current official membership. A release may belong to more than one
channel after promotion without changing its bytes or embedded identity.

When MM Launcher installs a package, it writes launcher-owned provenance outside the official
payload. The ownership receipt records exact package identity; the adjacent fixed-track sidecar
records the channel chosen for that newly created installation. Initialization is part of verified
publication and is never an import or later preference action. The installer must not edit the CI
manifest or claim that local policy is official channel metadata.

This gives predictable recovery behavior:

- a new launcher using the same registry/provenance directory reads the saved fixed track without
  rewriting it;
- an imported legacy folder is launch-only and receives no channel or managed-update
  authorization;
- a package copied without launcher provenance may identify its build from the embedded manifest,
  but cannot automatically regain managed updates or infer which track that copy was created for;
- missing, unsupported, stale, or ambiguous local track provenance remains unconfigured and
  launch-only rather than being inferred from version, title, membership, or `prerelease` flag;
- embedded or launcher-written metadata alone never authorizes an update unless the release
  identity, official index, asset size, and SHA-256 all revalidate.

## Canonical release index

Publish a versioned, machine-readable release index with an explicit schema identifier. Each
immutable release identity should include:

- product (`megamek`, `mekhq`, or `lab`);
- canonical repository owner/name;
- immutable GitHub release ID, tag, and normalized application version;
- zero or more channel memberships;
- append-only promotion history with channel, effective timestamp, and promoting workflow/run;
- compatibility-suite ID tying compatible cross-repository releases together;
- embedded package-manifest schema and identity;
- every installable asset's exact name, byte size, SHA-256 digest, platform/architecture scope,
  media/archive type, and canonical release identity;
- publication timestamp and source commit/workflow identity.

Channel membership must not be inferred from title text, release ordering, or GitHub's
`prerelease` flag. Promotion should append a dated event; it must not erase earlier state.
Corrections publish a new auditable index generation. The index should define whether a release
may belong to multiple channels concurrently. There is no required ordering or supersedes
relationship between different channels: target progression and integrity are evaluated only
within the fixed channel an installation follows.

## Repository CI responsibilities

### MegaMek, MekHQ, and MegaMekLab

Each product release workflow should:

- derive product, tag, and version from one canonical build input;
- reject case differences or disagreement among the tag, application version, archive root, and
  expected asset name;
- generate the install asset and its SHA-256 digest as one release transaction;
- make digest presence mandatory before a release is eligible for index publication;
- publish asset size, digest, platform, and compatibility-suite ID as signed or otherwise
  integrity-protected workflow output;
- reject duplicate asset names, case/Unicode aliases, conflicting product identities, and
  ambiguous install assets;
- expose immutable GitHub release identity rather than relying only on mutable tag/title fields.

### Website

The website publication workflow should:

- validate candidate entries against the versioned index schema;
- resolve and verify the exact release identity in each named official repository;
- verify tag/version/product/asset-name agreement, positive bounded sizes, and required SHA-256
  digests;
- validate that every compatibility-suite member points to the intended coordinated versions;
- reject duplicates, case-only collisions, conflicting promotions, missing products, and unknown
  schema fields where the active schema requires strictness;
- publish the current `stable`/`dev` pointers and canonical history atomically, so clients never
  observe a pointer whose indexed release is absent;
- retain old index revisions for audit and rollback without rewriting immutable release entries.

## Promotion and failure semantics

- Initial publication and later promotion are separate events.
- Promotion is append-only and records an effective date in UTC.
- Each channel's target transition must be internally valid and auditable; no cross-channel
  ordering is needed and a launcher never follows a promotion into another track.
- A failed cross-repository validation must publish neither a current pointer nor partial history.
- A website deployment failure must leave the previous pointer and history generation intact.
- Rollback of a bad pointer should be a new audited pointer event; it must not delete the failed
  release identity or silently rewrite its prior channel history.
- Missing or mismatched digest data is a hard failure, not a warning or a title-based fallback.
- Concurrent promotions must use generation/compare-and-swap protection or an equivalent atomic
  publication mechanism.

## Security and permissions

- Use least-privilege, short-lived CI credentials.
- Product workflows should not have general write access to the website repository.
- The website/index publisher should accept only validated artifacts or attestations from an
  allowlist of official repositories and protected workflows.
- Protect schema, publisher workflow, environment, and channel-promotion approvals with branch
  protection and required review.
- Never place release-upload credentials, tokens, signed asset URLs, or private provenance in the
  public index.
- Record workflow run IDs and immutable commit/release IDs for audit without treating them as a
  replacement for asset digest verification.

## Nightly channel requirements

Nightly cannot use ephemeral GitHub Actions artifacts as the launcher contract. Those artifacts
may require authentication, expire, change retention, and lack a stable public release identity.
For Nightly to be valid in MM Launcher, CI must provide all of the following.

### Durable identity and delivery

- Publish each Nightly as a unique immutable build. Never replace bytes at an existing tag, asset
  name, URL, or build ID.
- Use a documented version/build scheme containing a monotonically sortable build identifier,
  UTC build timestamp, and immutable source commit. The exact scheme remains an open decision.
- Publish through a durable, unauthenticated HTTPS endpoint accepted by the launcher, such as an
  official GitHub release or a separately specified official object store.
- Publish the same embedded `release-manifest.json` contract used by Milestone and Development.
- Publish exact asset name, positive bounded byte size, SHA-256 digest, repository/product, tag or
  immutable object identity, and release-notes/build-log URL.
- Keep the Nightly pointer and every referenced asset available for the documented retention
  period. A pointer must never reference an expired or deleted artifact.

### Cross-repository compatibility

- Build MegaMek, MekHQ, MegaMekLab, and required data from a coordinated compatibility-suite
  definition rather than independent “latest branch” races.
- Record every participating repository and commit in the suite metadata.
- Reject publication when a required product, data bundle, manifest, digest, or compatibility
  relationship is missing or inconsistent.
- Define whether a Nightly suite is published only after every product succeeds or whether
  independently installable products may advance separately. The index must represent that choice
  explicitly.

### Atomic Nightly publication

1. Build and test all required components.
2. Generate and validate embedded manifests.
3. Package immutable assets.
4. Compute sizes and SHA-256 digests.
5. Upload assets and verify the uploaded bytes.
6. Publish the immutable release-index entry.
7. Atomically advance the `nightly` pointer only after every prior step succeeds.

A failed, cancelled, or partial workflow must leave the previous Nightly pointer intact. Retrying
the same workflow must be idempotent and must not create conflicting identities.

### Nightly retention and promotion

- Define the minimum number of builds or days retained and ensure index cleanup never precedes
  asset cleanup.
- Mark expired/yanked builds explicitly in history; do not silently reuse their identity.
- Promotion from Nightly to Development or Milestone appends channel-membership events. It does
  not rewrite the Nightly archive or erase its original provenance.
- Security revocation must block new installs/updates while retaining enough immutable metadata
  for audit and recovery.

### Launcher-facing Nightly contract

- Add an explicit `nightly` channel pointer and channel-history value; never infer Nightly from a
  branch name, timestamp, version suffix, title, or GitHub flag.
- Nightly remains opt-in, is never selected as the first-launch default, and is installed as its
  own fixed-channel managed copy rather than retargeting a Milestone or Development root.
- A Nightly update check resolves one exact immutable target and revalidates repository, package
  manifest, suite identity, asset name, size, and SHA-256 before transfer.
- Unsupported, expired, missing, or partially published Nightly metadata fails closed and leaves
  the installed copy launchable.
- The launcher should display Nightly build date/sequence and short source commit in addition to
  the normal **Program Channel (Version)** label when the canonical schema supplies them.

## Launcher build and packaging cadence

Do not rebuild the all-platform launcher archive for routine source, documentation, or test-source
iterations. Local work should use only the smallest explicitly requested checks and refresh
`installDist` only when executable review is requested. `buildArchive` and native archive
verification are reserved for release/packaging changes, explicit packaging checkpoints, or final
publication validation.

## Required CI tests

- Schema-version and strict parser fixtures, including unknown/duplicate/null/type-invalid fields.
- Valid and invalid product/repository/tag/version matrices.
- Asset-name, size, digest, platform, and URL agreement.
- Duplicate, case-insensitive, Unicode-normalization, and conflicting promotion rejection.
- Multi-repository compatibility-suite completeness and mismatch rejection.
- Promotion-after-publication scenarios, including the same immutable release bytes belonging to
  Development and Milestone while existing installations retain their originally fixed tracks.
- Atomic pointer-plus-history publication and injected partial-deployment failure.
- Embedded package-manifest generation, schema validation, archive inclusion, and identity/index
  agreement.
- Nightly immutable-identity, coordinated-suite, upload-verification, pointer-advance, retention,
  expiry, yanking, and failed-publication fixtures.
- Assertions that no current pointer references an unavailable/expired asset and no uploaded asset
  is replaced under an existing identity.
- Retry/idempotency, concurrent publisher, rollback, and stale-generation behavior.
- Backfilled entries that are explicitly marked with evidence level and do not invent dates or
  channels.
- Launcher contract fixtures for known schema versions and fail-closed behavior for unsupported
  versions.
- Durable historical official packages and SHA-256 values sufficient to reconstruct an exact
  ancestor after the release stops being current; retention/yanking must remain explicit.
- A build-produced embedded manifest identity in every supported package, checked against the
  canonical repository/release/asset identity and complete executable/dependency inventory.
- Adoption fixtures for pristine, modified-data, protected/custom, missing, mixed-runtime,
  wrong-product/version, case/Unicode conflict, cancellation, root/provenance drift, and
  all-or-nothing metadata publication.
- Backfill validation that records immutable package facts independently from historical channel
  evidence and never invents a channel from GitHub titles, prerelease flags, or filenames.

## Backfill and launcher migration

Backfill should prioritize immutable facts: repository, release ID, tag, version, assets, sizes,
digests, and compatibility relationships. Historical channel memberships should be added only
when supported by durable evidence such as archived website commits or release-process records.
Unknown history must remain explicitly unclassified; GitHub titles and `prerelease` flags must not
be used to fill gaps.

Once the canonical index is deployed and independently validated, the launcher can add a
schema-versioned adapter and classify historical rows only when an exact immutable release
identity has verified history. During migration:

- keep the current fixed-YAML plus exact-tag path as the authoritative current fallback;
- keep GitHub history unclassified whenever history is absent, unsupported, stale, or invalid;
- never downgrade from invalid canonical history to inferred title/flag classification;
- cache only with generation/source identity and revalidate before transfer;
- retain the same exact repository/tag/asset/digest and normal-install planner checks;
- preserve every valid stored installation track by semantics without rewriting its sidecar on
  read, and leave missing/unknown/unavailable tracks launch-only.

Adoption consumes that same hierarchy rather than defining another authority. The canonical index
is authoritative; the embedded package manifest is its build-time identity projection; and a
launcher adoption receipt plus fixed track is local policy. CI must retain enough package/digest
history to verify supported ancestors and must mark unbackfilled or digest-less releases
unsupported. This requirement does not introduce cross-channel switching. Nightly adoption stays
disabled until the immutable Nightly publication, retention, and history contract above is
deployed and validated.

## Ownership and repository checklist

- [ ] Name owners for the schema and channel semantics.
- [ ] Name workflow owners in MegaMek, MekHQ, MegaMekLab, and `megamek.github.io`.
- [ ] Decide which repository hosts the canonical schema and generated index.
- [ ] Define protected promotion approvers and emergency rollback owners.
- [ ] Add product workflow generation and attestable metadata output.
- [ ] Define and publish the embedded package-manifest schema and fixed archive path.
- [ ] Define the launcher-owned provenance/receipt contract separately from official metadata.
- [ ] Add website cross-repository validator and atomic publisher.
- [ ] Add required digest and identity checks to every release workflow.
- [ ] Choose Nightly public storage, immutable version/build scheme, and retention policy.
- [ ] Add coordinated Nightly suite generation and immutable asset publication.
- [ ] Add atomic `nightly` pointer publication and failure/rollback tests.
- [ ] Define retention, audit, and incident-response procedures.
- [ ] Backfill immutable facts and separately review any historical channel evidence.
- [ ] Publish launcher migration fixtures and a deprecation schedule for the workaround only after
      the target state is demonstrably available.

## Open decisions

- Whether channel membership may overlap in the canonical index.
- The canonical compatibility-suite identifier and who allocates it.
- Whether the index is one atomic file, a signed manifest plus shards, or immutable per-version
  documents with an atomic pointer.
- Signature/attestation format and trust-root rotation.
- Retention period and delivery mechanism for old schema/index generations.
- Evidence requirements and visual labeling for partially backfilled history.
- Release-yank and security-revocation semantics without deleting immutable history.
- The fixed embedded manifest path, schema owner, and whether `publishedAs` is retained as
  informational provenance.
- Nightly version/build-ID format, public hosting, cadence, and minimum retention.
- Whether Nightly advances as one coordinated suite or independently per product.
- How long launchers support each schema version and which current-pointer fallback remains
  available during migration.
