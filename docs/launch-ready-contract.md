# Launch ready contract

The launcher keeps its window up behind a **Launching** card until the application it started
has its first window on screen, then minimises. This is how an application tells it so.

## Opting in

An application opts in through its jar manifest:

```
MegaMek-Launcher-Ready: 1
```

The launcher reads the attribute from the product jar each time it launches. An application
without it is treated as on screen as soon as its process starts, which is how launches behaved
before this contract.

## The ready file

For an application that opts in, the launcher adds one system property to the launch command:

```
-Dmegamek.launcher.readyFile=<absolute path>
```

The file does not exist when the application starts. Once the application's first window is
showing, it creates that file, empty, and ignores any failure to do so. The property only appears
on real launches from the launcher, so its presence also tells the application that the launcher
started it; copied launch commands never carry it.

A minimal implementation, run on the event-dispatch thread after the main window is shown:

```java
String readyFile = System.getProperty("megamek.launcher.readyFile");
if (readyFile != null) {
    try {
        Files.createFile(Path.of(readyFile));
    } catch (IOException | RuntimeException ignored) {
        // The launcher stops waiting on its own.
    }
}
```

## Launcher behaviour

- The launcher polls for the file every 100 ms and deletes it as soon as it appears.
- It stops waiting after 30 seconds and treats the application as on screen anyway.
- When the application exits, the launcher stops waiting and deletes the file if it is still there.
- A launch that ends before it reports ready never minimises the launcher.

## Where the files live

All ready files live in one launcher-owned folder, `~/.mm-launcher/launch-signals` by default
(the `mm.launcher.signals.dir` system property moves it), named `<random UUID>.ready`. The folder
must not be a link. Before each launch the launcher deletes ready files there that are more than a
day old, which can only be left behind if the launcher itself was killed mid-launch; newer files
are left alone because another launcher may still be waiting for them. The folder never grows
beyond the launches in progress plus, at most, a day of such leftovers.
