# MegaMek graphical launcher prototype

Requires Java 21. From the launcher checkout, build and run in PowerShell:

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat installDist
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
.\build\install\mm-launcher\bin\mm-launcher.bat gui
```

Review **Download MegaMek**, **Use existing copy**, **Preview update**, **Manage installations**,
Java selection, and the launch confirmation. If a copy is already registered, use
**Manage installations > Download MegaMek** to download another copy without removing the
existing one. With no registered copies, **Download MegaMek** also appears on the home screen.
Preview asks to fetch official releases and shows the
exact package and full size before download. It never writes installed application files and has
no Apply control. Preview requires an ownership receipt from one new official download with this
launcher; existing/imported copies (including older launcher downloads) remain launch-only and
cannot be backfilled this round. See the [GUI contract](docs/gui-contract.md) and
[update-preview contract](docs/update-preview-contract.md).

This is launch-only plus read-only preview: there are no applied updates, no bundled Java, or administrator
requirement. Downloads need an existing writable parent folder and always create a new subfolder.
The archived command-line fresh-install walkthrough remains in
[docs/cli-fresh-install.md](docs/cli-fresh-install.md).
