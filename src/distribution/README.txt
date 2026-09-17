MM Launcher archive prototype
=============================

This is a portable application archive, not an installer. Extract it before
running it. It contains no Java runtime and requires a compatible external
Java 21 or newer installation.

Windows: run "MM Launcher.exe".
macOS: open "MM Launcher.app".
Linux: run "./mm-launcher" from the extracted "MM Launcher" directory.

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
