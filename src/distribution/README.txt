MegaMek Launcher archive prototype
=============================

This is one all-platform portable application archive, not an installer.
Extract it before running it. It contains no Java runtime and requires a
compatible external Java 21 or newer installation.
Separately named host-native portable archives include a Java 21 runtime;
their entry points use the bundled runtime even without external Java.

Windows: run "MegaMek Launcher.exe".
macOS: open "MegaMek Launcher.app".
Linux: run "./mm-launcher" from the extracted "MegaMek Launcher" directory.

Keep this entire extracted directory intact. The one shared Java payload is
inside "MegaMek Launcher.app/Contents/app/lib". The Windows executable and Linux
script use that same payload and must remain beside the app folder.

The desktop entry point opens the graphical launcher by default. It accepts
an optional "--registry <absolute-path>" test/development override. Diagnostic
"--version" and "--startup-check" modes do not open the GUI, access the
network, or create a registry. They optionally accept
"--report <new-absolute-file>" for GUI-subsystem automation.

Application files remain in the extracted archive directory. Registry and
other user state use the OS-specific user-data locations described in the
project README; no startup integration is installed automatically.

This prototype is unsigned and not notarized. Do not bypass operating-system
security controls. Obtain a signed/notarized build when one becomes available.
