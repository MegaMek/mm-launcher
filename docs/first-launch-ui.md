# First-launch presentation

The artwork-led screen is used only when there is no preferred installation and no registry or
inspection error. An unreadable registry is still an error, not a new-user reset.

There is one joined, accessible split **Download & install** control. Its large primary segment is
the plain-language Milestone/all-three-program action. The distinct arrow segment opens exactly
**Latest Development** and **Choose another version or application…**; it is not a Unicode arrow
embedded in the primary button. Both segments are keyboard-focusable push buttons. Enter/Space
activates the focused segment, and Alt+Down, F4, the menu key, or Shift+F10 opens the standard
popup; Escape closes it. The split control sits immediately below the title, with a single channel
caption beneath it: **Latest Milestone** at first, extended to **Latest Milestone (version)** only
after a background metadata-only check validates the fixed official target and its installable
MekHQ asset. Failure leaves the generic caption, and selecting Download retries through the normal
consent flow. The selected main copy's configured channel appears on managed Home. Installer
explanations remain in the actual confirmation, not repeated on Home.

A regular, non-primary **Use existing installation** button is visibly next, captioned
**MegaMek, MekHQ, or MegaMekLab**. It opens only a user-directed folder chooser. Static inspection
and validation of the exact Java 21+ runtime running MM Launcher occur off the EDT before the
confirmation. The first-run screen has no separate advanced button. The split menu's full-picker
item directly opens the existing product/exact-release/channel dialog without navigating or
changing a default; it performs no network request until **Fetch releases**. Installations keeps
the same import and exact-release tools on managed/recovery pages, and local logs remain in
Settings. There is no simple/advanced mode toggle. Merely displaying Home
performs only the optional Milestone metadata lookup; it performs no package request,
registration, Java validation, channel choice, or filesystem write. Leaving or reloading Home
cancels or discards that display-only result.

The import confirmation names the detected build, actual products, canonical folder, and detected
Java feature, and states that files will not move. Only confirmation permits an atomic launch-only
record with that exact external Java. The first import becomes Main only when the locked registry
still has no Main; a concurrent or existing Main wins. Home then exposes only the launch buttons
for products the static inspector actually found. Imported copies receive no ownership receipt or
channel setting and remain update-ineligible.

Selecting the main **Download & install** segment performs only fixed official Milestone
channel/release metadata requests and validates the Java running the launcher. Selecting
**Latest Development** uses the same planner, confirmation, verified transfer, cancellation,
atomic registration, Java, destination, Main-selection, and Settings-default path, but binds
`target(DEVELOPMENT, MEKHQ)` and persists Development. Each shows one compact immutable
confirmation with its explicit channel, exact repository, tag, asset name/URL, size, digest,
destination, and Java. Package transfer and parent/destination creation begin only after explicit
Install. Change-location or Java selection builds a fresh quote for the same channel.
Offline/invalid-Java feedback offers retry/setup without cross-channel or latest-release fallback.
Opening the menu does not cancel the Milestone caption lookup. A Development selection may cancel
that display-only lookup while obtaining its own exact quote; cancelling resumes the Milestone
caption without displaying a Development version there.

## Artwork and controls

The supplied provisional artwork is retained at
`artwork/MekHQ_Load_spooky_uhd.webp`. Its actual dimensions are **1280 x 719**. The runtime PNG in
`src/main/resources/org/megamek/launcher/gui/first-launch-art.png` is a lossless conversion of the
decoded pixels at those same dimensions, so Java's built-in image reader needs no WebP dependency.
The original artwork is not modified or included a second time in the runtime payload. No artist
credit or public redistribution license is inferred here.

Artwork loading happens off the Swing event-dispatch thread. Painting always uses the complete
image at its original aspect ratio: no crop or stretching. The dark matte fills any remaining
space. The PNG is still a finite-resolution bitmap; a high-DPI display does not create extra image
detail.

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

The normal layout puts artwork beside an action panel. Narrow windows put the complete artwork
above the controls. The action area can scroll
vertically if a small work area or explicit large application zoom needs it; horizontal clipping
is not a substitute for access. Initial sizing fits the current monitor's logical work area and
is clamped again when the frame moves to a different graphics configuration.

Automated checks cover action bounds and image aspect at multiple pixel densities, compact
widths, explicit application zoom, first-launch registration transitions, and native Swing work
area fitting. Rendered images are review aids, not a substitute for user visual acceptance or
native macOS/Linux testing.
