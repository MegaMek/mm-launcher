# MegaMek graphical launcher prototype

Requires Java 21. From the launcher checkout, build and run in PowerShell:

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat installDist
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
.\build\install\mm-launcher\bin\mm-launcher.bat gui
```

Review **Download MegaMek**, **Use existing copy**, **Manage installations**, Java selection, and
the launch confirmation. An existing-copy registration automatically appears from the previous
default registry (`%LOCALAPPDATA%\MegaMek\launcher-registry.json` on Windows). For disposable
registry setup and behavioral details, see the
[GUI contract](docs/gui-contract.md).

This is launch-only: there are no real-install updates, no bundled Java, and no administrator
requirement. Downloads need an existing writable parent folder and always create a new subfolder.
The archived command-line fresh-install walkthrough remains in
[docs/cli-fresh-install.md](docs/cli-fresh-install.md).
