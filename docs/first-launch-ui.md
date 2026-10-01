# First-launch presentation

The artwork-led screen is used only when there is no registered installation and no registry or
inspection error. An unreadable registry is still an error, not a new-user reset.

There is one joined, accessible split **Install latest MekHQ Milestone** control. Its large primary segment
is the plain-language Milestone/all-three-program action. The distinct arrow segment opens exactly
eight fixed rows, in this order: **Install latest MegaMek Milestone**,
**Install latest MegaMekLab Milestone**, **Install latest MekHQ Development**,
**Install latest MegaMek Development**, **Install latest MegaMekLab Development**,
**Install latest MekHQ Weekly**, **Install latest MegaMek Weekly**, and
**Install latest MegaMekLab Weekly**.
There is no redundant MekHQ Milestone row and no exact-picker
row. Each available row includes its validated version in parentheses.

Both segments are keyboard-focusable push buttons. Enter/Space activates the focused segment, and
Alt+Down, F4, the menu key, or Shift+F10 opens the standard semantic popup; Escape closes it. The
popup supplies explicit opaque dark-teal rows, gold border/selection, high-contrast palette
fallbacks, accessible names/descriptions, and visible focus/selection while retaining normal
`JMenuItem` action and keyboard behavior. It is not an OS-default white popup.

The validated MekHQ Milestone version is added directly to the primary label, for example
**Install latest MekHQ Milestone (0.51.00)**. During validation the primary and arrow are labeled
truthfully, disabled, and removed from keyboard focus; **Use existing installation** remains
enabled and independent. Failure never invents a version or leaves the primary actionable. It
enables only the arrow needed to expose a clear **Retry version check** menu command.
The disabled button already communicates loading, so Home does not repeat a `Checking…` message.
Its fixed-height message row remains reserved even when empty, preventing metadata completion or
later failure text from moving the action controls.

One display-only worker discovers bounded complete suite records and resolves all nine choices,
with explicit unavailability per channel and repeated release-ID caching. A Weekly-only bootstrap
enables the three Weekly rows while the Milestone default remains disabled; it never changes the
default or invents a fallback. Concurrent retry activation is deduplicated. A successful immutable
snapshot is cached only in the `LauncherFrame` session. It updates the current Home controls
together; a late result may populate that cache but cannot mutate disposed, replaced, non-empty,
or non-Home components. Home re-render/reload, page transitions, confirmation cancellation/close,
Change location, and repeated quote opens reuse it and preserve every version label without
loading flicker or another current-target request. Opening the ready menu performs no
request. After failure or partial availability, only explicit Retry starts another snapshot attempt; failed attempts are
not successful cache entries. A failed refresh preserves the previous usable snapshot and exposes
the refresh failure with Retry still available; only a successful refresh replaces that snapshot.
Per-installation
channels and check status appear on Installations rather than recreating a global default block on
managed Home. Installer explanations remain in the actual confirmation, not repeated on Home.

After registration, Home retains the same composition rather than switching to a left-art layout:
full-width centered-cover artwork stays on top and a compact dark, vertically scrollable deck sits
directly below. The deck has no repeated launcher title/footer or visible Main block. It presents
one joined split control for every application in the union of registered static product records.
The primary segment uses the same **Program Channel (Version)** order as first launch, for example
**Launch MekHQ Milestone (0.51.0)**. Controls share equal widths and wrap/stack on compact widths.

Each application resolves independently from schema-3 preference state. The arrow lists every
other record containing that product in deterministic registry order as **name · version** plus
already-held status. Opening it performs no filesystem/network work. Selecting a row passes that
exact captured record/product to launch without changing any preference/default/channel; stale
data is explicitly rejected instead of falling back at click time. The arrow is absent and
unfocusable without alternates. An absent saved Java default is healthy and does not disable Home;
the launch worker validates either the explicit default or the exact runtime executing MM
Launcher. Root/layout mismatch and pending recovery disable the affected primary and route to its
installation card.

Primary and alternate actions launch immediately without a command/runtime confirmation. After
BusyGate admission, the frame iconifies on the EDT before the child can start and remains alive to
hold root/process coordination. A successful child exit leaves it minimized and opens no
completion dialog; start failure, thrown launch error, or nonzero exit restores the visible frame
and reports the error. A busy rejection retains **Please wait** without minimizing. Alternate
launch still passes its captured record and never mutates an application preference.

Managed update state is installation-level. Home contains no ordinary per-copy
Check/Retry/Update controls and no separate update-summary button. Its single Installations
navigation button remains **Installations** unless checked copies have known updates, when it
becomes **Installations (N updates)**. Imported/no-receipt and unknown copies never create a false
count. Late generation-bound workers update that label on the EDT without rebuilding Home.
Recovery is prominent only on the affected card, and no bulk update exists.

A regular, non-primary **Use existing installation** button is visibly next, captioned
**MegaMek, MekHQ, or MegaMekLab**. It opens only a user-directed folder chooser. The first page has
no Java prerequisite/download copy or control and no separate advanced action. Installations keeps
the product/exact-release/channel picker after a copy exists and on relevant repair/recovery
navigation; that picker still performs no request until **Fetch releases**. Local logs remain in
Settings, and there is no simple/advanced mode toggle. Merely displaying empty Home starts only
the nine-choice metadata snapshot; it performs no package request, registration, Java validation,
channel choice, or filesystem write. It disables only the two install segments while loading,
never the direct import action. Disposing the frame cancels and discards an in-flight result.
Re-rendering or leaving Home retains an in-flight or successful session snapshot without binding
a stale component.

After the folder chooser, import uses one styled operation window for static inspection and atomic
launch-only registration. It neither reads Java settings nor runs a Java process. There is no separate name,
confirmation, or completion dialog. A name is derived from the detected product and version
without inventing a channel. Import initializes preferences only for included applications that
remain unset under the registry lock. Home then uses the union across all records. Imported copies
receive no ownership receipt or channel setting and remain update-ineligible. They are never
prompted to choose a channel; Installations labels them **Imported copy · Launch only · Updates
unavailable**. A genuine import later exposes one separate **Enable Updates** action;
it is never part of import and never appears for corrupt/incomplete provenance. That opt-in flow
starts with a compact **Program (Version)** and update-channel dialog. Cancel/Escape performs no
network work; Continue automatically searches bounded official release-list metadata and, only
for one safe match, verifies one exact official package without changing root files before it can
publish a fixed Milestone, Development, or Weekly channel. A simple launch-only result offers the bounded
manual version chooser only after automatic matching is inconclusive or unavailable.

Selecting the main segment passes the cached `(MEKHQ, MILESTONE)` option to the normal planner.
Selecting an available row passes that exact cached `QuickInstallOption`; neither action performs
a current-record lookup. The planner still freshly captures destination and registry/default state off the EDT. A local
planning failure/retry keeps
the cached target. The planner, confirmation, verified transfer, cancellation, atomic registration, current-runtime
reuse, destination, and Main-if-empty selection are shared by all nine.
Each immutable quote uses the first-launch dark-teal/gold visual language without restyling other
dialogs. The normal summary names the selected application/channel, validated version, actual
program set, human-readable binary download size, and full selectable destination.
**Change location** retains the existing-parent/safe-new-subfolder flow and builds a fresh quote.
The native parent chooser is followed by a styled dark-teal/gold folder-name dialog with a
preselected friendly `Program Channel (Version)` value, inline validation, Cancel/Escape, and no
separate installation-name prompt; the same friendly value is registered automatically.
The fresh quote remains for the same repository and channel; the validated launcher Java has no
happy-path chooser in this
dialog. The simple confirmation has no passive update-behavior row and no technical-details
toggle or pane. Source/tag/asset/digest, destination, and registry/Main bindings remain in the
immutable backend plan and are revalidated before transfer. For a captured-current quote,
install-time validation re-fetches the exact repository/tag release and requires the same asset
name, size, and URL. A quoted digest cannot disappear or change; quoted absence may adopt a valid
digest newly present at that refresh, or compute SHA-256 from the one exact bounded transfer. It
never rereads the moving current pointer or retargets the quote.
Historical exact plans and the existing current/history picker retain distinct provenance.
Every successful managed publication initializes its per-installation check-on-open value to
true; first launch has no global or new-install update-check default.
Operation logs retain errors. Package
transfer and parent/destination creation begin only after explicit **Install**. Escape, Cancel, or
window close returns to the unchanged first-launch options. Offline feedback identifies that
choice and offers retry without
cross-product, cross-channel, title-based, or latest-release fallback.

For the ordinary GUI registry, the default managed payload/support-data destination is
`%LOCALAPPDATA%\MegaMek\<product channel>` on Windows,
`~/Library/Application Support/MegaMek/<product channel>` on macOS, or
`${XDG_DATA_HOME:-~/.local/share}/MegaMek/<product channel>` on Linux. Linux ignores a relative
`XDG_DATA_HOME`. The product/channel child keeps managed application files distinct from
registry/receipt files that may share the support root; Linux state remains in its state-data
location. This macOS location is not the `MegaMek Launcher.app` resource tree.
Explicit `gui --registry` overrides and custom `LauncherServices` registries instead keep the
isolated `<registry parent>/installations/<product channel>` layout.

On managed Installations, one Product/Channel/**Fetch releases** row defaults to MekHQ/Milestone.
It shows the channel's current product plus paged history with explicit complete-record membership.
Reused tags are deduplicated by their newest referencing record. All three product references
must validate for each displayed target; titles and prerelease flags never infer membership.
Channel becomes the created installation's fixed update track. Product or channel changes clear results and
invalidate in-flight work; Previous/Next retain their exact selection snapshot. Every row returns
through captured-record normal-planner revalidation before consent. Change location preserves
the chosen record source, even if a newer record reuses the same product tag. Product/channel and Java
selectors share the dark vector-arrow combo treatment rather than an operating-system white arrow
segment.

Cancelling a quote returns to the unchanged default Home presentation and reuses the cached
snapshot. Only explicit Retry refreshes unavailable choices. The main label/caption never adopts a popup selection, and popup planning never changes a
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
install-complete modal, automatic launch, or redundant readiness notice. Managed Home uses one
top-left information area for aggregate update-check state and has no separate status line above
navigation.

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
