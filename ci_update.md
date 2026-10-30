# CI and release channels

This summarizes the agreed release model for MegaMek, MegaMekLab, MekHQ, `mm-data`, the official
release records, and MegaMek Launcher. The game-suite coordinator, its record schema, and its
validation live in `MegaMek/megamek` (see its `docs/game-suite-release-contract.md`); this
repository only consumes the published records. This document does not authorize publication or
change CI.

## Release model

| Product | Channel | Source | Trigger | Audience |
| --- | --- | --- | --- | --- |
| Game suite | Core | `releases/0.MM.LL` branches | On demand, with approval | For playing; the default |
| Game suite | Experimental | `main` | Weekly (Tuesday 05:00 UTC) and on demand | Help us test, may break things; opt-in |
| Launcher | Release (no game channel) | `main` | Explicitly authorized, on demand | Everyone |

There are exactly two game channels. The earlier Milestone, Development, Weekly, and Nightly
channels are retired; the launcher does not read their records or migrate their installations.

MegaMek, MegaMekLab, and MekHQ are released together with **one version**, `0.MM.LL.B`. `B` is an
unpadded build counter starting at 1 and shared by both channels on a line `0.MM.LL`. For example,
Experimental publishes `0.51.02.1` to `0.51.02.32`, Core then publishes `0.51.02.33` from
`releases/0.51.02`, and Experimental moves on to `0.51.03.1`. Each product's version and tag equal
the suite version and tag; there are no independent product versions and no reused artifacts.
The coordinator allocates the next build number from the real tags and releases of all three
repositories (drafts included), and never moves a tag or replaces a published asset. A source
commit, timestamp, or CI run ID is internal provenance, not another user-facing version.

The channel is recorded in the suite record, not encoded in the version or inferred from a GitHub
title or prerelease flag. Experimental GitHub releases are marked as prereleases for people
browsing GitHub, but the launcher and website must use the record's `channel`.

The launcher keeps its own independent numeric product version. Publishing game version
`0.52.00.1` does not require publishing or rebuilding the launcher. A suite that requires newer
launcher functionality declares `minimumLauncherVersion`, and older launchers report the
incompatibility rather than attempt an unsupported update.

## Shared game-suite publication

One coordinating workflow family in `MegaMek/megamek` freezes one commit per repository from a
single branch (`main` for Experimental, `releases/<line>` for Core), builds all three products and
`mm-data` from those commits, and publishes each product's full `.tar.gz` bundle to that product's
GitHub Release under the shared tag. It verifies each uploaded asset's ID, name, size, and
SHA-256 against the built bytes.

Only after all three product assets exist and pass those checks does the coordinator attach one
complete schema-2 record, `suite-record-<version>.json`, to the MegaMek GitHub Release as its last
publication step. The record identifies the version, channel, tag, products, release and asset
IDs, sizes, digests, source commits, and the optional minimum launcher version. A failed,
cancelled, or partial run publishes no discoverable record. There is no mutable `latest`,
`weekly`, `dev`, or `stable` pointer.

A bad Experimental build is fixed by another Experimental publication with the next build number.
Users may explicitly install an older release of their channel in a **separate root**; saves
migrated by a newer build may not work in it. An existing installation is never moved backward.

## MegaMek Launcher

- Each managed installation follows exactly one fixed channel, Core or Experimental. Core is the
  default for new installs; quick install and the version picker offer Experimental only after
  the player turns on **Show Experimental releases** in Settings.
- The launcher discovers records from the MegaMek release inventory, accepts schema 2 only, and
  validates every product reference (tag, release and asset IDs, name, size, SHA-256) before
  offering a target. Retired schema-1 records are skipped.
- An installation's update target is the record with the greatest four-part version in its
  channel, so a Core install follows new lines automatically (`0.51.01.x` to `0.51.02.x`).
  Checks are metadata-only; packages download only after consent.
- Installations from the retired channels stay launch-only; there is no migration.
- The independent launcher CI matrix is unchanged: it tests and inspects five unsigned native
  installer/checksum pairs on PRs and relevant `main` pushes, and a separate manually authorized
  path publishes official **unsigned** installers. The Windows MSI updater reads the latest
  non-prerelease numeric launcher release; its rules are independent of the game channels.

See [the fixed-channel and update-check contract](docs/channel-update-contract.md) for the exact
launcher behavior.
