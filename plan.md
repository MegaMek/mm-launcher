# MM Launcher: Proposal and Delivery Plan

**Status:** Proposal for lead and team review; no implementation yet

**Updated:** 12 September 2026

**Decision requested:** Approve the direction and a bounded technical prototype

## 1. Executive summary

MegaMek users currently discover releases, download archives, and organize their application folders manually. We propose a launcher that makes obtaining, updating, and launching the suite a normal application operation.

For most users, the experience should be simple: one preferred non-nightly installation, an Update action, and buttons to launch the applications. They should not need to understand release inventories or manage old folders after each update.

Testers have a different need: a milestone and several nightly builds available at the same time. The launcher must support multiple installations and versions of each program, but expose that complexity only through optional installation-management controls.

**The core model is one simple default experience backed by an installation registry that can manage many portable copies. Each selected installation updates in place.**

This does not return to a model where every update leaves another installation for users to clean up. Additional installations are deliberate choices: for example, preserving an exact nightly to reproduce a bug.

We can keep GitHub Releases as the download host, start with full-package downloads, and later use component downloads to reduce bandwidth. We will build our own launcher, use externally installed Java, preserve user modifications, and leave save-format handling to the applications.

The immediate request is permission to prove the ownership, onboarding, and recovery model on disposable installations before investing in a complete UI or deploying an updater to users.

## 2. User experience

### Normal mode: simple by default

The main screen should focus on:

- The preferred installation and its release.
- Launch actions for the applications it contains.
- An available compatible update, release notes, and download size.
- Clear download, update, warning, and recovery status.

Do not show a release matrix, nightly feed, or installation inventory by default. A visible but secondary "Manage installations" entry makes advanced functionality discoverable.

Proposed default: offer the team's recommended non-nightly release. The team must explicitly define whether that means its Milestone channel or another recommended channel; do not derive it from GitHub's prerelease flag.

Update checks must not prevent offline use of a healthy installation. A failed check should say "Unable to check for updates," not "You are up to date."

### Advanced mode: multiple versions without ambiguity

Installation management should support:

- Adding a separate milestone, development release, or exact nightly build.
- Registering an existing portable copy.
- Giving installations recognizable names and choosing the default.
- Viewing the product/bundle, path, exact build, channel, and update policy.
- Pinning a build so it is not replaced by a newer build.
- Updating a selected installation without changing the others.
- Removing a registry entry without deleting its files.

For example, a tester might keep "Daily play - milestone," "Nightly under test," and "Reproduction - build abc123." A rolling nightly installation may update in place, while the reproduction build remains pinned.

Switching the default installation must not copy, convert, or merge saves. Any destructive removal of downloaded application files needs explicit confirmation and verified user-data protection; automatic deletion of registered external copies is not part of the initial scope.

The installation model must support multiple versions from the start. The UI can be delivered incrementally, but should not require redesigning the updater later.

## 3. First-run onboarding: portable archives, not installer detection

The suite is distributed as extractable application archives, not necessarily as operating-system-registered installations. Current researched packages are tar.gz archives; the design must not assume every package is a ZIP or that an OS installation registry exists.

The first-run screen should offer two clear paths.

### A. "Get MegaMek" - no existing copy

1. Offer the recommended product/release, explaining that the MekHQ bundle includes all three programs.
2. Select a writable application location and supported user-data location, with sensible platform defaults.
3. Locate a compatible externally installed Java runtime and explain any prerequisite failure.
4. Download the package and trusted metadata.
5. Verify and safely extract into temporary staging.
6. Finalize the new application directory and register it only after validation succeeds.
7. Make it the preferred installation and present the relevant launch actions.

A new installation must not overwrite a nonempty destination. If the selected folder contains an existing copy, direct the user to registration instead. Failure leaves no falsely registered "ready" installation and does not damage pre-existing files.

### B. "Use an existing copy" - already downloaded/extracted

Because portable folders can be anywhere, folder selection is the reliable baseline:

1. Let the user browse to an existing application folder.
2. Optionally offer a scan of user-approved locations, such as a selected games folder.
3. Identify candidate products, layouts, and versions using multiple package signals.
4. Present candidates for confirmation before registering or modifying anything.
5. Register the confirmed canonical folder and determine whether it is eligible for managed updates.

Detection must be read-only. Do not launch unknown executables or JARs to discover their version. Do not scan the entire machine, network shares, or protected locations by default. Bound optional scans, allow cancellation, report inaccessible locations, and avoid following links into recursive or unrelated trees.

Do not rely only on a folder name: users rename folders and may have source checkouts, incomplete downloads, modified builds, or nested copies. Read expected layout and embedded version/build metadata safely, then verify managed-update eligibility against an official baseline.

Registration does not imply trusted or exact release identity. A recognized, user-confirmed layout may be usable as launch-only before a trustworthy update baseline is available. Ambiguous, incomplete, or unsupported layouts need explicit diagnostics; they must not silently become update targets.

### Adoption and legacy content

Never generate an "official" baseline by hashing whatever is on the user's disk. Obtain a trusted manifest or reconstruct the baseline from the identified official package.

An older nightly may no longer have an available artifact or reliable identity. Preserve it and explain why automatic updating cannot safely be enabled; registration must not erase the user's existing copy.

Existing releases may not support a separate user-data root. Do not relocate their content merely because the launcher registered them. Keep their supported layout and working directory until an explicitly supported transition is available.

Design and validate that transition separately: preserve originals, respect external locations, and confirm the new application can access personal content. File relocation is not save-format conversion.

## 4. Agreed boundaries

| Area | Direction |
| --- | --- |
| Default experience | One preferred non-nightly installation, with advanced controls hidden by default |
| Installation capability | Multiple named installations and versions of each program |
| Updating | Update the selected installation in place; do not create routine version-folder leftovers |
| Downloads | Full packages first; components later |
| Java | Use installed Java; do not bundle or change global Java |
| User content | Separate from managed application content where supported |
| Local modifications | Preserve conflicting modifications and report skipped changes; no merge UI |
| Saves | Application-owned compatibility and conversion |
| Hosting | Existing public release assets plus authoritative metadata |
| Nightlies | Advanced opt-in, exact build identities, and explicit publication/retention rules |

Out of scope: campaign conversion or rollback tooling, a merge editor, binary deltas initially, mandatory accounts or telemetry, automatic machine-wide discovery, forced channel changes, and automatic cleanup of user-owned installations.

## 5. Installation and data model

### Installation is not the same as program

The registry describes physical application directories. Each installation has its own stable ID, canonical path, product/bundle, exact release/build identity, channel/update policy, Java selection, official manifests, override state, and data-location association.

One MekHQ installation exposes MegaMek, MegaMekLab, and MekHQ launch actions. Those are not three independent update targets: they share a tested bundle.

Standalone MegaMek and MegaMekLab distributions can have their own installation records. Multiple versions of a program can therefore be available through separate standalone or suite installations.

Register a physical installation only once even if selected through another path alias. Detect overlapping/nested managed roots and reject ambiguous ownership. Scope update locks to the real installation, including all programs sharing it.

A bundle update must use an explicitly tested set of components. Never insert the latest standalone MegaMek into an arbitrary older MekHQ bundle.

### Data isolation across versions

Separate these responsibilities:

| Area | Contents |
| --- | --- |
| Application content | Binaries, libraries, shipped data, and defaults |
| User data | Saves, campaigns, preferences, custom content, supported overrides |
| Launcher state | Registry, manifests, override records, staging, recovery journal |

**Separation does not mean every installation writes to one shared profile.** As a proposed safe default, new managed installations should have separate associated writable profiles. Nightly testing must not silently change the everyday installation's settings or personal content.

Keep legacy data associations unchanged unless the user accepts a supported relocation. Do not automatically share writable directories, copy campaigns when switching versions, or pretend a profile isolates every file: user-selected external paths may still refer to the same content.

Explicit cross-installation sharing is a later decision requiring compatibility and concurrency rules. The launcher does not parse save formats or guarantee that a save opened by a newer version can be reopened by an older one.

The application changes needed for configurable data roots must be coordinated across the suite. Simply changing the process working directory is not a complete solution.

## 6. Release identity and metadata

Use two related records:

- **Release catalog:** Product/bundle, channel, exact build identity, asset URLs, sizes, hashes, Java requirements, notes, and launcher compatibility.
- **File manifest:** Official relative paths and hashes for the final distribution; later, component identities and download locations.

Keep installation identity, release identity, and channel separate. Two installations may contain the same release; a pinned build does not track a moving channel.

Normalize release versions numerically while retaining exact original tags. Current conventions include zero padding and optional fourth components. Do not use lexicographic version comparison.

Nightlies require an immutable build identifier, such as source commit plus build/run identity and payload digest. A display version or date alone is insufficient: builds may share a version string, be rerun, or use different dependency/data revisions.

A channel is a team-controlled recommendation/promotion policy, not simply whichever GitHub entry happens to be newest.

## 7. File ownership and update policy

Compare:

1. **Baseline:** The previous official hash for the tracked path.
2. **Local:** The current on-disk hash.
3. **Target:** The new official hash.

Local-versus-target comparison alone cannot tell an outdated official file from a user-modified file.

| Situation | Action |
| --- | --- |
| Local matches target | Reuse it |
| Local matches baseline; target differs | Replace it |
| Local differs from baseline and target | Preserve it; report skipped replacement |
| Required managed file is missing | Install it |
| Target removes an unmodified managed file | Remove it |
| Target removes a modified managed file | Preserve it; report skipped removal |
| Target collides with an untracked file | Preserve existing content; report collision |
| Unknown file has no target conflict | Leave it alone |
| User-data file | Exclude from application synchronization |

Persist per-file baseline and override information across repeated updates. Updating the displayed release must not redefine preserved local modifications as official content.

Example completion:

> Updated the selected installation to release X with 3 local overrides preserved. These files differ from the official release and may affect compatibility.

Report the paths and intended operations. No merge UI is required. Preserving a modified binary can leave an incompatible combination; do not claim that such an installation is an exact verified official release.

## 8. Recoverable in-place updates

Temporary staging and recovery storage belong to the updater, not the user-facing installation inventory.

1. Resolve the selected installation and trusted target.
2. Compute changes and preflight permissions/disk capacity.
3. Download, verify, and stage payloads before changing application files.
4. Ensure all applications using that installation are closed; prevent overlapping updates.
5. Recheck affected local files before mutation to catch changes since planning.
6. Write a durable journal and retain files that will be replaced or removed.
7. Apply changes in place.
8. Verify the non-overridden target and record skipped paths.
9. Commit the installed-release state.
10. Clean up according to a bounded recovery-retention policy.

Process termination or disk exhaustion must not leave a half-updated installation launchable through the launcher. Recover or restore its prior application state before launch. Other healthy installations should remain independently usable.

A shared download cache is possible, but journals, backups, and writable files must not leak across installations. Cleanup must not remove content still needed by an active transaction.

Validate archive paths, link targets, platform collisions, and expansion limits. Define trusted metadata, bootstrap verification, key rotation, metadata freshness, and launcher self-update recovery before public distribution. Use established libraries and cryptographic mechanisms; never ship publishing credentials in the launcher.

A checksum from the same publishing account provides consistency checking, not an independent publisher signature.

**Application-update recovery does not roll back saves or campaign changes made after an application runs.**

## 9. Release pipeline and bandwidth

### Full downloads first

Extend the existing release process:

1. Build and test the bundle.
2. Prepare the final distribution.
3. Generate its manifest and payload hashes.
4. Publish artifacts and authenticated metadata.
5. Verify availability.
6. Promote the channel pointer only after publication completes.

Download the full package into staging, then apply ownership-aware changes in place. For a suite user, this is one MekHQ bundle rather than three separate downloads.

### Component downloads later

Measure representative releases before selecting boundaries such as application JARs, libraries, game data, and visual resources. Each release still specifies one tested combination.

Reuse verified unchanged components and download changed ones. Generate manifests from final packaged output, not source diffs: archives and generated atlases can amplify small changes.

This reduces network transfer without changing the installation contract. Temporary storage, recovery capacity, and data isolation remain necessary. Do not use hardlinks or other deduplication to share writable content across installations.

### Nightly delivery needs an explicit feed

The inspected nightly workflow uploaded GitHub Actions artifacts rather than a durable consumer release feed. Do not assume the release API automatically provides a suitable nightly channel.

The team needs to define:

- Which successful, tested builds are eligible.
- Exact identities of code, dependencies, and shared data.
- Anonymous availability or another approved access mechanism without embedded credentials.
- Artifact and manifest retention, and behavior when a historical build expires.
- Promotion only after the whole bundle is ready.
- Clear experimental labeling, opt-in tracking, and pinning.

Existing tester copies can be registered independently of this feed. Automatically downloading/managing nightlies is not complete until these publication requirements are implemented and tested.

Expired remote artifacts must not cause deletion of an installed pinned build. Explain when redownload or baseline reconstruction is unavailable.

## 10. Implementation milestones

### 1. Contract, onboarding, and ownership inventory

Define registry/catalog/manifest schemas, UI default-versus-advanced flows, data-root behavior, legacy detection, baseline adoption, build pinning, recovery, and supported product/channel/Java/platform scope.

Inventory current writable paths. Confirm how multiple profiles and old releases coexist without silently sharing state.

**Acceptance:** The team can explain fresh download, existing-copy registration, repeated updates, multiple builds, and failed updates without ambiguous file ownership.

### 2. Core prototype on disposable installations

Implement planning, staging, verification, in-place application, journaling, and recovery without a polished UI.

Test A-to-B-to-C updates with modified files, removals, missing files, collisions, and overrides. Use multiple independent installations to prove one update cannot change another.

**Acceptance:** The target is reached with explicit overrides, or a recoverable failure occurs, without protected-content loss.

### 3. First-run and suite integration

Implement clean download and read-only folder detection/registration. Establish trusted baselines separately from discovery. Add supported application data roots and conservative legacy onboarding.

Test duplicates, path aliases, invalid folders, external data locations, launch-only entries, unavailable historical artifacts, and different Java requirements.

**Acceptance:** A new user and an existing portable-copy user can reach a usable default installation without unsafe guessing or manual release-to-release save copying.

### 4. Minimal UI and optional installation management

Deliver the simple home screen first, backed by the multi-installation model. Add the secondary management view for naming, selecting defaults, pinning, registering copies, and updating a chosen installation.

**Acceptance:** Regular users see a straightforward Launch/Update experience. Testers can retain several versions, and the UI makes the affected installation unambiguous.

### 5. Release/nightly publication and controlled pilot

Integrate trusted metadata publication, stable channel promotion, and the explicit nightly feed. Validate supported operating systems and architectures, interrupted update recovery, running-process handling, external Java diagnostics, and launcher self-update.

An initial stable-only transport prototype is acceptable, but does not satisfy the full tester/nightly requirement.

**Acceptance:** Ordinary and tester workflows are proven on disposable copies before rollout to real user content.

### Follow-up: measured component optimization

Use download sizes, update times, and disk costs to prioritize component delivery. Retain the same ownership/recovery model. Binary deltas remain optional.

## 11. Principal risks

| Risk | Mitigation |
| --- | --- |
| Multi-version support overwhelms ordinary users | Simple default home; optional management view |
| Portable folders cannot be reliably auto-discovered | Folder picker baseline; bounded optional scans; user confirmation |
| A detected copy has an uncertain baseline | Separate registration from managed-update eligibility |
| One bundle is mistaken for independent executables | Installation-level identity and locks; tested bundle manifests |
| Nightly and milestone versions share writable content | Separate associated profiles by default; preserve legacy associations |
| Local overrides break a new release | Explicit warnings and persistent override records |
| Update interruption leaves mixed binaries | Durable recovery journal and launch gating |
| Historical nightly artifacts disappear | Define retention; preserve installed builds; explain unavailable recovery sources |
| Full downloads consume bandwidth and temporary space | One bundle initially; preflight checks; later component downloads |
| Application data separation is broader than expected | Inventory first and coordinate suite changes |

Do not promise a delivery date until the ownership inventory, legacy-adoption work, and recovery prototype establish the scope.

## 12. Decisions and support requested from the lead

1. Approve the simple-default, multi-installation architecture and in-place update policy.
2. Confirm supported products, the recommended non-nightly channel, and initial platforms.
3. Confirm the proposed profile-isolation default and legacy-adoption boundaries.
4. Assign ownership for the launcher and coordinated suite integration.
5. Assign release metadata/signing and nightly publication/retention responsibilities.
6. Authorize the contract-and-disposable-prototype milestone.

A launcher repository now exists; organizational ownership and contribution permissions still need to be agreed. Existing manual archive downloads should remain available during development and rollout.

## 13. Research references and limitations

Historical observations below were checked on 10 September 2026, not revalidated as current release announcements for this revision:

- The latest MekHQ endpoint returned `v0.51.0`, approximately 690 MB compressed, containing all three programs.
- Public metadata could be fetched without authentication, and the returned asset URL answered an HTTP HEAD request with status 200.
- Current inspected source required Java 21 and used release versions with optional fourth components.
- The nightly workflow published Actions artifacts.

Sources:

- [Official downloads](https://megamek.org/downloads.html)
- [MekHQ v0.51.0](https://github.com/MegaMek/mekhq/releases/tag/v0.51.0)
- [MekHQ package assembly at the inspected baseline](https://github.com/MegaMek/mekhq/blob/001d396d48796d35096ca0463ab8f66616b41f5a/MekHQ/build.gradle)
- [MegaMek version handling at the inspected baseline](https://github.com/MegaMek/megamek/blob/bd4af4b5ac242af051e43931642099122b3f68e6/megamek/src/megamek/Version.java)
- [Nightly workflow at the inspected baseline](https://github.com/MegaMek/megamek/blob/bd4af4b5ac242af051e43931642099122b3f68e6/.github/workflows/nightly-ci.yml)
- [GitHub Releases API](https://docs.github.com/en/rest/releases/releases)
- [GitHub REST rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [Update trust concepts](https://theupdateframework.io/docs/overview/)

Use caching, finite timeouts, and backoff for discovery; anonymous GitHub REST requests have a shared per-IP rate limit. No user account or embedded token should be needed for public release discovery.

No updater, full package installation, profile relocation, archive recovery, discovery scan, or cross-platform integration has been execution-tested. Current source may differ from shipped releases, and the writable-path inventory is not exhaustive.

This document records a proposed implementation path, not a security certification or authorization to modify real installations.
