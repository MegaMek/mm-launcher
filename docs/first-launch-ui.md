# First-launch presentation

The artwork-led screen is used only when there is no preferred installation and no registry or
inspection error. An unreadable registry is still an error, not a new-user reset.

There is one primary **Download & install** action with plain-language Milestone/all-three-program
copy. The primary button sits immediately below the title, with a single channel caption beneath
it: Milestone on first launch and the selected main copy's configured channel on managed Home.
Installer explanations remain in the actual confirmation, not repeated on Home.
The compact beveled **Advanced Options** button navigates to Installations, where import and exact-release download remain
available; local logs are in Settings. There is no competing existing-copy primary action and no
simple/advanced mode toggle. Merely displaying Home performs no network request, registration,
Java validation, channel choice, or filesystem write.

Selecting **Download & install** performs only fixed official channel/release metadata requests and
validates the Java running the launcher. It then shows one compact immutable confirmation with the
exact repository, tag, asset name/URL, size, digest, destination, and Java. Package transfer and
parent/destination creation begin only after explicit Install. Change-location or Java selection
builds a fresh quote. Offline/invalid-Java feedback offers retry/setup without a Development or
latest-release fallback.

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
fonts, or the full skin framework. Standard JButton actions, keyboard interaction, mnemonics and
accessible names/descriptions are retained. Focus is visibly outlined.

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
