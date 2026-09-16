package org.megamek.launcher.gui;

import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class LauncherFrame extends JFrame {
    private static final int LOG_LIMIT = 64_000;
    private final LauncherServices services;
    private final BusyGate gate = new BusyGate();
    private final Map<Component, Boolean> enabledBeforeWork = new IdentityHashMap<>();
    private final JPanel content = new JPanel(new BorderLayout(12, 12));
    private final JLabel status = new JLabel("Loading installations…");
    private LauncherServices.HomeState state;

    public LauncherFrame(LauncherServices services) {
        super("MegaMek Launcher");
        this.services = services;
        setName("launcherFrame");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(680, 470));
        setPreferredSize(new Dimension(820, 560));
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (gate.isBusy()) {
                    JOptionPane.showMessageDialog(LauncherFrame.this,
                            "An operation is still running. The launcher must remain open until "
                                    + "it finishes; downloads and games are not force-cancelled.",
                            "Operation in progress", JOptionPane.WARNING_MESSAGE);
                } else {
                    dispose();
                }
            }
        });
        pack();
        setLocationByPlatform(true);
    }

    public void showWindow() {
        setVisible(true);
        reload();
    }

    private void reload() {
        status.setText("Loading installations…");
        run("Loading installations", () -> {
            try {
                return new HomeLoad(services.loadHome(), null);
            } catch (IOException e) {
                return new HomeLoad(null, e);
            }
        }, loaded -> {
            if (loaded.error() == null) {
                state = loaded.state();
                renderHome();
            } else {
                renderLoadError(loaded.error());
            }
        });
    }

    private void renderHome() {
        content.removeAll();
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("MegaMek");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setName("homeTitle");
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        status.setText("Launch-only prototype · no automatic updates");
        header.add(status);
        content.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(22, 0, 14, 0));
        if (state.preferredError() != null) renderUnavailable(center);
        else if (state.preferred() == null) renderEmpty(center);
        else renderPreferred(center, state.preferred(), state.currentInspection());
        content.add(center, BorderLayout.CENTER);

        JButton manage = button("Manage installations", "manageInstallationsButton");
        manage.addActionListener(event -> manageInstallations());
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(manage, BorderLayout.WEST);
        JLabel registry = new JLabel("Registry: " + services.registry());
        registry.setToolTipText(services.registry().toString());
        footer.add(registry, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        content.revalidate();
        content.repaint();
    }

    private void renderUnavailable(JPanel panel) {
        JLabel heading = new JLabel("Preferred installation unavailable");
        heading.setName("preferredUnavailableTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        panel.add(heading);
        panel.add(Box.createVerticalStrut(10));
        JTextArea details = textArea(state.preferredError());
        details.setName("preferredUnavailableDetails");
        panel.add(details);
        panel.add(Box.createVerticalStrut(8));
        panel.add(new JLabel("Launching and Java selection are disabled for this copy."));
    }

    private void renderLoadError(Throwable error) {
        state = null;
        content.removeAll();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("Launcher registry could not be loaded");
        heading.setName("registryErrorTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        panel.add(heading);
        panel.add(Box.createVerticalStrut(10));
        JTextArea details = textArea(errorDetail(error));
        details.setName("registryErrorDetails");
        panel.add(details);
        panel.add(Box.createVerticalStrut(10));
        JButton retry = button("Retry", "retryRegistryButton");
        retry.addActionListener(event -> reload());
        panel.add(retry);
        content.add(panel, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    private void renderEmpty(JPanel panel) {
        JLabel heading = new JLabel("Choose how to get started");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        panel.add(heading);
        panel.add(Box.createVerticalStrut(10));
        panel.add(new JLabel("The MekHQ bundle includes MekHQ, MegaMek, and MegaMekLab."));
        panel.add(Box.createVerticalStrut(24));
        JButton download = button("Download MegaMek", "downloadMegaMekButton");
        download.setFont(download.getFont().deriveFont(Font.BOLD, 16f));
        download.addActionListener(event -> downloadDialog());
        panel.add(download);
        panel.add(Box.createVerticalStrut(10));
        JButton existing = button("Use existing copy", "useExistingCopyButton");
        existing.addActionListener(event -> chooseExisting());
        panel.add(existing);
    }

    private void renderPreferred(JPanel panel, InstallationRecord record, Inspection inspection) {
        JLabel name = new JLabel(record.name());
        name.setFont(name.getFont().deriveFont(Font.BOLD, 21f));
        panel.add(name);
        panel.add(Box.createVerticalStrut(7));
        panel.add(new JLabel("Build: " + inspection.observedBuild()));
        panel.add(new JLabel("Folder: " + inspection.canonicalRoot()));
        panel.add(new JLabel("Java: " + (record.javaExecutable() == null
                ? "Not selected" : record.javaExecutable())));
        panel.add(Box.createVerticalStrut(18));
        JPanel launches = new JPanel();
        launches.setLayout(new BoxLayout(launches, BoxLayout.X_AXIS));
        for (Product product : inspection.products()) {
            JButton launch = button("Launch " + displayProduct(product.key()),
                    "launch-" + product.key() + "-button");
            launch.addActionListener(event -> previewLaunch(record, product));
            launches.add(launch);
            launches.add(Box.createHorizontalStrut(8));
        }
        panel.add(launches);
        panel.add(Box.createVerticalStrut(12));
        JButton java = button(record.javaExecutable() == null ? "Select Java…" : "Change Java…",
                "selectJavaButton");
        java.addActionListener(event -> chooseJava(record));
        panel.add(java);
    }

    private void chooseExisting() {
        JFileChooser chooser = folders("Choose an extracted MegaMek or MekHQ folder");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path selected = chooser.getSelectedFile().toPath();
        String name = JOptionPane.showInputDialog(this, "Name for this copy:",
                selected.getFileName() == null ? "MegaMek" : selected.getFileName().toString());
        if (name == null) return;
        run("Inspecting existing copy", () -> services.inspect(selected), inspection -> {
            String products = inspection.products().stream().map(Product::key)
                    .map(LauncherFrame::displayProduct).sorted().toList().toString();
            int answer = JOptionPane.showConfirmDialog(this,
                    "Detected build: " + inspection.observedBuild() + "\nPrograms: " + products
                            + "\nFolder: " + inspection.canonicalRoot()
                            + "\n\nRegister this copy? No JAR has been executed.",
                    "Confirm existing copy", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                run("Registering existing copy", () -> services.register(name, selected),
                        ignored -> reload());
            }
        });
    }

    private void manageInstallations() {
        RegistryData data = state.registry();
        DefaultListModel<InstallationRecord> model = new DefaultListModel<>();
        data.installations().forEach(model::addElement);
        JList<InstallationRecord> list = new JList<>(model);
        list.setName("installationList");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((component, value, index, selected, focus) -> {
            JLabel label = new JLabel((value.id().equals(data.defaultInstallationId()) ? "★ " : "")
                    + value.name() + " — " + value.observedBuild() + " — " + value.canonicalRoot());
            label.setOpaque(true);
            label.setBackground(selected ? component.getSelectionBackground()
                    : component.getBackground());
            label.setForeground(selected ? component.getSelectionForeground()
                    : component.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 7));
            return label;
        });
        JButton preferred = button("Make preferred", "makePreferredButton");
        JButton remove = button("Remove record…", "removeRecordButton");
        JButton add = button("Use existing copy…", "manageAddExistingButton");
        JPanel buttons = new JPanel();
        buttons.add(preferred);
        buttons.add(remove);
        buttons.add(add);
        JDialog dialog = dialog("Manage installations", list, buttons, new Dimension(760, 360));
        preferred.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected == null) return;
            dialog.dispose();
            run("Changing preferred installation", () -> {
                services.select(selected);
                return null;
            }, ignored -> reload());
        });
        remove.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected == null) return;
            int answer = JOptionPane.showConfirmDialog(dialog,
                    "Remove only the launcher record for “" + selected.name()
                            + "”?\nThe files at " + selected.canonicalRoot() + " will be retained.",
                    "Remove registry record", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            dialog.dispose();
            run("Removing registry record", () -> {
                services.remove(selected);
                return null;
            }, ignored -> reload());
        });
        add.addActionListener(event -> {
            dialog.dispose();
            chooseExisting();
        });
        dialog.setVisible(true);
    }

    private void chooseJava(InstallationRecord record) {
        List<Path> candidates = services.javaCandidates(); // nonexecuting discovery
        JComboBox<Object> choices = new JComboBox<>();
        candidates.forEach(choices::addItem);
        choices.addItem("Browse for Java home or executable…");
        choices.setName("javaCandidateCombo");
        int answer = JOptionPane.showConfirmDialog(this, choices,
                "Select external Java 21+", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        Object choice = choices.getSelectedItem();
        Path selected;
        if (choice instanceof Path path) {
            selected = path;
        } else {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose Java home or java executable");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            selected = chooser.getSelectedFile().toPath();
        }
        Path captured = selected;
        run("Validating selected Java", () -> services.selectJava(record, captured), feature -> {
            JOptionPane.showMessageDialog(this, "Java " + feature + " selected.",
                    "Java selected", JOptionPane.INFORMATION_MESSAGE);
            reload();
        });
    }

    private void previewLaunch(InstallationRecord record, Product product) {
        run("Preparing launch preview", () -> services.preview(record, product.key()), command -> {
            JTextArea details = textArea("Copy: " + record.name() + "\nProgram: "
                    + displayProduct(product.key()) + "\nRuntime: " + command.getFirst()
                    + "\n\nCommand (direct arguments; no shell):\n" + command);
            int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(details),
                    "Confirm launch", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                InstallationRecord capturedRecord = record;
                String capturedProduct = product.key();
                run("Running " + displayProduct(capturedProduct),
                        () -> services.launch(capturedRecord, capturedProduct), exit -> {
                            if (exit == 0) {
                                JOptionPane.showMessageDialog(this, "The game has exited.",
                                        "Game finished", JOptionPane.INFORMATION_MESSAGE);
                            } else {
                                showError("Game exited with code " + exit,
                                        new IOException("The selected program returned nonzero "
                                                + "exit code " + exit));
                            }
                            reload();
                        });
            }
        });
    }

    void downloadDialog() {
        JComboBox<OfficialRepository> product = new JComboBox<>(new OfficialRepository[]{
                OfficialRepository.MEKHQ, OfficialRepository.MEGAMEK, OfficialRepository.LAB});
        product.setName("downloadProductCombo");
        DefaultListModel<ReleaseChoice> model = new DefaultListModel<>();
        JList<ReleaseChoice> releases = new JList<>(model);
        releases.setName("releaseList");
        releases.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton fetch = button("Fetch releases", "fetchReleasesButton");
        JButton next = button("Next page", "nextReleasePageButton");
        next.setEnabled(false);
        JButton install = button("Choose destination…", "installReleaseButton");
        install.setEnabled(false);
        JLabel pageLabel = new JLabel("Releases are fetched only when requested.");
        final int[] displayedPage = {0};
        final boolean[] displayedMayHaveNext = {false};
        final OfficialRepository[] loadedRepository = {null};

        JPanel top = new JPanel();
        top.add(new JLabel("Product:"));
        top.add(product);
        top.add(fetch);
        JPanel bottom = new JPanel();
        bottom.add(pageLabel);
        bottom.add(next);
        bottom.add(install);
        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.add(top, BorderLayout.NORTH);
        body.add(new JScrollPane(releases), BorderLayout.CENTER);
        body.add(bottom, BorderLayout.SOUTH);
        JDialog dialog = dialog("Download an official release", body, null,
                new Dimension(760, 450));

        Consumer<Integer> loadPage = requestedPage -> {
            OfficialRepository selectedRepository = (OfficialRepository) product.getSelectedItem();
            next.setEnabled(false);
            run("Fetching official releases",
                    () -> services.releases(selectedRepository, requestedPage), result -> {
                        if (!dialog.isDisplayable()
                                || product.getSelectedItem() != selectedRepository) return;
                        model.clear();
                        result.releases().forEach(release -> model.addElement(new ReleaseChoice(
                                release, services.assess(selectedRepository, release))));
                        loadedRepository[0] = selectedRepository;
                        displayedPage[0] = result.page();
                        displayedMayHaveNext[0] = result.mayHaveNextPage();
                        pageLabel.setText("Page " + result.page()
                                + (result.mayHaveNextPage() ? " · more may exist" : " · last page"));
                        next.setEnabled(result.mayHaveNextPage());
                    }, error -> {
                        if (dialog.isDisplayable()
                                && product.getSelectedItem() == selectedRepository) {
                            next.setEnabled(displayedPage[0] > 0
                                    && displayedMayHaveNext[0]);
                        }
                    });
        };
        fetch.addActionListener(event -> {
            loadPage.accept(1);
        });
        next.addActionListener(event -> {
            loadPage.accept(displayedPage[0] + 1);
        });
        product.addActionListener(event -> {
            model.clear();
            loadedRepository[0] = null;
            displayedPage[0] = 0;
            displayedMayHaveNext[0] = false;
            next.setEnabled(false);
            install.setEnabled(false);
            pageLabel.setText("Select Fetch releases.");
        });
        releases.addListSelectionListener(event -> {
            ReleaseChoice choice = releases.getSelectedValue();
            install.setEnabled(choice != null && choice.assessment().eligible());
            if (choice != null && !choice.assessment().eligible()) {
                pageLabel.setText(choice.assessment().reason());
            }
        });
        install.addActionListener(event -> {
            ReleaseChoice choice = releases.getSelectedValue();
            OfficialRepository repository = loadedRepository[0];
            if (choice == null || repository == null || !choice.assessment().eligible()) return;
            chooseInstallDestination(dialog, repository, choice);
        });
        dialog.setVisible(true);
    }

    private void chooseInstallDestination(JDialog owner, OfficialRepository repository,
                                          ReleaseChoice choice) {
        JFileChooser chooser = folders("Choose an existing writable parent folder");
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Path safeFolder = null;
        String suggestion = choice.release().tag();
        while (safeFolder == null) {
            String folder = JOptionPane.showInputDialog(owner,
                    "New subfolder name (it must not exist):", suggestion);
            if (folder == null) return;
            try {
                safeFolder = safeSubfolder(folder);
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(owner, e.getMessage(), "Invalid subfolder name",
                        JOptionPane.ERROR_MESSAGE);
                suggestion = folder;
            }
        }
        String name = JOptionPane.showInputDialog(owner, "Name for this installation:",
                displayProduct(repository.key()) + " " + choice.release().tag());
        if (name == null) return;
        Path destination = chooser.getSelectedFile().toPath().resolve(safeFolder);
        ReleaseCatalog.Asset asset = choice.assessment().asset();
        int answer = JOptionPane.showConfirmDialog(owner,
                "Release: " + choice.release().title() + " (" + choice.release().tag() + ")"
                        + "\nAsset: " + asset.name()
                        + "\nDownload: " + NumberFormat.getIntegerInstance().format(asset.size())
                        + " bytes\nDestination: " + destination
                        + "\n\nDownload, verify, extract, and register this exact release?",
                "Confirm download", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        owner.dispose();
        install(repository, choice.release().tag(), destination, name);
    }

    void install(OfficialRepository repository, String tag, Path destination, String name) {
        JTextArea log = textArea("Preparing exact release " + tag + "…\n");
        log.setName("installProgressLog");
        JDialog progress = dialog("Downloading and installing", new JScrollPane(log), null,
                new Dimension(700, 380));
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        progress.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (gate.isBusy()) {
                    JOptionPane.showMessageDialog(progress,
                            "Installation is active and cannot be safely cancelled. Wait for it to "
                                    + "finish; no existing installation will be overwritten.",
                            "Install in progress", JOptionPane.WARNING_MESSAGE);
                } else {
                    progress.dispose();
                }
            }
        });
        JButton retry = button("Retry install", "retryInstallButton");
        retry.setEnabled(false);
        JPanel actions = new JPanel();
        actions.add(retry);
        progress.getContentPane().add(actions, BorderLayout.SOUTH);
        progress.setVisible(true);
        Runnable[] attempt = new Runnable[1];
        attempt[0] = () -> {
            PrintStream stream = new PrintStream(new LogOutput(log), true, StandardCharsets.UTF_8);
            retry.setEnabled(false);
            run("Downloading and installing", () -> services.install(repository, tag, destination,
                    name, stream), result -> {
                        appendBounded(log, "\nInstalled and registered at "
                                + result.destination() + "\n");
                        JOptionPane.showMessageDialog(progress,
                                "Installed and registered successfully.\nNothing was launched.",
                                "Install complete", JOptionPane.INFORMATION_MESSAGE);
                        progress.dispose();
                        reload();
                    }, error -> {
                        appendBounded(log, "\nINSTALL FAILED: " + errorDetail(error)
                                + "\nReview the details above; retry is available. A valid "
                                + "extracted copy may be NOT REGISTERED.\n");
                        progress.setTitle("Installation failed");
                        retry.setEnabled(true);
                    }, stream::close);
        };
        retry.addActionListener(event -> attempt[0].run());
        attempt[0].run();
    }

    private <T> void run(String description, Callable<T> operation, Consumer<T> success) {
        run(description, operation, success, ignored -> {}, () -> {});
    }

    private <T> void run(String description, Callable<T> operation, Consumer<T> success,
                         Consumer<Throwable> failure) {
        run(description, operation, success, failure, () -> {});
    }

    private <T> void run(String description, Callable<T> operation, Consumer<T> success,
                         Consumer<Throwable> failure, Runnable completion) {
        if (!gate.tryEnter()) {
            JOptionPane.showMessageDialog(this, "Another operation is already running.",
                    "Please wait", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        status.setText(description + "…");
        setActionsEnabled(false);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return operation.call();
            }

            @Override protected void done() {
                gate.leave();
                setActionsEnabled(true);
                T value = null;
                Throwable problem = null;
                try {
                    value = get();
                } catch (java.util.concurrent.ExecutionException e) {
                    problem = e.getCause();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    problem = e;
                } finally {
                    completion.run();
                }
                if (!isDisplayable()) return;
                if (problem == null) {
                    success.accept(value);
                } else {
                    failure.accept(problem);
                    showError(description + (problem instanceof InterruptedException
                            ? " interrupted" : " failed"), problem);
                    status.setText("Operation failed — details shown");
                }
            }
        }.execute();
    }

    private void setActionsEnabled(boolean enabled) {
        if (!enabled) enabledBeforeWork.clear();
        setChildrenEnabled(getContentPane(), enabled);
        for (Window owned : getOwnedWindows()) {
            setChildrenEnabled(owned, enabled);
        }
        if (enabled) enabledBeforeWork.clear();
    }

    private void setChildrenEnabled(Container parent, boolean enabled) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton || child instanceof JComboBox<?> || child instanceof JList<?>) {
                if (!enabled) {
                    enabledBeforeWork.put(child, child.isEnabled());
                    child.setEnabled(false);
                } else {
                    child.setEnabled(enabledBeforeWork.getOrDefault(child, true));
                }
            }
            if (child instanceof Container container) setChildrenEnabled(container, enabled);
        }
    }

    private void showError(String title, Throwable error) {
        StringBuilder detail = new StringBuilder();
        for (Throwable item = error; item != null; item = item.getCause()) {
            if (!detail.isEmpty()) detail.append("\nCaused by: ");
            detail.append(item.getClass().getSimpleName()).append(": ")
                    .append(item.getMessage() == null ? "(no detail)" : item.getMessage());
        }
        JTextArea area = textArea(detail.toString());
        area.setName("errorDetails");
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(640, 220));
        JOptionPane pane = new JOptionPane(scroll, JOptionPane.ERROR_MESSAGE);
        JDialog errorDialog = pane.createDialog(this, title);
        errorDialog.setModal(false);
        errorDialog.setVisible(true);
    }

    private JDialog dialog(String title, Component center, Component south, Dimension size) {
        JDialog dialog = new JDialog(this, title, false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(center instanceof JScrollPane ? center : new JScrollPane(center),
                BorderLayout.CENTER);
        if (south != null) panel.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.setSize(size);
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    private static JFileChooser folders(String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        return chooser;
    }

    private static JButton button(String text, String name) {
        JButton button = new JButton(text);
        button.setName(name);
        button.getAccessibleContext().setAccessibleName(text);
        return button;
    }

    private static JTextArea textArea(String text) {
        JTextArea area = new JTextArea(text, 10, 65);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        return area;
    }

    private static String displayProduct(String key) {
        return switch (key) {
            case "mekhq" -> "MekHQ";
            case "lab" -> "MegaMekLab";
            case "megamek" -> "MegaMek";
            default -> key;
        };
    }

    static Path safeSubfolder(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Enter one new subfolder name.");
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("The subfolder name is not valid on this system.");
        }
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        boolean windowsReserved = upper.matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"
                + "(\\..*)?");
        if (path.isAbsolute() || path.getNameCount() != 1 || value.equals(".")
                || value.equals("..") || value.contains("/") || value.contains("\\")
                || value.contains(":") || value.endsWith(".") || value.endsWith(" ")
                || windowsReserved) {
            throw new IllegalArgumentException("Enter one safe relative subfolder name (no "
                    + "drive, separators, '.', '..', trailing dot/space, or reserved device name).");
        }
        return path;
    }

    private static String errorDetail(Throwable error) {
        StringBuilder detail = new StringBuilder();
        for (Throwable item = error; item != null; item = item.getCause()) {
            if (!detail.isEmpty()) detail.append("\nCaused by: ");
            detail.append(item.getClass().getSimpleName()).append(": ")
                    .append(item.getMessage() == null ? "(no detail)" : item.getMessage());
        }
        return detail.toString();
    }

    private static void appendBounded(JTextArea area, String text) {
        if (!area.isDisplayable()) return;
        area.append(text);
        int excess = area.getDocument().getLength() - LOG_LIMIT;
        if (excess > 0) area.replaceRange("", 0, excess);
        area.setCaretPosition(area.getDocument().getLength());
    }

    private record ReleaseChoice(ReleaseCatalog.Release release,
                                 ReleaseCatalog.Assessment assessment) {
        @Override public String toString() {
            ReleaseCatalog.Asset asset = assessment.asset();
            String size = asset == null ? "no supported asset"
                    : NumberFormat.getIntegerInstance().format(asset.size()) + " bytes";
            return release.tag() + " — " + release.title() + " — " + size + " — "
                    + assessment.reason();
        }

    }

    private record HomeLoad(LauncherServices.HomeState state, Throwable error) {
    }

    private static final class LogOutput extends OutputStream {
        private final JTextArea area;
        private final StringBuilder pending = new StringBuilder();

        private LogOutput(JTextArea area) {
            this.area = area;
        }

        @Override public synchronized void write(int value) {
            pending.append((char) value);
            if (value == '\n' || pending.length() >= 1024) flush();
        }

        @Override public synchronized void flush() {
            if (pending.isEmpty()) return;
            String text = pending.toString();
            pending.setLength(0);
            SwingUtilities.invokeLater(() -> appendBounded(area, text));
        }
    }
}
