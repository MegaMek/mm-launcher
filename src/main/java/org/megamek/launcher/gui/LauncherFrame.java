package org.megamek.launcher.gui;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.onboarding.ExistingImportService;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.NormalInstallService;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationCancelledException;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationOutcome;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.PreparedUpdate;
import org.megamek.launcher.update.UpdatePreviewService;
import org.megamek.launcher.update.RealUpdateService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LauncherFrame extends JFrame {
    private static final int LOG_LIMIT = 64_000;
    private static final String NORMAL_FOLDER = "Main";
    private final LauncherServices services;
    private final ExistingImportPrompts existingImportPrompts;
    private final BusyGate gate = new BusyGate();
    private final Map<Component, Boolean> enabledBeforeWork = new IdentityHashMap<>();
    private final JPanel content = new JPanel(new BorderLayout(12, 12));
    private final JLabel status = new JLabel("Loading installations…");
    private final Set<String> checkedOnOpen =
            java.util.Collections.synchronizedSet(new HashSet<>());
    private LauncherServices.HomeState state;
    private ChannelUpdateChecker.Result channelCheck;
    private String channelCheckError;
    private SwingWorker<ChannelUpdateChecker.Result, Void> channelWorker;
    private SwingWorker<String, Void> firstLaunchMilestoneWorker;
    private SwingWorker<Map<String, InstallationCheck>, Void> otherChecksWorker;
    private SwingWorker<InstallationCheck, Void> installationCheckWorker;
    private final Map<String, InstallationCheck> installationChecks = new HashMap<>();
    private final AtomicReference<OperationContext> activeOperation = new AtomicReference<>();
    private final GuiScale guiScale = GuiScale.DEFAULT;
    private java.awt.image.BufferedImage firstLaunchArtwork;
    private Throwable firstLaunchArtworkError;
    private FirstLaunchSplitButton firstLaunchSplitButton;
    private FirstLaunchPanel firstLaunchPanel;
    private boolean homeSizeInitialized;
    private long homeGeneration;
    private Page page = Page.HOME;
    private String selectedInstallationId;
    private LauncherSettingsStore.Settings launcherSettings;
    private Throwable launcherSettingsError;
    private boolean settingsLoading;

    public LauncherFrame(LauncherServices services) {
        this(services, new SwingExistingImportPrompts());
    }

    LauncherFrame(LauncherServices services, ExistingImportPrompts existingImportPrompts) {
        super("MegaMek Launcher");
        this.services = services;
        this.existingImportPrompts = existingImportPrompts;
        status.addPropertyChangeListener("text", event ->
                status.setVisible(status.getText() != null && !status.getText().isBlank()));
        setName("launcherFrame");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(guiScale.scaleForGUI(680, 470));
        setPreferredSize(guiScale.scaleForGUI(820, 560));
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (gate.isBusy()) {
                    OperationContext operation = activeOperation.get();
                    if (operation != null) {
                        OperationContext.CancellationRequest request =
                                operation.requestCancellation();
                        if (request.accepted()) {
                            status.setText("Cancelling at a safe checkpoint...");
                            return;
                        }
                        JOptionPane.showMessageDialog(LauncherFrame.this, request.reason(),
                                "Operation in progress", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
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
        addPropertyChangeListener("graphicsConfiguration", event -> {
            if (isDisplayable()) fitWindowToScreen(getSize());
        });
    }

    public void showWindow() {
        setVisible(true);
        reload();
    }

    private void reload() {
        homeGeneration++;
        disposeFirstLaunchSplitButton();
        cancelFirstLaunchMilestoneWorker();
        cancelChannelWorker();
        channelCheck = null;
        channelCheckError = null;
        installationChecks.clear();
        status.setText("Loading installations…");
        run("Loading installations", () -> {
            try {
                LauncherServices.HomeState home = services.loadHome();
                java.awt.image.BufferedImage artwork = firstLaunchArtwork;
                Throwable artworkError = null;
                if (artwork == null) {
                    try {
                        artwork = FirstLaunchPanel.loadArtwork();
                    } catch (IOException error) {
                        artworkError = error;
                    }
                }
                return new HomeLoad(home, null, artwork, artworkError);
            } catch (IOException e) {
                return new HomeLoad(null, e, null, null);
            }
        }, loaded -> {
            if (loaded.error() == null) {
                state = loaded.state();
                firstLaunchArtwork = loaded.artwork();
                firstLaunchArtworkError = loaded.artworkError();
                renderCurrentPage();
                if (firstLaunchArtworkError != null) {
                    recordErrorAsync("Loading first-launch artwork", firstLaunchArtworkError,
                            message -> status.setText("Artwork unavailable. " + message));
                }
                maybeCheckOnOpen();
                maybeCheckOtherCopiesOnOpen();
            } else {
                renderLoadError(loaded.error());
            }
        });
    }

    private void renderHome() {
        disposeFirstLaunchSplitButton();
        content.removeAll();
        if (state.preferred() == null && state.preferredError() == null) {
            renderFirstLaunch();
            content.revalidate();
            content.repaint();
            return;
        }
        restoreStandardChrome();
        homeSizeInitialized = true;
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("MegaMek Launcher");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setName("homeTitle");
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        status.setText(channelWorker == null ? "Ready" : "Checking for updates…");
        header.add(status);
        content.add(header, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(22, 0, 14, 0));
        if (state.preferredError() != null) renderUnavailable(center);
        else renderPreferred(center, state.preferred(), state.currentInspection());
        content.add(firstLaunchArtwork == null ? center
                : FirstLaunchPanel.managedHome(firstLaunchArtwork, guiScale, center),
                BorderLayout.CENTER);

        JButton manage = button("Installations", "manageInstallationsButton");
        manage.setMnemonic(java.awt.event.KeyEvent.VK_I);
        manage.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        JButton settings = button("Settings", "settingsButton");
        settings.setMnemonic(java.awt.event.KeyEvent.VK_S);
        settings.addActionListener(event -> navigateTo(Page.SETTINGS));
        JPanel left = new JPanel();
        left.add(manage);
        left.add(settings);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(left, BorderLayout.WEST);
        content.add(footer, BorderLayout.SOUTH);
        content.revalidate();
        content.repaint();
    }

    private void renderCurrentPage() {
        switch (page) {
            case HOME -> renderHome();
            case INSTALLATIONS -> renderInstallationsPage();
            case SETTINGS -> renderSettingsPage();
        }
    }

    private void navigateTo(Page next) {
        if (gate.isBusy()) return;
        if (next != Page.HOME) {
            disposeFirstLaunchSplitButton();
            cancelFirstLaunchMilestoneWorker();
        }
        page = next;
        if (next == Page.SETTINGS) {
            launcherSettings = null;
            launcherSettingsError = null;
            loadSettingsPage();
            return;
        }
        renderCurrentPage();
    }

    private void loadSettingsPage() {
        if (settingsLoading) return;
        settingsLoading = true;
        renderSettingsPage();
        run("Loading settings", services::settings, loaded -> {
            settingsLoading = false;
            launcherSettings = loaded;
            launcherSettingsError = null;
            renderSettingsPage();
        }, error -> {
            settingsLoading = false;
            launcherSettings = null;
            launcherSettingsError = error;
            recordErrorAsync("Reading launcher settings", error, ignored -> {
            });
            renderSettingsPage();
        }, () -> {
        }, false);
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
        panel.add(new JLabel("Choose another copy in Installations, or repair this one."));
        JButton installations = button("Choose another copy", "chooseAnotherInstallationButton");
        installations.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        panel.add(installations);
        renderRecoveryAction(panel);
    }

    private void renderLoadError(Throwable error) {
        state = null;
        content.removeAll();
        restoreStandardChrome();
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
        JLabel logging = new JLabel("Checking local diagnostics storage...");
        logging.setName("registryErrorLoggingStatus");
        panel.add(logging);
        recordErrorAsync("Launcher registry could not be loaded", error, logging::setText);
        panel.add(Box.createVerticalStrut(10));
        JButton retry = button("Retry", "retryRegistryButton");
        retry.addActionListener(event -> reload());
        panel.add(retry);
        JButton home = button("Home", "homeButton");
        home.addActionListener(event -> {
            page = Page.HOME;
            reload();
        });
        panel.add(home);
        JButton logs = button("View logs", "viewOperationLogsButton");
        logs.addActionListener(event -> showOperationLogs());
        panel.add(logs);
        content.add(panel, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    private void restoreStandardChrome() {
        content.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(20),
                guiScale.scaleForGUI(24), guiScale.scaleForGUI(18), guiScale.scaleForGUI(24)));
        status.setFont(javax.swing.UIManager.getFont("Label.font"));
        status.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
    }

    private void renderFirstLaunch() {
        content.setBorder(BorderFactory.createEmptyBorder());
        final long generation = homeGeneration;
        final LauncherServices.HomeState capturedHome = state;
        final FirstLaunchSplitButton[] source = new FirstLaunchSplitButton[1];
        FirstLaunchSplitButton download = new FirstLaunchSplitButton(guiScale,
                () -> {
                    if (!isCurrentFirstLaunch(generation, capturedHome, source[0])) return;
                    cancelFirstLaunchMilestoneWorker();
                    prepareNormalInstall(FollowChannel.MILESTONE, null, null);
                },
                () -> {
                    if (!isCurrentFirstLaunch(generation, capturedHome, source[0])) return;
                    cancelFirstLaunchMilestoneWorker();
                    prepareNormalInstall(FollowChannel.DEVELOPMENT, null, null);
                },
                () -> {
                    if (!isCurrentFirstLaunch(generation, capturedHome, source[0])) return;
                    downloadDialog();
                });
        source[0] = download;
        firstLaunchSplitButton = download;
        JButton useExisting = new FirstLaunchButton("Use existing installation",
                "useExistingCopyButton", false, guiScale);
        useExisting.setMnemonic(java.awt.event.KeyEvent.VK_U);
        useExisting.getAccessibleContext().setAccessibleDescription(
                "Choose and statically inspect an existing MegaMek, MekHQ, or MegaMekLab "
                        + "installation without moving its files.");
        useExisting.addActionListener(event -> chooseExisting(true));
        status.setText(firstLaunchArtworkError == null
                ? ""
                : "Artwork unavailable; diagnostics are available in Settings.");
        FirstLaunchPanel firstLaunch = new FirstLaunchPanel(firstLaunchArtwork, guiScale,
                download, useExisting, "Latest Milestone", status);
        firstLaunchPanel = firstLaunch;
        content.add(firstLaunch, BorderLayout.CENTER);
        startFirstLaunchMilestoneCheck(firstLaunch);
        if (!homeSizeInitialized) {
            homeSizeInitialized = true;
            Dimension preferred = firstLaunch.getPreferredSize();
            java.awt.Insets frameInsets = getInsets();
            fitWindowToScreen(new Dimension(preferred.width + frameInsets.left + frameInsets.right,
                    preferred.height + frameInsets.top + frameInsets.bottom));
        }
    }

    private void startFirstLaunchMilestoneCheck(FirstLaunchPanel panel) {
        if (firstLaunchMilestoneWorker != null) return;
        final long generation = homeGeneration;
        firstLaunchMilestoneWorker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return services.latestMilestoneVersion();
            }

            @Override
            protected void done() {
                if (firstLaunchMilestoneWorker != this) return;
                firstLaunchMilestoneWorker = null;
                if (isCancelled() || !isDisplayable() || generation != homeGeneration
                        || page != Page.HOME || state == null || state.preferred() != null
                        || panel != firstLaunchPanel || panel.getParent() != content) {
                    return;
                }
                try {
                    panel.setReleaseChannel("Latest Milestone (" + get() + ")",
                            "Validated from the official Milestone release metadata.");
                } catch (java.util.concurrent.ExecutionException error) {
                    panel.setReleaseChannel("Latest Milestone",
                            "Current version unavailable. Download & install will retry.");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    panel.setReleaseChannel("Latest Milestone",
                            "Current version check was interrupted.");
                }
            }
        };
        firstLaunchMilestoneWorker.execute();
    }

    private boolean isCurrentFirstLaunch(long generation, LauncherServices.HomeState capturedHome,
                                         FirstLaunchSplitButton source) {
        return isDisplayable() && generation == homeGeneration && page == Page.HOME
                && state == capturedHome && state != null && state.preferred() == null
                && source != null && source == firstLaunchSplitButton;
    }

    private void disposeFirstLaunchSplitButton() {
        if (firstLaunchSplitButton != null) {
            firstLaunchSplitButton.disposePopup();
            firstLaunchSplitButton = null;
        }
        firstLaunchPanel = null;
    }

    private void resumeFirstLaunchMilestoneCaption() {
        if (!isDisplayable() || page != Page.HOME || state == null
                || state.preferred() != null || firstLaunchPanel == null
                || firstLaunchSplitButton == null) {
            return;
        }
        status.setText("");
        firstLaunchPanel.setReleaseChannel("Latest Milestone",
                "Checking the current official Milestone release metadata.");
        startFirstLaunchMilestoneCheck(firstLaunchPanel);
    }

    private void fitWindowToScreen(Dimension requested) {
        java.awt.GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) return;
        java.awt.Rectangle available = GuiScale.usableBounds(configuration);
        Dimension fitted = GuiScale.fitWindow(requested, available);
        Dimension minimum = GuiScale.fitWindow(guiScale.scaleForGUI(680, 470), available);
        setMinimumSize(minimum);
        setBounds(Math.max(available.x, Math.min(getX(), available.x + available.width - fitted.width)),
                Math.max(available.y, Math.min(getY(), available.y + available.height - fitted.height)),
                fitted.width, fitted.height);
    }

    private void renderPreferred(JPanel panel, InstallationRecord record, Inspection inspection) {
        JLabel name = new JLabel(record.name());
        name.setName("mainInstallationName");
        name.setFont(name.getFont().deriveFont(Font.BOLD, 21f));
        panel.add(name);
        panel.add(Box.createVerticalStrut(7));
        JLabel version = new JLabel("Version " + inspection.observedBuild());
        version.setName("mainInstallationVersion");
        panel.add(version);
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
        panel.add(Box.createVerticalStrut(guiScale.scaleForGUI(9)));
        JLabel channel = new JLabel(homeChannelText());
        channel.setName("releaseChannelLabel");
        panel.add(channel);
        panel.add(Box.createVerticalStrut(12));
        if (record.javaExecutable() == null) {
            JLabel missing = new JLabel("Java 21 or newer must be set up before launching.");
            missing.setName("missingJavaMessage");
            panel.add(missing);
            JButton java = button("Set up Java", "selectJavaButton");
            java.addActionListener(event -> chooseJava(record));
            panel.add(java);
        }
        renderHomeUpdateAction(panel, record);
        renderRecoveryAction(panel);
        boolean otherAvailable = installationChecks.entrySet().stream()
                .anyMatch(entry -> !entry.getKey().equals(record.id())
                        && entry.getValue().result() != null
                        && entry.getValue().result().updateAvailable());
        if (otherAvailable) {
            JButton others = button("Updates available for other copies",
                    "otherUpdatesButton");
            others.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
            panel.add(Box.createVerticalStrut(8));
            panel.add(others);
        }
    }

    private String homeChannelText() {
        if (state.preferred() == null) return FollowChannel.MILESTONE.toString();
        ChannelPreferenceStore.ReadResult selected = state.channelPreference();
        if (selected == null || selected.status() == ChannelPreferenceStore.Status.UNKNOWN) {
            return "Channel not configured";
        }
        if (selected.status() == ChannelPreferenceStore.Status.UNAVAILABLE) {
            return "Channel setting needs attention";
        }
        return selected.preference().channel().toString();
    }

    private void renderHomeUpdateAction(JPanel panel, InstallationRecord record) {
        if (state.previewEligibility() == null || !state.previewEligibility().available()) {
            JLabel imported = new JLabel("Updates are unavailable for this imported copy; "
                    + "launching still works.");
            imported.setName("updatesUnavailableMessage");
            panel.add(imported);
            return;
        }
        ChannelPreferenceStore.ReadResult selected = state.channelPreference();
        if (selected == null || selected.status() != ChannelPreferenceStore.Status.CONFIGURED) {
            JButton configure = button("Choose update channel in Installations",
                    "configureUpdatesButton");
            configure.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
            panel.add(configure);
            return;
        }
        if (channelCheck == null && channelCheckError == null) {
            JButton check = button(channelWorker == null ? "Check for updates" : "Checking…",
                    "checkUpdatesButton");
            check.setEnabled(channelWorker == null);
            check.addActionListener(event -> startChannelCheck(false));
            panel.add(check);
            return;
        }
        JLabel result = new JLabel(channelStatus(channelCheck, channelCheckError));
        result.setName("channelCheckResult");
        panel.add(result);
        if (channelCheck != null && channelCheck.updateAvailable()) {
            JButton update = button("Update", "recommendedUpdateButton");
            update.addActionListener(event -> recommendedUpdate(channelCheck));
            panel.add(update);
        } else {
            JButton again = button("Check again", "checkUpdatesButton");
            again.addActionListener(event -> startChannelCheck(false));
            panel.add(again);
        }
    }

    private static String channelStatus(ChannelUpdateChecker.Result result, String error) {
        if (error != null) return "Could not check";
        if (result == null) return "Not checked";
        return switch (result.status()) {
            case EXACT_CURRENT -> "Up to date";
            case UPDATE_AVAILABLE -> "Update available";
            case INSTALLED_AHEAD -> "Installed ahead";
            case UNCONFIGURED -> "Channel not configured";
            case UNAVAILABLE, NON_COMPARABLE -> "Could not check";
        };
    }

    private void chooseChannel(InstallationRecord record) {
        final ChannelPreferenceStore.ReadResult read;
        try {
            read = state != null && state.preferred() != null
                    && state.preferred().equals(record) ? state.channelPreference()
                    : services.channelPreference(record);
        } catch (IOException error) {
            showError("Reading update channel failed", error);
            return;
        }
        if (read != null && read.status() == ChannelPreferenceStore.Status.UNAVAILABLE) {
            showError("Update channel needs repair", new IOException(read.reason()));
            return;
        }
        ChannelPreference existing = read == null ? null : read.preference();
        JComboBox<FollowChannel> channels = new JComboBox<>(FollowChannel.values());
        channels.setName("channelChoiceCombo");
        channels.setSelectedItem(existing == null ? null : existing.channel());
        JCheckBox checkOnOpen = new JCheckBox(
                "Check this copy on open (uses the network in the background)",
                existing == null || existing.checkOnOpen());
        checkOnOpen.setName("checkOnOpenCheckbox");
        JPanel choices = new JPanel();
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        choices.add(new JLabel("Follow an official release label:"));
        choices.add(channels);
        choices.add(checkOnOpen);
        choices.add(new JLabel("Changing this setting never applies or downgrades a release."));
        int answer = JOptionPane.showConfirmDialog(this, choices,
                existing == null ? "Choose update channel" : "Change update channel",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        FollowChannel selected = (FollowChannel) channels.getSelectedItem();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Choose Milestone or Development.",
                    "Channel required", JOptionPane.ERROR_MESSAGE);
            return;
        }
        run("Saving channel setting",
                () -> services.setChannel(record, selected, checkOnOpen.isSelected()),
                ignored -> reload());
    }

    private void maybeCheckOnOpen() {
        if (state == null || state.preferred() == null || state.channelPreference() == null
                || state.channelPreference().status() != ChannelPreferenceStore.Status.CONFIGURED
                || !state.channelPreference().preference().checkOnOpen()
                || state.previewEligibility() == null
                || !state.previewEligibility().available()) return;
        ChannelPreference preference = state.channelPreference().preference();
        String key = preference.installationId() + "|" + preference.registeredAt()
                + "|" + preference.channel();
        if (checkedOnOpen.add(key)) startChannelCheck(true);
    }

    private void startChannelCheck(boolean automatic) {
        if (channelWorker != null || state == null || state.preferred() == null
                || state.channelPreference() == null
                || state.channelPreference().status() != ChannelPreferenceStore.Status.CONFIGURED) {
            return;
        }
        final long generation = homeGeneration;
        final InstallationRecord record = state.preferred();
        final ChannelPreference preference = state.channelPreference().preference();
        status.setText(automatic ? "Checking followed channel in background…"
                : "Checking followed channel…");
        channelCheckError = null;
        channelWorker = new SwingWorker<>() {
            @Override
            protected ChannelUpdateChecker.Result doInBackground() throws Exception {
                return services.checkUpdates(record);
            }

            @Override
            protected void done() {
                if (channelWorker != this) return;
                channelWorker = null;
                if (isCancelled() || !isDisplayable() || generation != homeGeneration
                        || state == null || state.preferred() == null
                        || !state.preferred().equals(record)
                        || state.channelPreference() == null
                        || !preference.equals(state.channelPreference().preference())) return;
                try {
                    channelCheck = get();
                    channelCheckError = null;
                    installationChecks.put(record.id(),
                            new InstallationCheck(channelCheck, null));
                    status.setText("Update check complete");
                } catch (java.util.concurrent.CancellationException ignored) {
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException error) {
                    channelCheck = null;
                    Throwable failure = error.getCause();
                    channelCheckError = errorDetail(failure);
                    installationChecks.put(record.id(),
                            new InstallationCheck(null, channelCheckError));
                    recordErrorAsync("Channel check failed", failure, ignored -> {
                    });
                    status.setText("Unable to check for updates");
                }
                if (gate.isBusy()) return;
                renderCurrentPage();
            }
        };
        channelWorker.execute();
        renderCurrentPage();
    }

    private void cancelChannelWorker() {
        if (channelWorker != null) {
            channelWorker.cancel(true);
            channelWorker = null;
        }
        if (otherChecksWorker != null) {
            otherChecksWorker.cancel(true);
            otherChecksWorker = null;
        }
        if (installationCheckWorker != null) {
            installationCheckWorker.cancel(true);
            installationCheckWorker = null;
        }
    }

    private void cancelFirstLaunchMilestoneWorker() {
        if (firstLaunchMilestoneWorker != null) {
            firstLaunchMilestoneWorker.cancel(true);
            firstLaunchMilestoneWorker = null;
        }
    }

    private void startInstallationCheck(InstallationRecord record) {
        if (installationCheckWorker != null) return;
        final long generation = homeGeneration;
        selectedInstallationId = record.id();
        installationChecks.put(record.id(), new InstallationCheck(null, "Checking"));
        renderInstallationsPage();
        installationCheckWorker = new SwingWorker<>() {
            @Override
            protected InstallationCheck doInBackground() {
                try {
                    ChannelUpdateChecker.Result result = services.checkUpdates(record);
                    if (result.preference() != null
                            && !services.isCheckBindingCurrent(record, result.preference())) {
                        return new InstallationCheck(null,
                                "Copy or channel changed; check again");
                    }
                    return new InstallationCheck(result, null);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return new InstallationCheck(null, "Check cancelled");
                } catch (IOException | RuntimeException error) {
                    return new InstallationCheck(null, errorDetail(error));
                }
            }

            @Override
            protected void done() {
                if (installationCheckWorker != this) return;
                installationCheckWorker = null;
                if (isCancelled() || generation != homeGeneration || !isDisplayable()) return;
                try {
                    installationChecks.put(record.id(), get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException error) {
                    installationChecks.put(record.id(),
                            new InstallationCheck(null, errorDetail(error.getCause())));
                }
                if (page == Page.INSTALLATIONS) renderInstallationsPage();
            }
        };
        installationCheckWorker.execute();
    }

    private void maybeCheckOtherCopiesOnOpen() {
        if (state == null || state.registry() == null || otherChecksWorker != null) return;
        final long generation = homeGeneration;
        final RegistryData captured = state.registry();
        final String mainId = captured.defaultInstallationId();
        if (captured.installations().stream().noneMatch(record -> !record.id().equals(mainId))) {
            return;
        }
        otherChecksWorker = new SwingWorker<>() {
            @Override
            protected Map<String, InstallationCheck> doInBackground() {
                Map<String, InstallationCheck> results = new HashMap<>();
                int checked = 0;
                for (InstallationRecord record : captured.installations()) {
                    if (isCancelled() || Thread.currentThread().isInterrupted() || checked >= 32) {
                        break;
                    }
                    if (record.id().equals(mainId)) continue;
                    try {
                        ChannelPreferenceStore.ReadResult selected =
                                services.channelPreference(record);
                        if (selected.status() != ChannelPreferenceStore.Status.CONFIGURED) {
                            results.put(record.id(), new InstallationCheck(null, null,
                                    selected.status() == ChannelPreferenceStore.Status.UNKNOWN
                                            ? "Channel not configured"
                                            : "Channel setting needs attention"));
                            continue;
                        }
                        results.put(record.id(), new InstallationCheck(null, null,
                                selected.preference().channel().toString()));
                        if (!selected.preference().checkOnOpen()) continue;
                        if (!services.canCheckOnOpen(record)) continue;
                        String key = selected.preference().installationId() + "|"
                                + selected.preference().registeredAt() + "|"
                                + selected.preference().channel();
                        if (!checkedOnOpen.add(key)) continue;
                        checked++;
                        ChannelUpdateChecker.Result result = services.checkUpdates(record);
                        if (services.isCheckBindingCurrent(record, result.preference())) {
                            results.put(record.id(), new InstallationCheck(result, null));
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException | RuntimeException error) {
                        results.put(record.id(),
                                new InstallationCheck(null, errorDetail(error)));
                    }
                }
                return Map.copyOf(results);
            }

            @Override
            protected void done() {
                if (otherChecksWorker != this) return;
                otherChecksWorker = null;
                if (isCancelled() || generation != homeGeneration || !isDisplayable()) return;
                try {
                    installationChecks.putAll(get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException error) {
                    recordErrorAsync("Background update checks", error.getCause(), ignored -> {
                    });
                }
                renderCurrentPage();
            }
        };
        otherChecksWorker.execute();
    }

    private void recommendedUpdate(ChannelUpdateChecker.Result checked) {
        if (checked == null || !checked.updateAvailable() || state == null
                || state.preferred() == null || state.previewEligibility() == null
                || !state.previewEligibility().available()) return;
        ChannelUpdateChecker.Recommendation recommendation = checked.recommendation();
        int answer = JOptionPane.showConfirmDialog(this,
                "Installed verified tag: " + checked.currentTag()
                        + "\nFollowing: " + checked.preference().channel()
                        + "\nExact target: " + recommendation.targetTag()
                        + "\nAsset: " + recommendation.assetName()
                        + "\nDownload: "
                        + NumberFormat.getIntegerInstance().format(recommendation.assetSize())
                        + " bytes"
                        + "\nRelease notes: " + recommendation.notesUrl()
                        + "\n\nDownload this exact name, size, and digest for a read-only preview?"
                        + "\nA separate Apply confirmation follows.",
                "Confirm recommended preview download", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        runPreparedUpdate(state.preferred(), state.previewEligibility().receipt(),
                state.previewEligibility().current(), recommendation.targetTag(),
                recommendation.assetName(),
                recommendation.assetSize(), recommendation.assetDigest(), checked);
    }

    private void renderRecoveryAction(JPanel panel) {
        if (state == null || state.preferred() == null) return;
        if (!state.pendingUpdate() && (state.previewEligibility() == null
                || state.previewEligibility().receipt() == null)) return;
        panel.add(Box.createVerticalStrut(5));
        JButton recover = button(state.pendingUpdate()
                        ? "Recover interrupted update…" : "Check update recovery…",
                "recoverUpdateButton");
        recover.setToolTipText("Recovery is available even when static inspection or launch fails.");
        recover.addActionListener(event -> recoverUpdate());
        panel.add(recover);
    }

    private void chooseExisting(boolean firstLaunchEntry) {
        Path selected = existingImportPrompts.chooseFolder(this);
        if (selected == null) return;
        OperationProgressDialog preparation = new OperationProgressDialog(this,
                "Inspecting existing installation", "existingImportProgressLog",
                this::showOperationLogs);
        preparation.append("Inspecting package metadata and validating launcher Java...\n");
        preparation.setVisible(true);
        runOperation("Inspecting existing installation", OperationType.IMPORT_EXISTING,
                List.of(selected), preparation,
                context -> services.prepareExistingImport(selected, context),
                plan -> {
                    preparation.dispose();
                    String defaultName = selected.getFileName() == null
                            ? "MegaMek" : selected.getFileName().toString();
                    String name = existingImportPrompts.chooseName(this, defaultName);
                    if (name == null) {
                        status.setText("Existing installation was not imported");
                        return;
                    }
                    String details = existingImportConfirmation(plan, name);
                    if (!existingImportPrompts.confirm(this, details)) {
                        status.setText("Existing installation was not imported");
                        return;
                    }
                    registerExisting(plan, name, firstLaunchEntry);
                }, error -> preparation.setTitle("Inspection failed"), () -> {}, false);
    }

    private void registerExisting(ExistingImportService.Plan plan, String name,
                                  boolean firstLaunchEntry) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Importing existing installation", "existingImportProgressLog",
                this::showOperationLogs);
        progress.append("Revalidating and registering the confirmed launch-only copy...\n");
        progress.setVisible(true);
        runOperation("Importing existing installation", OperationType.IMPORT_EXISTING,
                List.of(Path.of(plan.inspection().canonicalRoot())), progress,
                context -> services.importExisting(plan, name, context),
                result -> {
                    progress.append("\nRegistered successfully. Nothing was launched or moved.\n");
                    progress.dispose();
                    selectedInstallationId = result.record().id();
                    String message = result.becameMain()
                            ? "Imported “" + result.record().name()
                            + "” and made it Main. Nothing was launched."
                            : "Imported “" + result.record().name()
                            + "”. The existing Main installation was not changed.";
                    existingImportPrompts.completed(this, message);
                    page = firstLaunchEntry && result.becameMain()
                            ? Page.HOME : Page.INSTALLATIONS;
                    reload();
                }, error -> {
                    progress.append("\nIMPORT DID NOT COMPLETE: " + errorDetail(error) + "\n");
                    progress.setTitle("Import failed");
                }, () -> {});
    }

    static String existingImportConfirmation(ExistingImportService.Plan plan, String name) {
        String programs = plan.inspection().products().stream().map(Product::key)
                .map(LauncherFrame::displayProduct).sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        return "Name: " + name
                + "\nDetected build: " + plan.inspection().observedBuild()
                + "\nPrograms: " + programs
                + "\nFolder: " + plan.inspection().canonicalRoot()
                + "\nLauncher Java: Java " + plan.javaFeature() + " detected"
                + "\n\nRegister this copy for launch-only use? Existing files will stay "
                + "where they are and will not be moved. No game JAR has been executed.";
    }

    private void renderInstallationsPage() {
        content.removeAll();
        restoreStandardChrome();
        JPanel header = pageHeader("Installations",
                "Import a portable copy or add an exact official release.");
        content.add(header, BorderLayout.NORTH);

        RegistryData data = state.registry();
        DefaultListModel<InstallationRecord> model = new DefaultListModel<>();
        data.installations().forEach(model::addElement);
        JList<InstallationRecord> list = new JList<>(model);
        list.setName("installationList");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((component, value, index, selected, focus) -> {
            InstallationCheck check = installationChecks.get(value.id());
            String followed = check == null ? null : check.channel();
            if (followed == null && value.id().equals(data.defaultInstallationId())
                    && state.channelPreference() != null) {
                followed = state.channelPreference().status()
                        == ChannelPreferenceStore.Status.CONFIGURED
                        ? state.channelPreference().preference().channel().toString()
                        : state.channelPreference().status()
                        == ChannelPreferenceStore.Status.UNKNOWN
                        ? "Channel not configured" : "Channel setting needs attention";
            }
            String update = check == null ? "Not checked"
                    : "Checking".equals(check.error()) ? "Checking"
                    : channelStatus(check.result(), check.error());
            String target = check == null || check.result() == null
                    || check.result().recommendation() == null ? ""
                    : " → " + check.result().recommendation().targetTag();
            JLabel label = new JLabel((value.id().equals(data.defaultInstallationId()) ? "★ " : "")
                    + value.name() + " · " + value.observedBuild()
                    + (followed == null ? "" : " · " + followed)
                    + " · " + update + target);
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
        JButton add = button("Import existing copy…", "manageAddExistingButton");
        JButton download = button("Add exact release…", "manageDownloadMegaMekButton");
        JButton java = button("Choose Java…", "selectJavaButton");
        JButton channel = button("Channel & checks…", "chooseChannelButton");
        JButton check = button("Check selected", "checkUpdatesButton");
        JButton preview = button("Preview…", "previewUpdateButton");
        JButton update = button("Update…", "applyUpdateButton");
        JButton recover = button("Recover…", "recoverUpdateButton");
        JButton location = button("Show location", "showInstallationLocationButton");
        JPanel buttons = new JPanel(new java.awt.GridLayout(0, 3,
                guiScale.scaleForGUI(8), guiScale.scaleForGUI(6)));
        buttons.setName("installationActionsPanel");
        buttons.add(preferred);
        buttons.add(remove);
        buttons.add(add);
        buttons.add(download);
        buttons.add(java);
        buttons.add(channel);
        buttons.add(check);
        buttons.add(preview);
        buttons.add(update);
        buttons.add(recover);
        buttons.add(location);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        JLabel selectedStatus = new JLabel("Select a copy to see its update status.");
        selectedStatus.setName("channelCheckResult");
        south.add(selectedStatus, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        south.add(pageNavigation(Page.INSTALLATIONS), BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);

        Runnable updateEnabled = () -> {
            boolean selected = list.getSelectedValue() != null;
            preferred.setEnabled(selected);
            remove.setEnabled(selected);
            java.setEnabled(selected);
            channel.setEnabled(selected);
            check.setEnabled(selected);
            preview.setEnabled(selected);
            update.setEnabled(selected);
            recover.setEnabled(selected);
            location.setEnabled(selected);
            if (selected) {
                InstallationRecord record = list.getSelectedValue();
                InstallationCheck current = installationChecks.get(record.id());
                selectedStatus.setText(record.name() + ": "
                        + (current == null ? "Not checked"
                        : "Checking".equals(current.error()) ? "Checking"
                        : channelStatus(current.result(), current.error())));
                selectedStatus.setToolTipText(current == null || current.result() == null
                        ? current == null ? null : current.error()
                        : current.result().reason());
            } else {
                selectedStatus.setText("Select a copy to see its update status.");
                selectedStatus.setToolTipText(null);
            }
        };
        list.addListSelectionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            selectedInstallationId = selected == null ? null : selected.id();
            updateEnabled.run();
        });
        if (selectedInstallationId != null) {
            for (int index = 0; index < model.size(); index++) {
                if (model.get(index).id().equals(selectedInstallationId)) {
                    list.setSelectedIndex(index);
                    break;
                }
            }
        }
        if (list.getSelectedIndex() < 0 && !model.isEmpty()) list.setSelectedIndex(0);
        updateEnabled.run();
        preferred.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected == null) return;
            run("Changing preferred installation", () -> {
                services.select(selected);
                return null;
            }, ignored -> {
                page = Page.HOME;
                reload();
            });
        });
        remove.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected == null) return;
            int answer = JOptionPane.showConfirmDialog(this,
                    "Remove only the launcher record for “" + selected.name()
                            + "”?\nThe files at " + selected.canonicalRoot() + " will be retained.",
                    "Remove registry record", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            run("Removing registry record", () -> {
                services.remove(selected);
                return null;
            }, ignored -> {
                selectedInstallationId = null;
                reload();
            });
        });
        add.addActionListener(event -> chooseExisting(false));
        download.addActionListener(event -> downloadDialog());
        java.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected != null) chooseJava(selected);
        });
        channel.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected != null) chooseChannel(selected);
        });
        check.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected != null) startInstallationCheck(selected);
        });
        update.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected != null) openSelectedUpdate(selected, true);
        });
        preview.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected != null) openSelectedUpdate(selected, false);
        });
        recover.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected != null) recoverUpdate(selected);
        });
        location.addActionListener(event -> {
            InstallationRecord selected = list.getSelectedValue();
            if (selected != null) JOptionPane.showMessageDialog(this,
                    selected.canonicalRoot(), selected.name() + " location",
                    JOptionPane.INFORMATION_MESSAGE);
        });
        content.revalidate();
        content.repaint();
    }

    private JPanel pageHeader(String titleText, String subtitle) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(titleText);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setName("pageTitle");
        header.add(title);
        header.add(new JLabel(subtitle));
        return header;
    }

    private JPanel pageNavigation(Page current) {
        JPanel navigation = new JPanel();
        JButton home = button("Home", "homeButton");
        home.setMnemonic(java.awt.event.KeyEvent.VK_H);
        home.addActionListener(event -> navigateTo(Page.HOME));
        JButton installations = button("Installations", "installationsButton");
        installations.setEnabled(current != Page.INSTALLATIONS);
        installations.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        JButton settings = button("Settings", "settingsButton");
        settings.setEnabled(current != Page.SETTINGS);
        settings.addActionListener(event -> navigateTo(Page.SETTINGS));
        navigation.add(home);
        navigation.add(installations);
        navigation.add(settings);
        return navigation;
    }

    private void renderSettingsPage() {
        content.removeAll();
        restoreStandardChrome();
        content.add(pageHeader("Settings",
                "Launcher preferences and local diagnostics."), BorderLayout.NORTH);
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        if (settingsLoading) {
            JLabel loading = new JLabel("Loading settings…");
            loading.setName("settingsLoadingMessage");
            center.add(loading);
        } else if (launcherSettings != null) {
            JCheckBox checks = new JCheckBox(
                    "Check newly added releases for updates when the launcher opens",
                    launcherSettings.checkNewInstallsOnOpen());
            checks.setName("newInstallCheckDefaultCheckbox");
            checks.getAccessibleContext().setAccessibleDescription(
                    "This default affects only future graphical installs. Existing copy "
                            + "preferences are not changed.");
            center.add(checks);
            center.add(new JLabel("Existing per-copy on/off choices stay unchanged."));
            JButton save = button("Save settings", "saveSettingsButton");
            save.addActionListener(event -> run("Saving settings",
                    () -> services.setCheckNewInstallsOnOpen(checks.isSelected()),
                    saved -> {
                        launcherSettings = saved;
                        launcherSettingsError = null;
                        renderSettingsPage();
                    }));
            center.add(save);
        } else {
            JLabel unavailable = new JLabel("Settings could not be read and were not reset.");
            unavailable.setName("settingsErrorMessage");
            unavailable.setToolTipText(launcherSettingsError == null ? null
                    : errorDetail(launcherSettingsError));
            center.add(unavailable);
            JButton retry = button("Retry settings", "retrySettingsButton");
            retry.addActionListener(event -> loadSettingsPage());
            center.add(retry);
        }
        center.add(Box.createVerticalStrut(18));
        JButton logs = button("View logs", "viewOperationLogsButton");
        logs.addActionListener(event -> showOperationLogs());
        center.add(logs);
        content.add(center, BorderLayout.CENTER);
        content.add(pageNavigation(Page.SETTINGS), BorderLayout.SOUTH);
        content.revalidate();
        content.repaint();
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

    private void prepareNormalInstall(FollowChannel channel, Path destination,
                                      Path selectedJava) {
        final Path target;
        try {
            target = destination == null ? services.normalInstallDestination() : destination;
        } catch (IOException error) {
            showError("Preparing Download & install failed", error);
            resumeFirstLaunchMilestoneCaption();
            return;
        }
        run("Checking the current official " + channel,
                () -> services.prepareNormalInstall(channel, target, selectedJava),
                this::showNormalInstallConfirmation,
                error -> normalPlanFailure(channel, target, selectedJava, error), () -> {
                }, false);
    }

    private void normalPlanFailure(FollowChannel channel, Path target, Path selectedJava,
                                   Throwable error) {
        String detail = errorDetail(error);
        boolean javaProblem = detail.toLowerCase(java.util.Locale.ROOT).contains("java");
        Object[] choices = javaProblem
                ? new Object[]{"Choose Java…", "Retry", "Cancel"}
                : new Object[]{"Retry", "Cancel"};
        int answer = JOptionPane.showOptionDialog(this,
                (javaProblem
                        ? "A compatible external Java 21 or newer could not be verified."
                        : "The current official " + channel + " could not be checked.")
                        + "\n\n" + detail
                        + "\n\nNo package was downloaded and no destination was created.",
                javaProblem ? "Java setup required" : "Could not prepare download",
                JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
                null, choices, choices[0]);
        if (javaProblem && answer == 0) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose Java home or Java executable");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                prepareNormalInstall(channel, target, chooser.getSelectedFile().toPath());
            } else {
                resumeFirstLaunchMilestoneCaption();
            }
        } else if (answer == (javaProblem ? 1 : 0)) {
            prepareNormalInstall(channel, target, selectedJava);
        } else {
            resumeFirstLaunchMilestoneCaption();
        }
    }

    private void showNormalInstallConfirmation(NormalInstallService.Plan plan) {
        String details = "Current official " + plan.channel() + " MekHQ suite\n"
                + "Includes: MegaMek, MekHQ, and MegaMekLab\n\n"
                + "Repository: " + plan.repository().slug() + "\n"
                + "Release: " + plan.release().tag() + "\n"
                + "Asset: " + plan.asset().name() + "\n"
                + "URL: " + plan.asset().url() + "\n"
                + "Size: " + NumberFormat.getIntegerInstance().format(plan.asset().size())
                + " bytes\n"
                + "SHA-256: " + plan.asset().digest() + "\n"
                + "Destination: " + plan.destination() + "\n"
                + "Java: " + plan.javaExecutable() + " (Java " + plan.javaFeature() + ")\n"
                + "Automatic checks when the launcher opens: "
                + (plan.checkConfiguration().checkOnOpen() ? "On" : "Off") + "\n\n"
                + "Nothing has been downloaded or installed yet.";
        JTextArea quote = textArea(details);
        quote.setName("normalInstallConfirmationDetails");
        JButton change = button("Change location…", "changeNormalLocationButton");
        JButton changeJava = button("Choose Java…", "changeNormalJavaButton");
        JButton install = button("Install", "confirmNormalInstallButton");
        install.setMnemonic(java.awt.event.KeyEvent.VK_I);
        JButton cancel = button("Cancel", "cancelNormalInstallButton");
        JPanel actions = new JPanel();
        actions.add(change);
        actions.add(changeJava);
        actions.add(install);
        actions.add(cancel);
        JDialog dialog = dialog("Confirm Download & install",
                new JScrollPane(quote), actions, new Dimension(720, 470));
        boolean[] continuing = {false};
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                if (!continuing[0]) resumeFirstLaunchMilestoneCaption();
            }
        });
        change.addActionListener(event -> {
            JFileChooser chooser = folders("Choose an existing writable parent folder");
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            String value = JOptionPane.showInputDialog(dialog,
                    "New subfolder name (it must not exist):", NORMAL_FOLDER);
            if (value == null) return;
            final Path folder;
            try {
                folder = safeSubfolder(value);
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(dialog, error.getMessage(),
                        "Invalid folder name", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Path changed = chooser.getSelectedFile().toPath().resolve(folder);
            continuing[0] = true;
            dialog.dispose();
            prepareNormalInstall(plan.channel(), changed, plan.javaExecutable());
        });
        changeJava.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose Java home or Java executable");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
            continuing[0] = true;
            dialog.dispose();
            prepareNormalInstall(plan.channel(), plan.destination(),
                    chooser.getSelectedFile().toPath());
        });
        install.addActionListener(event -> {
            continuing[0] = true;
            dialog.dispose();
            runNormalInstall(plan);
        });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(install);
        dialog.setVisible(true);
    }

    private void runNormalInstall(NormalInstallService.Plan plan) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Downloading and installing", "normalInstallProgressLog",
                this::showOperationLogs);
        progress.append("Installing the confirmed official " + plan.channel()
                + " MekHQ suite...\n");
        JButton repair = button("Open Installations", "repairNormalInstallButton");
        repair.setEnabled(false);
        progress.addActionButton(repair);
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation("Downloading and installing", OperationType.FRESH_INSTALL,
                List.of(plan.destination()), progress,
                context -> services.installNormal(plan, stream, context),
                result -> {
                    progress.append("\nInstalled and set as Main. Nothing was launched.\n");
                    JOptionPane.showMessageDialog(progress,
                            "MegaMek, MekHQ, and MegaMekLab are ready on Home.\n"
                                    + "Nothing was launched.",
                            "Install complete", JOptionPane.INFORMATION_MESSAGE);
                    progress.dispose();
                    page = Page.HOME;
                    reload();
                }, error -> {
                    progress.append("\nINSTALL DID NOT COMPLETE: " + errorDetail(error) + "\n");
                    progress.setTitle(error instanceof
                            NormalInstallService.PublishedInstallationException
                            ? "Copy retained — setup needs repair" : "Installation failed");
                    if (error instanceof NormalInstallService.PublishedInstallationException) {
                        repair.setEnabled(true);
                    }
                }, stream::close);
        repair.addActionListener(event -> {
            progress.dispose();
            page = Page.INSTALLATIONS;
            reload();
        });
    }

    void downloadDialog() {
        JComboBox<OfficialRepository> product = new JComboBox<>(new OfficialRepository[]{
                OfficialRepository.MEKHQ, OfficialRepository.MEGAMEK, OfficialRepository.LAB});
        product.setName("downloadProductCombo");
        JComboBox<FollowChannel> channel = new JComboBox<>(FollowChannel.values());
        channel.setName("downloadChannelCombo");
        channel.setSelectedItem(null);
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
        top.add(new JLabel("Follow:"));
        top.add(channel);
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
            install.setEnabled(choice != null && choice.assessment().eligible()
                    && channel.getSelectedItem() != null);
            if (choice != null && !choice.assessment().eligible()) {
                pageLabel.setText(choice.assessment().reason());
            }
        });
        channel.addActionListener(event -> {
            ReleaseChoice choice = releases.getSelectedValue();
            install.setEnabled(choice != null && choice.assessment().eligible()
                    && channel.getSelectedItem() != null);
        });
        install.addActionListener(event -> {
            ReleaseChoice choice = releases.getSelectedValue();
            OfficialRepository repository = loadedRepository[0];
            FollowChannel followed = (FollowChannel) channel.getSelectedItem();
            if (choice == null || repository == null || followed == null
                    || !choice.assessment().eligible()) return;
            chooseInstallDestination(dialog, repository, choice, followed);
        });
        dialog.setVisible(true);
    }

    private void openSelectedUpdate(InstallationRecord record, boolean apply) {
        run("Loading selected installation",
                () -> services.loadInstallation(record.id()), selected -> {
                    if (!selected.preferred().equals(record)) {
                        showError("Opening selected installation failed",
                                new IOException("selected installation changed; choose it again"));
                        return;
                    }
                    if (selected.previewEligibility() == null
                            || !selected.previewEligibility().available()) {
                        showError("Updates unavailable",
                                new IOException(selected.previewEligibility() == null
                                        ? "No verified update information is available."
                                        : selected.previewEligibility().reason()));
                        return;
                    }
                    openUpdatePicker(selected, apply);
                });
    }

    private void openUpdatePicker(LauncherServices.HomeState selectedState,
                                  boolean applyWorkflow) {
        if (selectedState == null || selectedState.preferred() == null
                || selectedState.previewEligibility() == null
                || !selectedState.previewEligibility().available()) return;
        final InstallationRecord sourceRecord = selectedState.preferred();
        final org.megamek.launcher.update.OwnershipReceipt sourceReceipt =
                selectedState.previewEligibility().receipt();
        final org.megamek.launcher.update.CurrentUpdateState sourceCurrent =
                selectedState.previewEligibility().current();
        OfficialRepository repository;
        try {
            repository = OfficialRepository.parse(sourceReceipt.repository());
        } catch (IOException e) {
            showError("Opening update preview failed", e);
            return;
        }
        DefaultListModel<ReleaseChoice> model = new DefaultListModel<>();
        JList<ReleaseChoice> releases = new JList<>(model);
        releases.setName("previewReleaseList");
        JButton fetch = button("Fetch releases", "fetchPreviewReleasesButton");
        JButton next = button("Next page", "nextPreviewReleasePageButton");
        JButton inspect = button("Download and preview…", "runPreviewButton");
        next.setEnabled(false);
        inspect.setEnabled(false);
        JLabel pageLabel = new JLabel("No network request until Fetch releases is selected.");
        final int[] page = {0};
        final boolean[] more = {false};
        JPanel top = new JPanel();
        top.add(new JLabel("Official source: " + repository.slug()));
        top.add(fetch);
        JPanel bottom = new JPanel();
        bottom.add(pageLabel);
        bottom.add(next);
        bottom.add(inspect);
        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.add(top, BorderLayout.NORTH);
        body.add(new JScrollPane(releases), BorderLayout.CENTER);
        body.add(bottom, BorderLayout.SOUTH);
        JDialog dialog = dialog(applyWorkflow ? "Choose update" : "Preview update", body, null,
                new Dimension(780, 460));
        Consumer<Integer> load = requested -> {
            next.setEnabled(false);
            run("Fetching official releases", () -> services.releases(repository, requested),
                    result -> {
                        if (!dialog.isDisplayable()) return;
                        model.clear();
                        result.releases().forEach(release -> model.addElement(new ReleaseChoice(
                                release, services.assess(repository, release))));
                        page[0] = result.page();
                        more[0] = result.mayHaveNextPage();
                        next.setEnabled(more[0]);
                        pageLabel.setText("Page " + page[0]
                                + (more[0] ? " · more may exist" : " · last page"));
                    }, error -> next.setEnabled(page[0] > 0 && more[0]));
        };
        fetch.addActionListener(event -> load.accept(1));
        next.addActionListener(event -> load.accept(page[0] + 1));
        releases.addListSelectionListener(event -> {
            ReleaseChoice choice = releases.getSelectedValue();
            inspect.setEnabled(choice != null && choice.assessment().eligible());
            if (choice != null && !choice.assessment().eligible()) {
                pageLabel.setText(choice.assessment().reason());
            }
        });
        inspect.addActionListener(event -> {
            ReleaseChoice choice = releases.getSelectedValue();
            if (choice == null || !choice.assessment().eligible()) return;
            ReleaseCatalog.Asset asset = choice.assessment().asset();
            int answer = JOptionPane.showConfirmDialog(dialog,
                    "Current verified source: "
                            + (sourceCurrent == null ? sourceReceipt.tag() : sourceCurrent.tag())
                            + " / " + (sourceCurrent == null ? sourceReceipt.assetName()
                            : sourceCurrent.assetName())
                            + "\nTarget: " + choice.release().tag() + " / " + asset.name()
                            + "\nDownload: " + NumberFormat.getIntegerInstance().format(asset.size())
                            + " bytes (a full release package; time depends on your connection)"
                            + "\n\nDownload, verify, and inspect in external temporary storage?"
                            + "\nThis step is READ-ONLY: no installed files or metadata will change."
                            + (applyWorkflow
                            ? "\nA separate destructive Apply confirmation follows the report."
                            : ""),
                    "Confirm read-only preview download", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            dialog.dispose();
            if (applyWorkflow) {
                runPreparedUpdate(sourceRecord, sourceReceipt, sourceCurrent,
                        choice.release().tag(), asset.name(), asset.size(), asset.digest(), null);
            } else {
                runUpdatePreview(sourceRecord, sourceReceipt, choice.release().tag(), false);
            }
        });
        dialog.setVisible(true);
    }

    private void runUpdatePreview(InstallationRecord record,
                                  org.megamek.launcher.update.OwnershipReceipt receipt,
                                  String tag, boolean applyWorkflow) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Downloading update preview", "previewProgressLog", this::showOperationLogs);
        progress.append("Preparing read-only preview for " + tag + "...\n");
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation("Building read-only update preview", OperationType.UPDATE_PREVIEW,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.previewUpdate(record, receipt, tag, stream, context),
                result -> {
                    progress.dispose();
                    showPreviewResult(result);
                }, error -> {
                    progress.append("\nPREVIEW FAILED: " + errorDetail(error) + "\n");
                    progress.setTitle("Update preview failed");
                }, stream::close);
    }

    private boolean showApplyConsent(UpdatePreviewService.Preview preview,
                                     ChannelUpdateChecker.Result recommended) {
        if (preview.currentState() == null) {
            showError("Update unavailable",
                    new IOException("preview did not include current verified provenance"));
            return false;
        }
        long skipped = preview.counts().get(org.megamek.launcher.plan.Action.SKIP);
        String channel = recommended == null ? "" : "\nFollowing channel: "
                + recommended.preference().channel();
        String message = "Installation: " + preview.record().name()
                + "\nFolder: " + preview.record().canonicalRoot()
                + "\nCurrent verified source: " + preview.baseline().tag()
                + "\nTarget: " + preview.targetRelease().tag()
                + channel
                + "\nFull package already downloaded and verified: "
                + NumberFormat.getIntegerInstance().format(preview.targetAsset().size()) + " bytes"
                + "\nSHA-256: " + preview.targetAsset().digest()
                + "\nManaged changes: ADD "
                + preview.counts().get(org.megamek.launcher.plan.Action.ADD)
                + ", REPLACE " + preview.counts().get(org.megamek.launcher.plan.Action.REPLACE)
                + ", REMOVE " + preview.counts().get(org.megamek.launcher.plan.Action.REMOVE)
                + ", preserved SKIP " + skipped
                + "\n\nApplying is destructive to unmodified managed package files. Verified "
                + "backups and a recovery journal are created first. Protected, unknown, and "
                + "modified data are retained."
                + "\n\nCLOSE ALL MegaMek, MekHQ, and MegaMekLab windows now, including copies "
                + "started manually. The launcher coordinates its own launches, but cannot "
                + "guarantee detection of older or externally started applications."
                + "\n\nMetadata, source provenance, local files, and this exact retained package "
                + "will be checked again. The package will NOT be downloaded a second time."
                + "\n\nApply this exact size and digest to the installation named above?";
        int answer = JOptionPane.showConfirmDialog(this, message, "Authorize update Apply",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.OK_OPTION;
    }

    private void runPreparedUpdate(
            InstallationRecord record, org.megamek.launcher.update.OwnershipReceipt receipt,
            org.megamek.launcher.update.CurrentUpdateState current, String tag,
            String assetName, long assetSize, String assetDigest,
            ChannelUpdateChecker.Result recommended) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Preparing verified update", "applyUpdateProgressLog", this::showOperationLogs);
        progress.append("Downloading and verifying the exact consented package once for "
                + tag + "...\n");
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation("Preparing verified update", OperationType.UPDATE_APPLY,
                List.of(Path.of(record.canonicalRoot())), progress, context -> {
                PreparedUpdate prepared = null;
                try {
                    prepared = recommended == null
                            ? services.prepareUpdate(record, receipt, current, tag, assetName,
                            assetSize, assetDigest, stream, context)
                            : services.prepareRecommended(
                                    record, receipt, current, recommended, stream, context);
                    context.checkpoint();
                    if (!isDisplayable()) {
                        context.requestCancellation();
                        PreparedUpdate discarded = prepared;
                        prepared = null;
                        discardPrepared(discarded, context);
                    }
                    AtomicReference<Boolean> authorized = new AtomicReference<>(false);
                    PreparedUpdate captured = prepared;
                    SwingUtilities.invokeAndWait(() -> {
                        if (isDisplayable()) {
                            authorized.set(showApplyConsent(captured.preview(), recommended));
                        }
                    });
                    if (!authorized.get()) {
                        context.requestCancellation();
                        PreparedUpdate discarded = prepared;
                        prepared = null;
                        discardPrepared(discarded, context);
                    }
                    SwingUtilities.invokeLater(() ->
                            progress.setTitle("Applying verified update"));
                    PreparedUpdate applying = prepared;
                    prepared = null; // applyPrepared now owns cleanup on every outcome
                    return services.applyPrepared(
                            applying, RealUpdateService.CONFIRM, stream, context);
                } catch (Exception failure) {
                    if (prepared != null) {
                        try {
                            context.cleanupPreservingCancellation(prepared::close);
                        } catch (IOException cleanup) {
                            failure.addSuppressed(cleanup);
                        } catch (Exception cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                    }
                    throw failure;
                }
            }, result -> {
                progress.dispose();
                String completed = result.skippedDecisions() == 0
                        ? "Update completed and the managed package is pristine."
                        : "Update completed with " + result.skippedDecisions()
                        + " skipped data/collision decisions preserved.";
                if (result.cleanupWarning() != null) {
                    completed += "\n\n" + result.cleanupWarning();
                }
                JOptionPane.showMessageDialog(LauncherFrame.this,
                        completed + "\nRetained override history: "
                                + result.retainedOverrides() + "\nBuild: "
                                + result.record().observedBuild(),
                        result.cleanupWarning() == null
                                ? "Update complete" : "Update complete - cleanup warning",
                        result.cleanupWarning() == null
                                ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                reload();
            }, failure -> {
                    progress.append("\nUPDATE ATTEMPT FAILED: " + errorDetail(failure)
                            + "\nNo second package download was attempted. If application "
                            + "mutation began, use Check update recovery after closing every "
                            + "suite application.\n");
                    progress.setTitle("Update failed — recovery may be required");
            }, stream::close);
    }

    private static void discardPrepared(PreparedUpdate prepared, OperationContext context)
            throws OperationCancelledException {
        Exception cleanupFailure = null;
        context.cleanupPhase("Discarding the prepared package workspace");
        try {
            context.cleanupPreservingCancellation(prepared::close);
        } catch (Exception error) {
            cleanupFailure = error;
        }
        try {
            context.checkpoint();
        } catch (OperationCancelledException cancelled) {
            if (cleanupFailure != null) cancelled.addSuppressed(cleanupFailure);
            throw cancelled;
        }
        throw new IllegalStateException("prepared update discard did not retain cancellation");
    }

    private void recoverUpdate() {
        InstallationRecord record = state == null ? null : state.preferred();
        if (record == null) return;
        recoverUpdate(record);
    }

    private void recoverUpdate(InstallationRecord record) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Close ALL MegaMek, MekHQ, and MegaMekLab applications, including ones started "
                        + "outside this launcher.\n\nRecovery never kills a process and refuses a "
                        + "verified live launcher-started child. It completes a fully applied "
                        + "transaction or rolls back with verified backups; unexpected edits "
                        + "remain untouched and block recovery.\n\nContinue?",
                "Confirm update recovery", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Recovering update", "recoveryProgressLog", this::showOperationLogs);
        progress.append("Recovery is non-cancellable once replay or cleanup begins.\n");
        progress.setVisible(true);
        runOperation("Recovering update", OperationType.RECOVERY,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.recoverUpdate(
                        record, RealUpdateService.CONFIRM, context),
                result -> {
                    progress.dispose();
                    JOptionPane.showMessageDialog(this, result.outcome(), "Recovery complete",
                            JOptionPane.INFORMATION_MESSAGE);
                    reload();
                }, error -> progress.setTitle("Update recovery failed"), () -> {
                });
    }

    private void showPreviewResult(UpdatePreviewService.Preview result) {
        StringBuilder text = new StringBuilder();
        text.append("READ-ONLY point-in-time preview; there is no Apply action.\n")
                .append("Baseline: ").append(result.baseline().tag()).append(" / ")
                .append(result.baseline().assetName()).append('\n')
                .append("Target: ").append(result.targetRelease().tag()).append(" / ")
                .append(result.targetAsset().name()).append('\n')
                .append("Policy: ").append(result.baseline().ownershipPolicyVersion()).append('\n')
                .append("Summary: ").append(result.counts()).append("\n\n")
                .append("Protected paths:\n  ")
                .append(String.join("\n  ", OwnershipPolicy.PROTECTED_PATHS))
                .append("\n\nBaseline package paths excluded from ownership:\n  ")
                .append(String.join("\n  ", result.baseline().excludedOfficialPaths()))
                .append("\n\nTarget package paths excluded from ownership:\n  ")
                .append(String.join("\n  ", result.targetExcludedPaths()))
                .append("\n\nDecisions:\n");
        result.decisions().forEach(decision -> text.append(decision.action()).append('\t')
                .append(decision.path()).append("\t— ").append(decision.reason()).append('\n'));
        JTextArea details = textArea(text.toString());
        details.setName("updatePreviewResults");
        JDialog dialog = dialog("Read-only update preview", new JScrollPane(details), null,
                new Dimension(900, 620));
        dialog.setVisible(true);
    }

    private void chooseInstallDestination(JDialog owner, OfficialRepository repository,
                                          ReleaseChoice choice, FollowChannel channel) {
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
                        + "\nFollowing channel: " + channel
                        + "\nAsset: " + asset.name()
                        + "\nDownload: " + NumberFormat.getIntegerInstance().format(asset.size())
                        + " bytes\nDestination: " + destination
                        + "\n\nDownload, verify, extract, and register this exact release?",
                "Confirm download", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        owner.dispose();
        install(repository, choice.release().tag(), destination, name, channel);
    }

    void install(OfficialRepository repository, String tag, Path destination, String name) {
        install(repository, tag, destination, name, null);
    }

    void install(OfficialRepository repository, String tag, Path destination, String name,
                 FollowChannel channel) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Downloading and installing", "installProgressLog", this::showOperationLogs);
        progress.append("Preparing exact release " + tag + "...\n");
        JButton retry = button("Retry install", "retryInstallButton");
        retry.setEnabled(false);
        progress.addActionButton(retry);
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation("Downloading and installing", OperationType.FRESH_INSTALL,
                List.of(destination), progress, context -> channel == null
                    ? services.install(repository, tag, destination, name, stream, context)
                    : services.install(repository, tag, destination, name, channel, stream, context),
                    result -> {
                        progress.append("\nInstalled and registered at "
                                + result.destination() + "\n");
                        JOptionPane.showMessageDialog(progress,
                                "Installed and registered successfully.\nNothing was launched.",
                                "Install complete", JOptionPane.INFORMATION_MESSAGE);
                        progress.dispose();
                        reload();
                    }, error -> {
                        boolean published = Files.exists(destination,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        progress.append("\nINSTALL FAILED: " + errorDetail(error)
                                + (published
                                ? "\nA published copy is retained. Do not download over it; "
                                + "open Installations to register or repair the copy.\n"
                                : "\nNothing was published. Retry starts a new selection and "
                                + "confirmation.\n"));
                        progress.setTitle(published
                                ? "Copy retained — setup needs repair" : "Installation failed");
                        retry.setText(published ? "Open Installations" : "Retry install");
                        retry.setEnabled(true);
                    }, stream::close);
        retry.addActionListener(event -> {
            progress.dispose();
            if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                page = Page.INSTALLATIONS;
                reload();
            } else {
                downloadDialog();
            }
        });
    }

    private <T> void runOperation(String description, OperationType type,
                                  List<Path> potentialInstallationRoots,
                                  OperationProgressDialog progress,
                                  OperationCallable<T> operation, Consumer<T> success,
                                  Consumer<Throwable> failure, Runnable completion) {
        runOperation(description, type, potentialInstallationRoots, progress, operation, success,
                failure, completion, true);
    }

    private <T> void runOperation(String description, OperationType type,
                                  List<Path> potentialInstallationRoots,
                                  OperationProgressDialog progress,
                                  OperationCallable<T> operation, Consumer<T> success,
                                  Consumer<Throwable> failure, Runnable completion,
                                  boolean persistLog) {
        if (!gate.tryEnter()) {
            progress.dispose();
            JOptionPane.showMessageDialog(this, "Another operation is already running.",
                    "Please wait", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        LauncherServices.LoggedOperation logged = persistLog ? services.beginOperation(
                type, progress, potentialInstallationRoots) : null;
        OperationContext context = logged == null
                ? new OperationContext(type, progress) : logged.context();
        progress.bind(context);
        if (!activeOperation.compareAndSet(null, context)) {
            gate.leave();
            progress.dispose();
            throw new IllegalStateException("operation gate and active context disagree");
        }
        status.setText(description + "...");
        setActionsEnabled(false);
        new SwingWorker<OperationExecution<T>, Void>() {
            @Override
            protected OperationExecution<T> doInBackground() {
                T value = null;
                Throwable problem = null;
                boolean cancelled = false;
                try (OperationContext.WorkerRegistration ignored = context.activate()) {
                    value = operation.call(context);
                    context.checkpoint();
                } catch (Exception error) {
                    problem = error;
                    cancelled = !context.finalizationStarted()
                            && context.cancellationRequested()
                            && (error instanceof InterruptedException
                            || error instanceof OperationCancelledException);
                } finally {
                    try {
                        completion.run();
                    } catch (RuntimeException cleanup) {
                        if (problem == null) problem = cleanup;
                        else problem.addSuppressed(cleanup);
                        cancelled = false;
                    }
                }
                OperationOutcome outcome = problem == null ? OperationOutcome.SUCCEEDED
                        : cancelled ? OperationOutcome.CANCELLED : OperationOutcome.FAILED;
                String detail = outcome == OperationOutcome.SUCCEEDED ? "Operation completed"
                        : outcome == OperationOutcome.CANCELLED
                        ? "Cancelled before finalization" : "Operation failed";
                String warning;
                if (logged == null) {
                    context.finish(outcome, detail);
                    warning = null;
                } else {
                    warning = logged.finish(outcome, detail, problem);
                }
                Thread.interrupted();
                return new OperationExecution<>(value, problem, cancelled, warning);
            }

            @Override
            protected void done() {
                activeOperation.compareAndSet(context, null);
                gate.leave();
                setActionsEnabled(true);
                progress.markFinished();
                OperationExecution<T> result;
                try {
                    result = get();
                } catch (java.util.concurrent.ExecutionException impossible) {
                    result = new OperationExecution<>(null, impossible.getCause(), false,
                            "The operation worker failed before local diagnostics completed.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result = new OperationExecution<>(null, interrupted, false,
                            "The launcher UI was interrupted while collecting the operation result.");
                }
                if (result.loggingWarning() != null) {
                    progress.append("\n" + result.loggingWarning() + "\n");
                }
                if (!isDisplayable()) return;
                if (result.cancelled()) {
                    progress.setTitle("Cancelled");
                    progress.append("\nCANCELLED before installation finalization. "
                            + "Any operation-owned temporary files were discarded.\n");
                    status.setText("Cancelled - no finalization was started");
                    return;
                }
                if (result.problem() != null) {
                    failure.accept(result.problem());
                    showError(description + " failed", result.problem(), false);
                    status.setText("Operation failed - details shown");
                    return;
                }
                success.accept(result.value());
            }
        }.execute();
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
        run(description, operation, success, failure, completion, true);
    }

    private <T> void run(String description, Callable<T> operation, Consumer<T> success,
                         Consumer<Throwable> failure, Runnable completion,
                         boolean showAutomaticError) {
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
                    if (showAutomaticError) {
                        showError(description + (problem instanceof InterruptedException
                                ? " interrupted" : " failed"), problem);
                        status.setText("Operation failed — details shown");
                    } else {
                        status.setText("Operation could not continue");
                    }
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
                if (child instanceof javax.swing.JComponent component
                        && Boolean.TRUE.equals(component.getClientProperty(
                        OperationProgressDialog.OPERATION_CONTROL))) {
                    continue;
                }
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
        showError(title, error, true);
    }

    private void showError(String title, Throwable error, boolean record) {
        JTextArea area = textArea(SanitizedErrors.display(error));
        area.setName("errorDetails");
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(640, 220));
        JButton copy = button("Copy sanitized details", "copyErrorDetailsButton");
        copy.addActionListener(event -> copySanitized(area.getText()));
        JButton logs = button("View logs", "viewOperationLogsButton");
        logs.addActionListener(event -> showOperationLogs());
        JLabel logging = new JLabel(record ? "Saving local diagnostics..." :
                "Local diagnostics were recorded with the operation.");
        logging.setName("errorLoggingStatus");
        JPanel actions = new JPanel();
        actions.add(copy);
        actions.add(logs);
        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.add(scroll, BorderLayout.CENTER);
        body.add(logging, BorderLayout.NORTH);
        body.add(actions, BorderLayout.SOUTH);
        JOptionPane pane = new JOptionPane(body, JOptionPane.ERROR_MESSAGE);
        JDialog errorDialog = pane.createDialog(this, title);
        errorDialog.setModal(false);
        errorDialog.setVisible(true);
        if (record) {
            recordErrorAsync(title, error, message -> {
                if (errorDialog.isDisplayable()) logging.setText(message);
            });
        }
    }

    private void recordErrorAsync(String title, Throwable error, Consumer<String> result) {
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return services.recordGuiError(title, error);
            }

            @Override
            protected void done() {
                try {
                    result.accept(get());
                } catch (java.util.concurrent.ExecutionException problem) {
                    result.accept("Local diagnostics could not be saved: "
                            + SanitizedErrors.display(problem.getCause()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.accept("Local diagnostics save was interrupted.");
                }
            }
        }.execute();
    }

    private void showOperationLogs() {
        JTextArea area = textArea("Loading local operation logs...");
        area.setName("operationLogViewer");
        JButton copy = button("Copy sanitized log", "copyOperationLogButton");
        copy.addActionListener(event -> copySanitized(area.getText()));
        JPanel actions = new JPanel();
        actions.add(copy);
        JDialog viewer = dialog("Local operation logs", new JScrollPane(area), actions,
                new Dimension(850, 600));
        viewer.setVisible(true);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return services.operationLogsForViewer();
            }

            @Override
            protected void done() {
                if (!viewer.isDisplayable()) return;
                try {
                    area.setText(get());
                } catch (java.util.concurrent.ExecutionException problem) {
                    area.setText("Local logs could not be read safely.\n\n"
                            + SanitizedErrors.display(problem.getCause())
                            + "\n\nExpected location: " + services.operationLogLocation());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    area.setText("Reading local logs was interrupted.");
                }
                area.setCaretPosition(0);
            }
        }.execute();
    }

    private static void copySanitized(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(SanitizedErrors.document(text)), null);
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

    static JTextArea textArea(String text) {
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
        return SanitizedErrors.display(error);
    }

    interface ExistingImportPrompts {
        Path chooseFolder(Component parent);

        String chooseName(Component parent, String defaultName);

        boolean confirm(Component parent, String details);

        void completed(Component parent, String message);
    }

    private static final class SwingExistingImportPrompts implements ExistingImportPrompts {
        @Override
        public Path chooseFolder(Component parent) {
            JFileChooser chooser = folders("Choose an extracted MegaMek or MekHQ folder");
            return chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile().toPath() : null;
        }

        @Override
        public String chooseName(Component parent, String defaultName) {
            return JOptionPane.showInputDialog(parent, "Name for this copy:", defaultName);
        }

        @Override
        public boolean confirm(Component parent, String details) {
            return JOptionPane.showConfirmDialog(parent, details,
                    "Confirm existing installation", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
        }

        @Override
        public void completed(Component parent, String message) {
            JOptionPane.showMessageDialog(parent, message, "Import complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static void appendBounded(JTextArea area, String text) {
        if (!area.isDisplayable()) return;
        area.append(text);
        int excess = area.getDocument().getLength() - LOG_LIMIT;
        if (excess > 0) area.replaceRange("", 0, excess);
        area.setCaretPosition(area.getDocument().getLength());
    }

    @Override
    public void dispose() {
        homeGeneration++;
        disposeFirstLaunchSplitButton();
        cancelFirstLaunchMilestoneWorker();
        cancelChannelWorker();
        OperationContext operation = activeOperation.get();
        if (operation != null && !operation.finalizationStarted()) {
            operation.requestCancellation();
        }
        super.dispose();
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

    private record HomeLoad(LauncherServices.HomeState state, Throwable error,
                            java.awt.image.BufferedImage artwork, Throwable artworkError) {
    }

    private record InstallationCheck(ChannelUpdateChecker.Result result, String error,
                                     String channel) {
        private InstallationCheck(ChannelUpdateChecker.Result result, String error) {
            this(result, error, result == null || result.preference() == null
                    ? null : result.preference().channel().toString());
        }
    }

    private enum Page {
        HOME,
        INSTALLATIONS,
        SETTINGS
    }

    @FunctionalInterface
    private interface OperationCallable<T> {
        T call(OperationContext context) throws Exception;
    }

    private record OperationExecution<T>(
            T value, Throwable problem, boolean cancelled, String loggingWarning) {
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
