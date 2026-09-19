# First-launch presentation

The artwork-led screen is used only when there is no registered installation and no registry or
inspection error. An unreadable registry is still an error, not a new-user reset.

There is one joined, accessible split **Install latest MekHQ Milestone** control. Its large primary segment
is the plain-language Milestone/all-three-program action. The distinct arrow segment opens exactly
five fixed rows, in this order: **Install latest MegaMek Milestone**,
**Install latest MegaMekLab Milestone**, **Install latest MekHQ Development**,
**Install latest MegaMek Development**, and **Install latest MegaMekLab Development**.
There is no redundant MekHQ Milestone row and no exact-picker
row. Each available row includes its validated version in parentheses.

Both segments are keyboard-focusable push buttons. Enter/Space activates the focused segment, and
Alt+Down, F4, the menu key, or Shift+F10 opens the standard semantic popup; Escape closes it. The
popup supplies explicit opaque dark-teal rows, gold border/selection, high-contrast palette
fallbacks, accessible names/descriptions, and visible focus/selection while retaining normal
`JMenuItem` action and keyboard behavior. It is not an OS-default white popup.

The validated MekHQ Milestone version is added directly to the primary label, for example
**Install latest MekHQ Milestone (0.51.0)**. Before validation the base install label remains
usable; failure never invents a version. One display-only worker reads the bounded
official YAML once and resolves eligible assets for all six choices, deduplicating exact release
metadata when stable and development point to the same repository/tag. It updates the main version
and all five rows together only while the original Home generation is current. Loading or
unavailable menu rows are labeled and disabled. Opening while metadata is loading or available
performs no request. After unavailable rows have been shown, closing and reopening the popup is an
explicit display-snapshot retry and immediately shows disabled loading rows. Selecting the
still-enabled primary install action always performs its own fresh normal plan. Per-installation
channels and check status appear on Installations rather than recreating a global default block on
managed Home. Installer explanations remain in the actual confirmation, not repeated on Home.

After registration, Home retains the same composition rather than switching to a left-art layout:
full-width centered-cover artwork stays on top and a compact dark, vertically scrollable deck sits
directly below. The deck has no repeated launcher title/footer or visible Main block. It presents
one joined split control for every application in the union of registered static product records.
The primary segment names the preferred record's version, for example
**Launch MekHQ 0.51.0**. Controls share equal widths and wrap/stack on compact widths.

Each application resolves independently from schema-2 preference state. The arrow lists every
other record containing that product in deterministic registry order as **name · version** plus
already-held status. Opening it performs no filesystem/network work. Selecting a row passes that
exact captured record/product to launch without changing any preference/default/channel; stale
data is explicitly rejected instead of falling back at click time. The arrow is absent and
unfocusable without alternates. Missing Java, root/layout mismatch, and pending recovery disable
the affected primary and route to its installation card.

Managed update state is installation-level. Home contains no ordinary per-copy
Check/Retry/Update controls and instead says **All installed versions are up to date**,
**N installations have updates**, or **Some versions could not be checked**. Imported/no-receipt
and unknown copies cannot create success-shaped current status. Late generation-bound workers
update the summary on the EDT without rebuilding Home. Recovery is prominent only on the affected
card, and no bulk update exists.

A regular, non-primary **Use existing installation** button is visibly next, captioned
**MegaMek, MekHQ, or MegaMekLab**. It opens only a user-directed folder chooser. The first page has
no Java prerequisite/download copy or control and no separate advanced action. Installations keeps
the product/exact-release/channel picker after a copy exists and on relevant repair/recovery
navigation; that picker still performs no request until **Fetch releases**. Local logs remain in
Settings, and there is no simple/advanced mode toggle. Merely displaying Home performs only the
optional six-choice metadata snapshot; it performs no package request, registration, Java
validation, channel choice, filesystem write, or action disabling. Leaving or reloading Home
cancels or discards that display-only result.

The import confirmation names the detected build, actual products, canonical folder, and detected
Java feature, and states that files will not move. Only confirmation permits an atomic launch-only
record with that exact external Java. Import initializes preferences only for included
applications that remain unset under the registry lock. Home then uses the union across all
records. Imported copies receive no ownership receipt or channel setting and remain
update-ineligible.

Selecting the main segment calls the normal planner for exactly `(MEKHQ, MILESTONE)`. Selecting an
available row calls that same planner for the row's fixed `OfficialRepository`/`FollowChannel`
pair. Selection first cancels the display worker so planning cannot duplicate its metadata work.
The planner, confirmation, verified transfer, cancellation, atomic registration, current-runtime
reuse, destination, Main-if-empty selection, and Settings-default path are shared by all six.
Each immutable quote uses the first-launch dark-teal/gold visual language without restyling other
dialogs. The normal summary names the selected application/channel, validated version, actual
program set, human-readable binary download size, and full selectable destination.
**Change location** retains the existing-parent/safe-new-subfolder flow and builds a fresh quote
for the same repository and channel; the validated launcher Java has no happy-path chooser in this
dialog. The simple confirmation has no passive update-behavior row and no technical-details
toggle or pane. Source/tag/asset/digest, Java, registry/Main, and Settings bindings remain in the
immutable backend plan and are revalidated before transfer; operation logs retain errors. Package
transfer and parent/destination creation begin only after explicit **Install**. Escape, Cancel, or
window close returns to the unchanged first-launch options. Offline feedback identifies that
choice and offers retry, including focused Java selection when Java planning failed, without
cross-product, cross-channel, title-based, or latest-release fallback.

For the ordinary GUI registry, the default managed payload/support-data destination is
`%LOCALAPPDATA%\MegaMek\<product channel>` on Windows,
`~/Library/Application Support/MegaMek/<product channel>` on macOS, or
`${XDG_DATA_HOME:-~/.local/share}/MegaMek/<product channel>` on Linux. Linux ignores a relative
`XDG_DATA_HOME`. The product/channel child keeps managed application files distinct from
registry/receipt files that may share the support root; Linux state remains in its state-data
location. This macOS location is not the `MM Launcher.app` resource tree.
Explicit `gui --registry` overrides and custom `LauncherServices` registries instead keep the
isolated `<registry parent>/installations/<product channel>` layout.

Cancelling a quote returns to the unchanged default Home presentation and starts a fresh background
snapshot. The main label/caption never adopts a popup selection, and popup planning never changes a
preference or Main. A successful MekHQ package must inspect as exactly the three-program suite;
standalone MegaMek and MegaMekLab packages must inspect as exactly their selected product, so Home
shows only actual products.

After confirmation, the operation window uses the same dark-teal/gold visual language with a
clear phase, one concise detail, an accent progress bar, and only **Cancel** while cancellation is
safe. It does not display backend log text or active log/copy/repair controls. Once publication
wins, Cancel disappears and the window asks the user not to close it. Accepted cancellation closes
progress and returns to Home with **Installation cancelled**. Failure stays in one compact window
with **Close** and failure-only **View details**; a retained published copy also reveals
**Open Installations**, with no duplicate generic error dialog.

Successful normal setup closes progress immediately and reloads managed Home without an
install-complete modal or automatic launch. A low-prominence `installationReadyNotice` says, for
example, **MekHQ 0.51.0 is ready**. It is process-local across in-place check results, clears on
navigation or meaningful launch action, and is not persisted for restart.

## Artwork and controls

The supplied provisional artwork is retained at
`artwork/MekHQ_Load_spooky_uhd.webp`. Its actual dimensions are **1280 x 719**. The runtime PNG in
`src/main/resources/org/megamek/launcher/gui/first-launch-art.png` is a lossless conversion of the
decoded pixels at those same dimensions, so Java's built-in image reader needs no WebP dependency.
The original artwork is not modified or included a second time in the runtime payload. No artist
credit or public redistribution license is inferred here.

Artwork loading happens off the Swing event-dispatch thread. First-launch and managed Home both
paint at the original aspect ratio with a centered cover treatment: no stretching or side bars,
with only the crop required by the viewport shape. The PNG is still a finite-resolution bitmap; a
high-DPI display does not create extra image detail.

Buttons are vector-painted, suite-inspired beveled controls rather than copies of game textures,
fonts, or the full skin framework. The split segments share one painted plate and separator, so
there are no doubled borders or opposing bevel cuts. Standard JButton actions, keyboard
interaction, mnemonics and accessible names/descriptions are retained. Focus is visibly outlined.

## Scaling and responsive layout

`GuiScale` centralizes logical dimensions, insets, font sizes and aspect-preserving image bounds.
Its optional application zoom factor is separate from device DPI and defaults to 1.0. It is not a
new persisted preference in this pass.

Java 21 already applies the monitor's graphics transform to Swing. Logical component metrics and
the screen's usable bounds must **not** be multiplied by that transform again. The full source
image is painted directly at the logical destination rather than pre-shrinking it and letting
HiDPI enlarge the result a second time.

The normal layout fills the full artwork region with a centered cover treatment, avoiding side
bars while allowing a small top/bottom crop when the viewport has a different aspect ratio.
A full-width dark control deck sits underneath. It has no redundant title: the window title
already identifies the application. The two actions have equal widths, are centered horizontally,
and align at their top edges even though the existing-installation action has a caption. Narrow or
short windows stack them and keep controls top-aligned.
The action area can scroll
vertically if a small work area or explicit large application zoom needs it; horizontal clipping
is not a substitute for access. Initial sizing fits the current monitor's logical work area and
is clamped again when the frame moves to a different graphics configuration.

Managed Home follows the same geometry with a content-sized deck rather than a fixed empty band.
Its actual-product launch controls use up to three equal columns and reduce the column count as
space narrows; deck scrolling keeps update, setup, launch, and navigation actions reachable.

Automated checks cover action bounds and image aspect at multiple pixel densities, compact
widths, explicit application zoom, first-launch registration transitions, and native Swing work
area fitting. Rendered images are review aids, not a substitute for user visual acceptance or
native macOS/Linux testing.
