package org.megamek.launcher.gui;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.onboarding.ExistingImportService;
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
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
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
    private final LauncherServices services;
    private final ExistingImportPrompts existingImportPrompts;
    private final NormalInstallLocationPrompts normalInstallLocationPrompts;
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
    private SwingWorker<QuickInstallSnapshot, Void> firstLaunchOptionsWorker;
    private SwingWorker<Map<String, InstallationCheck>, Void> otherChecksWorker;
    private SwingWorker<InstallationCheck, Void> installationCheckWorker;
    private final Map<String, InstallationCheck> installationChecks = new HashMap<>();
    private final AtomicReference<OperationContext> activeOperation = new AtomicReference<>();
    private final GuiScale guiScale = GuiScale.DEFAULT;
    private java.awt.image.BufferedImage firstLaunchArtwork;
    private Throwable firstLaunchArtworkError;
    private FirstLaunchSplitButton firstLaunchSplitButton;
    private FirstLaunchPanel firstLaunchPanel;
    private final List<HomeLaunchSplitButton> homeLaunchButtons =
            new java.util.ArrayList<>();
    private JButton homeInstallationsButton;
    private JButton homeUpdateSummary;
    private boolean homeSizeInitialized;
    private long homeGeneration;
    private Page page = Page.HOME;
    private String selectedInstallationId;
    private LauncherServices.SettingsView launcherSettings;
    private Throwable launcherSettingsError;
    private boolean settingsLoading;
    private String homeNotice;

    public LauncherFrame(LauncherServices services) {
        this(services, new SwingExistingImportPrompts(),
                new SwingNormalInstallLocationPrompts());
    }

    LauncherFrame(LauncherServices services, ExistingImportPrompts existingImportPrompts) {
        this(services, existingImportPrompts, new SwingNormalInstallLocationPrompts());
    }

    LauncherFrame(LauncherServices services,
                  NormalInstallLocationPrompts normalInstallLocationPrompts) {
        this(services, new SwingExistingImportPrompts(), normalInstallLocationPrompts);
    }

    private LauncherFrame(LauncherServices services,
                          ExistingImportPrompts existingImportPrompts,
                          NormalInstallLocationPrompts normalInstallLocationPrompts) {
        super("MegaMek Launcher");
        this.services = services;
        this.existingImportPrompts = existingImportPrompts;
        this.normalInstallLocationPrompts = normalInstallLocationPrompts;
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
                        if (operation.type() == OperationType.RECOVERY) {
                            status.setText(
                                    "Recovery is finishing — keep the launcher open.");
                            return;
                        }
                        OperationContext.CancellationRequest request =
                                operation.requestCancellation();
                        if (request.accepted()) {
                            status.setText("Cancelling at a safe checkpoint...");
                            return;
                        }
                        status.setText("The operation is finishing — keep the launcher open.");
                        return;
                    }
                    status.setText("An operation is still running — keep the launcher open.");
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
        disposeHomeLaunchButtons();
        cancelFirstLaunchOptionsWorker();
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
        disposeHomeLaunchButtons();
        content.removeAll();
        if (state.registry().installations().isEmpty()) {
            renderFirstLaunch();
            content.revalidate();
            content.repaint();
            return;
        }
        content.setBorder(BorderFactory.createEmptyBorder());
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        homeSizeInitialized = true;
        status.setText(channelWorker == null ? "" : "Checking for updates…");

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setName("managedHomeDeck");
        center.setBackground(FirstLaunchPanel.PANEL);
        center.setBorder(BorderFactory.createEmptyBorder());
        if (homeNotice != null && !homeNotice.isBlank()) {
            JLabel notice = new JLabel(homeNotice);
            notice.setName("installationReadyNotice");
            notice.setOpaque(false);
            notice.setForeground(new Color(232, 211, 146));
            notice.setFont(guiScale.font(notice.getFont(), Font.PLAIN, 12f));
            notice.setBorder(BorderFactory.createEmptyBorder(0,
                    guiScale.scaleForGUI(8), 0, guiScale.scaleForGUI(8)));
            notice.setAlignmentX(Component.CENTER_ALIGNMENT);
            notice.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    notice.getPreferredSize().height));
            center.add(notice);
            center.add(Box.createVerticalStrut(guiScale.scaleForGUI(12)));
        }
        renderApplicationLaunches(center);
        renderHomeUpdateSummary(center);

        center.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));
        status.setForeground(FirstLaunchPanel.MUTED);
        status.setFont(guiScale.font(status.getFont(), Font.PLAIN, 11f));
        status.setAlignmentX(Component.CENTER_ALIGNMENT);
        center.add(status);
        center.add(Box.createVerticalStrut(guiScale.scaleForGUI(5)));

        JButton manage = homeButton("Installations", "manageInstallationsButton");
        manage.setMnemonic(java.awt.event.KeyEvent.VK_I);
        manage.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        homeInstallationsButton = manage;
        updateHomeInstallationsLabel();
        JButton settings = homeButton("Settings", "settingsButton");
        settings.setMnemonic(java.awt.event.KeyEvent.VK_S);
        settings.addActionListener(event -> navigateTo(Page.SETTINGS));
        JPanel navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER,
                guiScale.scaleForGUI(8), 0));
        navigation.setName("managedHomeNavigation");
        navigation.setOpaque(false);
        navigation.setAlignmentX(Component.CENTER_ALIGNMENT);
        navigation.add(manage);
        navigation.add(settings);
        center.add(navigation);

        content.add(FirstLaunchPanel.managedHome(firstLaunchArtwork, guiScale, center),
                BorderLayout.CENTER);
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
        if (next != page) homeNotice = null;
        if (next != Page.HOME) {
            disposeFirstLaunchSplitButton();
            disposeHomeLaunchButtons();
            cancelFirstLaunchOptionsWorker();
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
        run("Loading settings", services::settingsView, loaded -> {
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
        content.setBackground(javax.swing.UIManager.getColor("Panel.background"));
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
                    cancelFirstLaunchOptionsWorker();
                    prepareNormalInstall(OfficialRepository.MEKHQ,
                            FollowChannel.MILESTONE, null, null);
                },
                key -> {
                    if (!isCurrentFirstLaunch(generation, capturedHome, source[0])) return;
                    cancelFirstLaunchOptionsWorker();
                    prepareNormalInstall(key.repository(), key.channel(), null, null);
                },
                () -> {
                    if (!isCurrentFirstLaunch(generation, capturedHome, source[0])) return;
                    resumeFirstLaunchOptions();
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
                download, useExisting, status);
        firstLaunchPanel = firstLaunch;
        content.add(firstLaunch, BorderLayout.CENTER);
        startFirstLaunchOptionsCheck(firstLaunch, download);
        if (!homeSizeInitialized) {
            homeSizeInitialized = true;
            Dimension preferred = firstLaunch.getPreferredSize();
            java.awt.Insets frameInsets = getInsets();
            fitWindowToScreen(new Dimension(preferred.width + frameInsets.left + frameInsets.right,
                    preferred.height + frameInsets.top + frameInsets.bottom));
        }
    }

    private void startFirstLaunchOptionsCheck(FirstLaunchPanel panel,
                                              FirstLaunchSplitButton split) {
        if (firstLaunchOptionsWorker != null) return;
        final long generation = homeGeneration;
        firstLaunchOptionsWorker = new SwingWorker<>() {
            @Override
            protected QuickInstallSnapshot doInBackground() throws Exception {
                return services.quickInstallSnapshot();
            }

            @Override
            protected void done() {
                if (firstLaunchOptionsWorker != this) return;
                firstLaunchOptionsWorker = null;
                if (isCancelled() || !isDisplayable() || generation != homeGeneration
                        || page != Page.HOME || state == null || state.preferred() != null
                        || panel != firstLaunchPanel || split != firstLaunchSplitButton
                        || panel.getParent() != content) {
                    return;
                }
                try {
                    QuickInstallSnapshot snapshot = get();
                    split.setOptions(snapshot);
                } catch (java.util.concurrent.ExecutionException error) {
                    split.setOptionsUnavailable(
                            "Version unavailable. Close and reopen this menu to retry.");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    split.setOptionsUnavailable(
                            "Version check interrupted. Close and reopen this menu to retry.");
                }
            }
        };
        firstLaunchOptionsWorker.execute();
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

    private void disposeHomeLaunchButtons() {
        for (HomeLaunchSplitButton button : homeLaunchButtons) {
            button.disposePopup();
        }
        homeLaunchButtons.clear();
        homeInstallationsButton = null;
        homeUpdateSummary = null;
    }

    private void updateHomeInstallationsLabel() {
        if (homeInstallationsButton == null) return;
        String text = "Installations";
        homeInstallationsButton.setText(text);
        homeInstallationsButton.getAccessibleContext().setAccessibleName(text);
        updateHomeUpdateSummary();
    }

    private void resumeFirstLaunchOptions() {
        if (!isDisplayable() || page != Page.HOME || state == null
                || state.preferred() != null || firstLaunchPanel == null
                || firstLaunchSplitButton == null) {
            return;
        }
        status.setText("");
        firstLaunchSplitButton.setOptionsLoading();
        startFirstLaunchOptionsCheck(firstLaunchPanel, firstLaunchSplitButton);
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

    private void renderApplicationLaunches(JPanel panel) {
        Map<String, InstallationRecord> targets = new java.util.LinkedHashMap<>();
        if (state.preferredApplications() != null) {
            targets.putAll(state.preferredApplications());
        }
        // Compatibility for synthetic HomeState values used by older callers/tests.
        for (String key : List.of("megamek", "mekhq", "lab")) {
            if (targets.containsKey(key)) continue;
            String preferredId = state.registry().preferredInstallationIds().get(key);
            InstallationRecord fallback = state.registry().installations().stream()
                    .filter(record -> record.products().stream()
                            .anyMatch(product -> key.equals(product.key())))
                    .filter(record -> preferredId == null || preferredId.equals(record.id()))
                    .findFirst().orElse(null);
            if (fallback == null) {
                fallback = state.registry().installations().stream()
                        .filter(record -> record.products().stream()
                                .anyMatch(product -> key.equals(product.key())))
                        .findFirst().orElse(null);
            }
            if (fallback != null) targets.put(key, fallback);
        }

        JPanel launches = new ResponsiveHomeActions(guiScale);
        launches.setName("homeLaunchActions");
        launches.setOpaque(false);
        launches.setAlignmentX(Component.CENTER_ALIGNMENT);
        boolean missingJava = false;
        boolean pending = false;
        boolean unavailable = state.preferredError() != null
                && targets.containsValue(state.preferred());
        for (Map.Entry<String, InstallationRecord> target : targets.entrySet()) {
            String productKey = target.getKey();
            InstallationRecord record = target.getValue();
            HomeLaunchSplitButton launch = new HomeLaunchSplitButton(guiScale, productKey,
                    displayProduct(productKey), record.observedBuild(),
                    () -> previewLaunch(record, productKey),
                    homeLaunchAlternatives(record, productKey));
            missingJava |= record.javaExecutable() == null;
            LauncherServices.InstallationStatus local = installationLocalStatus(record);
            pending |= local != null && local.pendingUpdate();
            unavailable |= local != null && local.error() != null;
            launch.setEnabled(record.javaExecutable() != null
                    && (local == null || !local.pendingUpdate() && local.error() == null));
            homeLaunchButtons.add(launch);
            launches.add(launch);
        }
        panel.add(launches);
        if (missingJava || pending || unavailable) {
            panel.add(Box.createVerticalStrut(guiScale.scaleForGUI(7)));
            String message = pending
                    ? "An installation needs update recovery before it can be played."
                    : missingJava
                    ? "A preferred application needs Java 21+ before it can be played."
                    : "An installation changed or is unavailable.";
            JLabel blocker = homeStatusLabel(message);
            blocker.setName("homeActionRequiredMessage");
            blocker.setForeground(FirstLaunchPanel.GOLD);
            panel.add(blocker);
            JButton open = homeButton("Open Installations", "resolveHomeBlockerButton");
            open.setAlignmentX(Component.CENTER_ALIGNMENT);
            open.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
            panel.add(open);
        }
    }

    private void renderHomeUpdateSummary(JPanel panel) {
        panel.add(Box.createVerticalStrut(guiScale.scaleForGUI(9)));
        homeUpdateSummary = homeButton("", "installationUpdateSummary");
        homeUpdateSummary.setAlignmentX(Component.CENTER_ALIGNMENT);
        homeUpdateSummary.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        panel.add(homeUpdateSummary);
        updateHomeUpdateSummary();
    }

    private void updateHomeUpdateSummary() {
        if (homeUpdateSummary == null || state == null || state.registry() == null) return;
        int installations = state.registry().installations().size();
        long updates = installationChecks.values().stream()
                .filter(check -> check.result() != null && check.result().updateAvailable())
                .count();
        boolean failedOrUnknown = installationChecks.size() < installations
                || installationChecks.values().stream().anyMatch(check ->
                check.error() != null || check.result() == null
                        || check.result().status() == ChannelUpdateChecker.Status.UNAVAILABLE
                        || check.result().status() == ChannelUpdateChecker.Status.UNCONFIGURED
                        || check.result().status() == ChannelUpdateChecker.Status.NON_COMPARABLE);
        String text;
        if (updates > 0) {
            text = updates + (updates == 1
                    ? " installation has updates" : " installations have updates");
        } else if (failedOrUnknown) {
            text = "Some versions could not be checked";
        } else {
            text = "All installed versions are up to date";
        }
        homeUpdateSummary.setText(text);
        homeUpdateSummary.getAccessibleContext().setAccessibleName(text);
        homeUpdateSummary.getAccessibleContext().setAccessibleDescription(
                "Open Installations to review per-installation update status.");
    }

    private List<HomeLaunchSplitButton.Option> homeLaunchAlternatives(
            InstallationRecord preferred, String productKey) {
        if (state == null || state.registry() == null
                || state.registry().installations() == null) {
            return List.of();
        }
        return state.registry().installations().stream()
                .filter(record -> !record.id().equals(preferred.id()))
                .filter(record -> record.products() != null && record.products().stream()
                        .anyMatch(product -> product.key().equals(productKey)))
                .map(record -> new HomeLaunchSplitButton.Option(
                        homeAlternativeLabel(record),
                        () -> previewLaunch(record, productKey)))
                .toList();
    }

    private String homeAlternativeLabel(InstallationRecord record) {
        String name = record.name() == null || record.name().isBlank()
                ? "Registered copy" : record.name();
        String version = record.observedBuild() == null || record.observedBuild().isBlank()
                ? "Unknown version" : record.observedBuild();
        InstallationCheck check = installationChecks.get(record.id());
        String channel = check == null ? null : localChannelLabel(check.channel());
        if (channel == null && state != null && state.installationStatuses() != null) {
            LauncherServices.InstallationStatus local = installationLocalStatus(record);
            if (local != null && local.channelPreference() != null
                    && local.channelPreference().status()
                    == ChannelPreferenceStore.Status.CONFIGURED) {
                channel = local.channelPreference().preference().channel().toString();
            }
        }
        String update = check == null ? null : channelStatus(check.result(), check.error());
        return name + " · " + version + (channel == null ? "" : " · " + channel)
                + (update == null ? "" : " · " + update);
    }

    private static String localChannelLabel(String value) {
        if (value == null) return null;
        for (FollowChannel channel : FollowChannel.values()) {
            if (channel.toString().equals(value)) return value;
        }
        return null;
    }

    private JLabel homeStatusLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(FirstLaunchPanel.GOLD);
        label.setFont(guiScale.font(label.getFont(), Font.BOLD, 12f));
        return label;
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
        LauncherServices.InstallationStatus local = installationLocalStatus(record);
        ChannelPreferenceStore.ReadResult read =
                local == null ? null : local.channelPreference();
        if (read == null) {
            showError("Reading update channel failed",
                    new IOException("The installation status changed; reload Installations."));
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
                || !state.automaticChecksEnabled()
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
        channelCheck = null;
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
                if (page == Page.INSTALLATIONS) renderInstallationsPage();
                else if (page == Page.HOME) updateHomeUpdateSummary();
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

    private void cancelFirstLaunchOptionsWorker() {
        if (firstLaunchOptionsWorker != null) {
            firstLaunchOptionsWorker.cancel(true);
            firstLaunchOptionsWorker = null;
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
        if (state == null || state.registry() == null || otherChecksWorker != null
                || !state.automaticChecksEnabled()) return;
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
                if (page == Page.INSTALLATIONS) {
                    renderInstallationsPage();
                } else if (page == Page.HOME) {
                    updateHomeInstallationsLabel();
                }
            }
        };
        otherChecksWorker.execute();
    }

    private void recommendedUpdate(InstallationRecord record,
                                   UpdatePreviewService.Eligibility eligibility,
                                   ChannelUpdateChecker.Result checked) {
        if (checked == null || !checked.updateAvailable() || record == null
                || eligibility == null || !eligibility.available()) return;
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
        runPreparedUpdate(record, eligibility.receipt(),
                eligibility.current(), recommendation.targetTag(),
                recommendation.assetName(),
                recommendation.assetSize(), recommendation.assetDigest(), checked);
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
                    progress.dispose();
                    progress.append("\nRegistered successfully. Nothing was launched or moved.\n");
                    selectedInstallationId = result.record().id();
                    String message = result.becameMain()
                            ? "Imported “" + result.record().name()
                            + "” and set its included applications as preferred. "
                            + "Nothing was launched."
                            : "Imported “" + result.record().name()
                            + "”. Existing application preferences were not changed.";
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
                + "\nGame Java: Java " + plan.javaFeature() + " detected"
                + "\n\nRegister this copy for launch-only use? Existing files will stay "
                + "where they are and will not be moved. No game JAR has been executed.";
    }

    private void renderInstallationsPage() {
        content.removeAll();
        content.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(18),
                guiScale.scaleForGUI(22), guiScale.scaleForGUI(14),
                guiScale.scaleForGUI(22)));
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        JPanel header = pageHeader("Installations",
                "Manage each physical installation independently.");
        header.setBackground(FirstLaunchPanel.BACKGROUND);
        for (Component child : header.getComponents()) child.setForeground(FirstLaunchPanel.TEXT);
        JPanel topActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                guiScale.scaleForGUI(8), 0));
        topActions.setName("installationTopActions");
        topActions.setOpaque(false);
        JButton install = homeButton("Install another version",
                "installAnotherVersionButton");
        install.addActionListener(event -> downloadDialog());
        JButton importExisting = homeButton("Import existing installation",
                "manageAddExistingButton");
        importExisting.addActionListener(event -> chooseExisting(false));
        topActions.add(install);
        topActions.add(importExisting);
        header.add(Box.createVerticalStrut(guiScale.scaleForGUI(8)));
        header.add(topActions);
        content.add(header, BorderLayout.NORTH);

        RegistryData data = state.registry();
        JPanel cards = new JPanel();
        cards.setName("installationCards");
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        cards.setBackground(FirstLaunchPanel.BACKGROUND);
        for (InstallationRecord record : data.installations()) {
            cards.add(installationCard(record));
            cards.add(Box.createVerticalStrut(guiScale.scaleForGUI(9)));
        }
        JScrollPane scroll = new JScrollPane(cards);
        scroll.setName("installationCardsScrollPane");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(FirstLaunchPanel.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(guiScale.scaleForGUI(16));
        content.add(scroll, BorderLayout.CENTER);
        JPanel navigation = pageNavigation(Page.INSTALLATIONS);
        navigation.setBackground(FirstLaunchPanel.BACKGROUND);
        content.add(navigation, BorderLayout.SOUTH);
        content.revalidate();
        content.repaint();
    }

    private JPanel installationCard(InstallationRecord record) {
        JPanel card = new JPanel(new BorderLayout(guiScale.scaleForGUI(12),
                guiScale.scaleForGUI(8)));
        card.setName("installationCard-" + record.id());
        card.setBackground(FirstLaunchPanel.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(84, 116, 108)),
                BorderFactory.createEmptyBorder(guiScale.scaleForGUI(12),
                        guiScale.scaleForGUI(14), guiScale.scaleForGUI(12),
                        guiScale.scaleForGUI(14))));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, guiScale.scaleForGUI(190)));
        card.getAccessibleContext().setAccessibleName(
                record.name() + " " + record.observedBuild());

        JPanel summary = new JPanel();
        summary.setOpaque(false);
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(record.name() + "  " + record.observedBuild());
        name.setForeground(FirstLaunchPanel.TEXT);
        name.setFont(guiScale.font(name.getFont(), Font.BOLD, 18f));
        summary.add(name);
        LauncherServices.InstallationStatus local = installationLocalStatus(record);
        String channel = installationChannel(record, local);
        JLabel metadata = new JLabel(channel + " · " + record.products().stream()
                .map(Product::key).map(LauncherFrame::displayProduct)
                .collect(java.util.stream.Collectors.joining(", ")));
        metadata.setForeground(FirstLaunchPanel.MUTED);
        summary.add(metadata);
        String markers = record.products().stream().map(Product::key)
                .filter(key -> (state.preferredApplications() != null
                        && record.equals(state.preferredApplications().get(key)))
                        || record.id().equals(
                                state.registry().preferredInstallationIds().get(key)))
                .map(key -> "Preferred for " + displayProduct(key))
                .collect(java.util.stream.Collectors.joining(" · "));
        if (!markers.isBlank()) {
            JLabel preferred = new JLabel(markers);
            preferred.setName("preferredApplicationMarkers");
            preferred.setForeground(FirstLaunchPanel.GOLD);
            summary.add(preferred);
        }
        JLabel updateStatus = new JLabel(installationUpdateStatus(record, local));
        updateStatus.setName("installationStatus-" + record.id());
        updateStatus.setForeground(local != null && local.pendingUpdate()
                ? FirstLaunchPanel.GOLD : FirstLaunchPanel.MUTED);
        summary.add(updateStatus);
        card.add(summary, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                guiScale.scaleForGUI(7), 0));
        actions.setOpaque(false);
        InstallationCheck checked = installationChecks.get(record.id());
        boolean updateAvailable = checked != null && checked.result() != null
                && checked.result().updateAvailable();
        boolean updateManaged = local != null && local.previewEligibility() != null
                && local.previewEligibility().available();
        if (local != null && local.pendingUpdate()) {
            JButton recover = homeButton("Recover", "recoverUpdateButton");
            recover.addActionListener(event -> recoverUpdate(record));
            actions.add(recover);
        } else if (updateAvailable && updateManaged) {
            JButton update = homeButton("Update", "applyUpdateButton");
            update.setFont(guiScale.font(update.getFont(), Font.BOLD, 14f));
            ChannelUpdateChecker.Result captured = checked.result();
            UpdatePreviewService.Eligibility eligibility = local.previewEligibility();
            update.addActionListener(event ->
                    recommendedUpdate(record, eligibility, captured));
            actions.add(update);
        }
        if (updateManaged) {
            JButton channel = homeButton("Channel & checks", "chooseChannelButton");
            channel.addActionListener(event -> chooseChannel(record));
            actions.add(channel);
            boolean checking = checked != null && "Checking".equals(checked.error());
            boolean needsCheck = checked == null || checked.error() != null
                    || checked.result() == null
                    || checked.result().status() == ChannelUpdateChecker.Status.UNAVAILABLE
                    || checked.result().status() == ChannelUpdateChecker.Status.UNCONFIGURED
                    || checked.result().status() == ChannelUpdateChecker.Status.NON_COMPARABLE;
            if (needsCheck) {
                String checkText = checking ? "Checking…"
                        : checked != null ? "Retry check" : "Check";
                JButton check = homeButton(checkText, "checkUpdatesButton");
                check.setEnabled(installationCheckWorker == null && !checking);
                check.addActionListener(event -> startInstallationCheck(record));
                actions.add(check);
            }
        }
        JButton more = homeButton("More…", "installationMenuButton-" + record.id());
        JPopupMenu menu = installationMenu(record, local, updateManaged);
        more.getAccessibleContext().setAccessibleDescription(
                "More actions for " + record.name());
        more.addActionListener(event -> menu.show(more, 0, more.getHeight()));
        actions.add(more);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private LauncherServices.InstallationStatus installationLocalStatus(
            InstallationRecord record) {
        LauncherServices.InstallationStatus local = state.installationStatuses() == null
                ? null : state.installationStatuses().get(record.id());
        if (local == null && record.equals(state.preferred())) {
            return new LauncherServices.InstallationStatus(state.channelPreference(),
                    state.previewEligibility(), state.pendingUpdate(),
                    state.preferredError());
        }
        return local;
    }

    private JPopupMenu installationMenu(InstallationRecord record,
                                        LauncherServices.InstallationStatus local,
                                        boolean updateManaged) {
        JPopupMenu menu = new JPopupMenu();
        menu.setName("installationMenu-" + record.id());
        menu.setBackground(HomeLaunchSplitButton.POPUP_BACKGROUND);
        menu.setBorder(BorderFactory.createLineBorder(HomeLaunchSplitButton.POPUP_BORDER));
        for (Product product : record.products()) {
            JMenuItem preferred = installationMenuItem(
                    "Use as preferred for " + displayProduct(product.key()));
            preferred.setEnabled(!record.id().equals(
                    state.registry().preferredInstallationIds().get(product.key())));
            preferred.addActionListener(event -> run(
                    "Changing " + displayProduct(product.key()) + " preference",
                    () -> {
                        services.selectPreferred(product.key(), record);
                        return null;
                    }, ignored -> reload()));
            menu.add(preferred);
        }
        menu.addSeparator();
        JMenuItem java = installationMenuItem("Change Java");
        java.addActionListener(event -> chooseJava(record));
        menu.add(java);
        if (updateManaged) {
            JMenuItem channel = installationMenuItem("Channel & checks");
            channel.addActionListener(event -> chooseChannel(record));
            menu.add(channel);
            JMenuItem preview = installationMenuItem("Preview update");
            preview.addActionListener(event -> openSelectedUpdate(record, false));
            menu.add(preview);
        }
        if (local != null && local.pendingUpdate()) {
            JMenuItem recover = installationMenuItem("Recover interrupted update");
            recover.addActionListener(event -> recoverUpdate(record));
            menu.add(recover);
        }
        JMenuItem location = installationMenuItem("Show location");
        location.addActionListener(event -> JOptionPane.showMessageDialog(this,
                record.canonicalRoot(), record.name() + " location",
                JOptionPane.INFORMATION_MESSAGE));
        menu.add(location);
        JMenuItem remove = installationMenuItem("Remove record");
        remove.addActionListener(event -> removeInstallation(record));
        menu.add(remove);
        return menu;
    }

    private JMenuItem installationMenuItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setOpaque(true);
        item.setBackground(HomeLaunchSplitButton.POPUP_BACKGROUND);
        item.setForeground(HomeLaunchSplitButton.POPUP_FOREGROUND);
        item.getAccessibleContext().setAccessibleName(text);
        return item;
    }

    private String installationChannel(InstallationRecord record,
                                       LauncherServices.InstallationStatus local) {
        InstallationCheck checked = installationChecks.get(record.id());
        if (checked != null && checked.channel() != null) return checked.channel();
        if (local == null || local.channelPreference() == null) return "Updates unavailable";
        return switch (local.channelPreference().status()) {
            case CONFIGURED -> local.channelPreference().preference().channel().toString();
            case UNKNOWN -> "Channel not configured";
            case UNAVAILABLE -> "Channel setting needs attention";
        };
    }

    private String installationUpdateStatus(InstallationRecord record,
                                            LauncherServices.InstallationStatus local) {
        if (local != null && local.pendingUpdate()) return "Recovery required";
        if (local != null && local.error() != null) {
            return "Installation needs attention";
        }
        InstallationCheck checked = installationChecks.get(record.id());
        if (checked == null) {
            return local != null && local.previewEligibility() != null
                    && !local.previewEligibility().available()
                    ? "Launch only · updates unavailable" : "Update status not checked";
        }
        if ("Checking".equals(checked.error())) return "Checking…";
        return channelStatus(checked.result(), checked.error());
    }

    private void removeInstallation(InstallationRecord record) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Remove only the launcher record for “" + record.name()
                        + "”?\nThe application files will be retained.",
                "Remove registry record", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        run("Removing registry record", () -> {
            services.remove(record);
            return null;
        }, ignored -> {
            selectedInstallationId = null;
            reload();
        });
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
        JButton home = homeButton("Home", "homeButton");
        home.setMnemonic(java.awt.event.KeyEvent.VK_H);
        home.addActionListener(event -> navigateTo(Page.HOME));
        JButton installations = homeButton("Installations", "installationsButton");
        installations.setEnabled(current != Page.INSTALLATIONS);
        installations.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        JButton settings = homeButton("Settings", "settingsButton");
        settings.setEnabled(current != Page.SETTINGS);
        settings.addActionListener(event -> navigateTo(Page.SETTINGS));
        navigation.add(home);
        navigation.add(installations);
        navigation.add(settings);
        return navigation;
    }

    private void renderSettingsPage() {
        content.removeAll();
        content.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(18),
                guiScale.scaleForGUI(22), guiScale.scaleForGUI(16),
                guiScale.scaleForGUI(22)));
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        JPanel heading = pageHeader("Settings",
                "Launcher preferences and local diagnostics.");
        heading.setBackground(FirstLaunchPanel.BACKGROUND);
        for (Component child : heading.getComponents()) child.setForeground(FirstLaunchPanel.TEXT);
        content.add(heading, BorderLayout.NORTH);
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setName("settingsSections");
        center.setBackground(FirstLaunchPanel.BACKGROUND);
        if (settingsLoading) {
            JLabel loading = new JLabel("Loading settings…");
            loading.setName("settingsLoadingMessage");
            loading.setForeground(FirstLaunchPanel.MUTED);
            center.add(loading);
        } else if (launcherSettings != null) {
            JPanel updates = settingsSection("Updates");
            JCheckBox checks = new JCheckBox(
                    "Check installed versions when the launcher opens",
                    launcherSettings.settings().checkInstalledVersionsOnOpen());
            checks.setName("installedVersionsCheckMasterCheckbox");
            checks.setOpaque(false);
            checks.setForeground(FirstLaunchPanel.TEXT);
            checks.getAccessibleContext().setAccessibleDescription(
                    "Master gate for automatic checks. Existing per-installation Off choices "
                            + "remain Off.");
            updates.add(checks);
            JLabel preserves = new JLabel(
                    "Per-installation channel and check-on-open choices stay unchanged.");
            preserves.setForeground(FirstLaunchPanel.MUTED);
            updates.add(preserves);
            checks.addActionListener(event -> {
                boolean requested = checks.isSelected();
                checks.setEnabled(false);
                run("Saving automatic update checks",
                    () -> services.setCheckInstalledVersionsOnOpen(requested),
                    saved -> {
                        launcherSettings = new LauncherServices.SettingsView(saved,
                                launcherSettings.defaultJava(),
                                launcherSettings.defaultJavaFeature(),
                                launcherSettings.defaultJavaPersisted());
                        launcherSettingsError = null;
                        renderSettingsPage();
                    }, error -> {
                        checks.setSelected(!requested);
                        showError("Automatic check setting was not saved", error);
                        renderSettingsPage();
                    }, () -> {
                    }, false);
            });
            center.add(updates);
            center.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));

            JPanel java = settingsSection("Game Java");
            JLabel runtime = new JLabel("Java " + launcherSettings.defaultJavaFeature()
                    + (launcherSettings.defaultJavaPersisted()
                    ? " · launcher default" : " · current launcher runtime (not yet saved)"));
            runtime.setName("defaultJavaStatus");
            runtime.setForeground(FirstLaunchPanel.TEXT);
            java.add(runtime);
            JTextArea javaPath = textArea(launcherSettings.defaultJava().toString());
            javaPath.setName("defaultJavaPath");
            javaPath.setToolTipText(launcherSettings.defaultJava().toString());
            javaPath.setLineWrap(false);
            javaPath.setRows(1);
            javaPath.setBackground(FirstLaunchPanel.PANEL);
            javaPath.setForeground(FirstLaunchPanel.MUTED);
            java.add(javaPath);
            JButton changeJava = homeButton("Change default Java",
                    "changeDefaultJavaButton");
            changeJava.setAlignmentX(Component.LEFT_ALIGNMENT);
            changeJava.addActionListener(event -> chooseDefaultJava());
            java.add(changeJava);
            center.add(java);
            center.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));

            JPanel diagnostics = settingsSection("Diagnostics");
            JButton logs = homeButton("View logs", "viewOperationLogsButton");
            logs.setAlignmentX(Component.LEFT_ALIGNMENT);
            logs.addActionListener(event -> showOperationLogs());
            diagnostics.add(logs);
            center.add(diagnostics);
        } else {
            JLabel unavailable = new JLabel("Settings could not be read and were not reset.");
            unavailable.setName("settingsErrorMessage");
            unavailable.setForeground(FirstLaunchPanel.GOLD);
            unavailable.setToolTipText(launcherSettingsError == null ? null
                    : errorDetail(launcherSettingsError));
            center.add(unavailable);
            JButton retry = homeButton("Retry settings", "retrySettingsButton");
            retry.addActionListener(event -> loadSettingsPage());
            center.add(retry);
        }
        JScrollPane scroll = new JScrollPane(center);
        scroll.setName("settingsScrollPane");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(FirstLaunchPanel.BACKGROUND);
        content.add(scroll, BorderLayout.CENTER);
        JPanel navigation = pageNavigation(Page.SETTINGS);
        navigation.setBackground(FirstLaunchPanel.BACKGROUND);
        content.add(navigation, BorderLayout.SOUTH);
        content.revalidate();
        content.repaint();
    }

    private JPanel settingsSection(String titleText) {
        JPanel section = new JPanel();
        section.setName("settings" + titleText.replace(" ", "") + "Section");
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(FirstLaunchPanel.PANEL);
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(92, 121, 112)),
                BorderFactory.createEmptyBorder(guiScale.scaleForGUI(12),
                        guiScale.scaleForGUI(14), guiScale.scaleForGUI(12),
                        guiScale.scaleForGUI(14))));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                guiScale.scaleForGUI(150)));
        JLabel title = new JLabel(titleText);
        title.setForeground(FirstLaunchPanel.GOLD);
        title.setFont(guiScale.font(title.getFont(), Font.BOLD, 17f));
        section.add(title);
        section.add(Box.createVerticalStrut(guiScale.scaleForGUI(7)));
        return section;
    }

    private void chooseDefaultJava() {
        run("Finding Java runtimes", services::javaCandidates,
                this::chooseDefaultJavaFromCandidates);
    }

    private void chooseDefaultJavaFromCandidates(List<Path> candidates) {
        JComboBox<Object> choices = new JComboBox<>();
        candidates.forEach(choices::addItem);
        choices.addItem("Browse for Java home or executable…");
        choices.setName("defaultJavaCandidateCombo");
        int answer = JOptionPane.showConfirmDialog(this, choices,
                "Change default game Java", JOptionPane.OK_CANCEL_OPTION);
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
        run("Validating default game Java",
                () -> services.selectDefaultJava(captured), saved -> {
                    launcherSettings = null;
                    launcherSettingsError = null;
                    loadSettingsPage();
                });
    }

    private void chooseJava(InstallationRecord record) {
        run("Finding Java runtimes", services::javaCandidates,
                candidates -> chooseJavaFromCandidates(record, candidates));
    }

    private void chooseJavaFromCandidates(InstallationRecord record, List<Path> candidates) {
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

    private void previewLaunch(InstallationRecord record, String productKey) {
        homeNotice = null;
        run("Preparing launch preview", () -> services.preview(record, productKey), command -> {
            JTextArea details = textArea("Copy: " + record.name() + "\nProgram: "
                    + displayProduct(productKey) + "\nRuntime: " + command.getFirst()
                    + "\n\nCommand (direct arguments; no shell):\n" + command);
            int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(details),
                    "Confirm launch", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                InstallationRecord capturedRecord = record;
                String capturedProduct = productKey;
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

    private void prepareNormalInstall(OfficialRepository repository, FollowChannel channel,
                                      Path destination, Path selectedJava) {
        final Path target;
        try {
            target = destination == null
                    ? services.normalInstallDestination(repository, channel) : destination;
        } catch (IOException error) {
            showError("Preparing " + quickChoiceName(repository, channel) + " failed", error);
            resumeFirstLaunchOptions();
            return;
        }
        run("Checking " + quickChoiceName(repository, channel),
                () -> services.prepareNormalInstall(repository, channel, target, selectedJava),
                this::showNormalInstallConfirmation,
                error -> normalPlanFailure(repository, channel, target, selectedJava, error), () -> {
                }, false);
    }

    private void normalPlanFailure(OfficialRepository repository, FollowChannel channel,
                                   Path target, Path selectedJava, Throwable error) {
        String detail = errorDetail(error);
        boolean javaProblem = detail.toLowerCase(java.util.Locale.ROOT).contains("java");
        Object[] choices = javaProblem
                ? new Object[]{"Choose Java…", "Retry", "Cancel"}
                : new Object[]{"Retry", "Cancel"};
        int answer = JOptionPane.showOptionDialog(this,
                (javaProblem
                        ? "A compatible external Java 21 or newer could not be verified."
                        : quickChoiceName(repository, channel) + " could not be checked.")
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
                prepareNormalInstall(repository, channel, target,
                        chooser.getSelectedFile().toPath());
            } else {
                resumeFirstLaunchOptions();
            }
        } else if (answer == (javaProblem ? 1 : 0)) {
            prepareNormalInstall(repository, channel, target, selectedJava);
        } else {
            resumeFirstLaunchOptions();
        }
    }

    private void showNormalInstallConfirmation(NormalInstallService.Plan plan) {
        String product = displayProduct(plan.repository().key());
        List<String> programs = List.of("megamek", "mekhq", "lab").stream()
                .filter(plan.requiredProducts()::contains)
                .map(LauncherFrame::displayProduct)
                .toList();
        boolean[] continuing = {false};
        NormalInstallConfirmationDialog dialog = new NormalInstallConfirmationDialog(
                this, plan, product, programs, guiScale, source -> {
                    final Path changed;
                    try {
                        changed = normalInstallLocationPrompts.choose(source, plan);
                    } catch (IOException error) {
                        showError("Preparing install location failed", error);
                        return false;
                    }
                    if (changed == null) return false;
                    continuing[0] = true;
                    source.dispose();
                    prepareNormalInstall(plan.repository(), plan.channel(), changed,
                            plan.javaExecutable());
                    return true;
                }, source -> {
                    continuing[0] = true;
                    source.dispose();
                    runNormalInstall(plan);
                    return true;
                });
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                if (!continuing[0]) resumeFirstLaunchOptions();
            }
        });
        dialog.setVisible(true);
    }

    private void runNormalInstall(NormalInstallService.Plan plan) {
        String product = displayProduct(plan.repository().key());
        String operationDescription = "Installing " + product + " " + plan.version();
        OperationProgressDialog progress = new OperationProgressDialog(this,
                operationDescription, "normalInstallProgressLog", this::showOperationLogs);
        progress.append("Installing the confirmed official " + product + " "
                + plan.channel() + "...\n");
        JButton repair = button("Open Installations", "repairNormalInstallButton");
        repair.setEnabled(false);
        progress.addActionButton(repair);
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation(operationDescription, OperationType.FRESH_INSTALL,
                List.of(plan.destination()), progress,
                context -> services.installNormal(plan, stream, context),
                result -> {
                    progress.dispose();
                    progress.append(result.becameMain()
                                    ? "\nInstalled and set as the initial preferred copy. "
                                    + "Nothing was launched.\n"
                                    : "\nInstalled; existing application preferences were preserved. "
                                    + "Nothing was launched.\n");
                    String installedVersion = result.record().observedBuild();
                    homeNotice = product + " " + installedVersion + " is ready";
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
        progress.startNonCancellable(
                "Recovery is starting and must remain open until it finishes.");
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
        JButton repair = button("Open Installations", "repairPublishedInstallButton");
        repair.setEnabled(false);
        progress.addActionButton(repair);
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation("Downloading and installing", OperationType.FRESH_INSTALL,
                List.of(destination), progress, context -> channel == null
                    ? services.install(repository, tag, destination, name, stream, context)
                    : services.install(repository, tag, destination, name, channel, stream, context),
                    result -> {
                        progress.dispose();
                        progress.append("\nInstalled and registered at "
                                + result.destination() + "\n");
                        JOptionPane.showMessageDialog(LauncherFrame.this,
                                "Installed and registered successfully.\nNothing was launched.",
                                "Install complete", JOptionPane.INFORMATION_MESSAGE);
                        reload();
                    }, error -> {
                        boolean published = Files.exists(destination,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        progress.append("\nINSTALL FAILED: " + errorDetail(error)
                                + (published
                                ? "\nA published copy is retained. Do not download over it; "
                                + "open Installations to register or repair the copy.\n"
                                : "\nNothing was published.\n"));
                        progress.setTitle(published
                                ? "Copy retained — setup needs repair" : "Installation failed");
                        repair.setEnabled(published);
                    }, stream::close);
        repair.addActionListener(event -> {
            progress.dispose();
            page = Page.INSTALLATIONS;
            reload();
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
                    progress.append("\nCANCELLED before installation finalization. "
                            + "Any operation-owned temporary files were discarded.\n");
                    progress.showCancelled();
                    if (page == Page.HOME && state != null && state.preferred() == null) {
                        resumeFirstLaunchOptions();
                    }
                    status.setText(type == OperationType.FRESH_INSTALL
                            ? "Installation cancelled" : description + " cancelled");
                    return;
                }
                if (result.problem() != null) {
                    failure.accept(result.problem());
                    progress.showFailure(result.problem(), result.loggingWarning());
                    status.setText("Operation failed — details are available");
                    return;
                }
                success.accept(result.value());
                if (result.loggingWarning() != null) {
                    status.setText(
                            "Operation completed, but local diagnostics could not be saved.");
                }
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

    void showOperationFailureDetails(String title, Throwable error) {
        showError(title, error, false);
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
                "Local operation diagnostics are available when recording succeeded.");
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

    private JButton homeButton(String text, String name) {
        JButton button = new FirstLaunchButton(text, name, false, guiScale,
                FirstLaunchButton.Size.SMALL);
        button.getAccessibleContext().setAccessibleDescription(
                text + " on the managed Home screen.");
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

    private static String quickChoiceName(OfficialRepository repository,
                                          FollowChannel channel) {
        return "Latest " + displayProduct(repository.key()) + " " + channel;
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

    @FunctionalInterface
    interface NormalInstallLocationPrompts {
        Path choose(Component parent, NormalInstallService.Plan plan) throws IOException;
    }

    private static final class SwingNormalInstallLocationPrompts
            implements NormalInstallLocationPrompts {
        @Override
        public Path choose(Component parent, NormalInstallService.Plan plan) throws IOException {
            JFileChooser chooser = folders("Choose an existing writable parent folder");
            if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
            String proposedName =
                    NormalInstallService.defaultFolderName(plan.repository(), plan.channel());
            String value = JOptionPane.showInputDialog(parent,
                    "New subfolder name (it must not exist):", proposedName);
            if (value == null) return null;
            final Path folder;
            try {
                folder = safeSubfolder(value);
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(parent, error.getMessage(),
                        "Invalid folder name", JOptionPane.ERROR_MESSAGE);
                return null;
            }
            return chooser.getSelectedFile().toPath().resolve(folder);
        }
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
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendBounded(area, text));
            return;
        }
        area.append(text);
        int excess = area.getDocument().getLength() - LOG_LIMIT;
        if (excess > 0) area.replaceRange("", 0, excess);
        area.setCaretPosition(area.getDocument().getLength());
    }

    @Override
    public void dispose() {
        homeGeneration++;
        disposeFirstLaunchSplitButton();
        disposeHomeLaunchButtons();
        cancelFirstLaunchOptionsWorker();
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

    private static final class ResponsiveHomeActions extends JPanel {
        private final GuiScale scale;
        private final int gap;
        private int columns = 3;

        private ResponsiveHomeActions(GuiScale scale) {
            this.scale = scale;
            gap = scale.scaleForGUI(8);
            setLayout(new java.awt.GridLayout(0, columns, gap, gap));
        }

        @Override
        public void doLayout() {
            int count = Math.max(1, getComponentCount());
            int cell = scale.scaleForGUI(210);
            int next = Math.max(1, Math.min(Math.min(3, count),
                    Math.max(1, (getWidth() + gap) / (cell + gap))));
            if (next != columns) {
                columns = next;
                java.awt.GridLayout layout = (java.awt.GridLayout) getLayout();
                layout.setColumns(columns);
                revalidate();
            }
            super.doLayout();
        }

        @Override
        public Dimension getPreferredSize() {
            int count = getComponentCount();
            if (count == 0) return new Dimension();
            int width = 0;
            int height = 0;
            for (Component child : getComponents()) {
                Dimension preferred = child.getPreferredSize();
                width = Math.max(width, preferred.width);
                height = Math.max(height, preferred.height);
            }
            int usedColumns = Math.max(1, Math.min(columns, count));
            int rows = (count + usedColumns - 1) / usedColumns;
            return new Dimension(width * usedColumns + gap * (usedColumns - 1),
                    height * rows + gap * (rows - 1));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
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
