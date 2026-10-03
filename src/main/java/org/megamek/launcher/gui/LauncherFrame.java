/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package org.megamek.launcher.gui;

import org.megamek.launcher.gui.AutomaticInstallationChecks.InstallationCheck;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.channel.SelectedChannelReleaseCatalog;
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
import org.megamek.launcher.update.ImportedCopyAdoptionService;
import org.megamek.launcher.update.PreparedAdoption;
import org.megamek.launcher.update.PreparedUpdate;
import org.megamek.launcher.update.UpdatePreviewService;
import org.megamek.launcher.update.RealUpdateService;
import org.megamek.launcher.update.UninstallService;
import org.megamek.launcher.update.WindowsMsiUpdate;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import javax.swing.Scrollable;
import javax.swing.KeyStroke;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LauncherFrame extends JFrame {
    private static final int LOG_LIMIT = 64_000;
    private static final DateTimeFormatter NEWS_DATE =
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);
    static final String DISCORD_INVITE_URL = "https://discord.gg/megamek";
    private final LauncherServices services;
    private final LauncherNewsFeed newsFeed;
    private final ExistingImportPrompts existingImportPrompts;
    private final NormalInstallLocationPrompts normalInstallLocationPrompts;
    private final BusyGate gate = new BusyGate();
    private final Map<Component, Boolean> enabledBeforeWork = new IdentityHashMap<>();
    private final JPanel content = new JPanel(new BorderLayout(12, 12));
    private final JLabel status = new JLabel("Loading installations…");
    private final Set<String> checkedOnOpen =
            java.util.Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Boolean> checkPreferenceSaves = new HashMap<>();
    private LauncherServices.HomeState state;
    private SwingWorker<QuickInstallSnapshot, Void> firstLaunchOptionsWorker;
    private FirstLaunchMetadataState firstLaunchMetadataState =
            FirstLaunchMetadataState.NOT_STARTED;
    private QuickInstallSnapshot firstLaunchSnapshot;
    private String firstLaunchMetadataFailure;
    private long firstLaunchMetadataAttempt;
    private SwingWorker<Map<String, InstallationCheck>, Void> automaticChecksWorker;
    private SwingWorker<InstallationCheck, Void> installationCheckWorker;
    private final Map<String, InstallationCheck> installationChecks = new HashMap<>();
    private final Map<String, JButton> installationMenuButtons = new HashMap<>();
    private final AtomicReference<OperationContext> activeOperation = new AtomicReference<>();
    private final GuiScale guiScale = GuiScale.DEFAULT;
    private java.awt.image.BufferedImage firstLaunchArtwork;
    private Throwable firstLaunchArtworkError;
    private FirstLaunchSplitButton firstLaunchSplitButton;
    private FirstLaunchPanel firstLaunchPanel;
    private final List<HomeLaunchSplitButton> homeLaunchButtons =
            new java.util.ArrayList<>();
    private JButton homeInstallationsButton;
    private JPanel homeInformationRow;
    private JLabel homeInformationLabel;
    private boolean homeSizeInitialized;
    private long homeGeneration;
    private Page page = Page.HOME;
    private String selectedInstallationId;
    private LauncherServices.SettingsView launcherSettings;
    private Throwable launcherSettingsError;
    private boolean settingsLoading;
    private boolean newsRequested;
    private boolean newsLoading;
    private List<LauncherNewsFeed.Article> newsArticles = List.of();
    private Throwable newsError;
    private String transientHomeMessage;
    private boolean launcherUpdateChecked;
    private final WindowsMsiUpdate launcherUpdater = new WindowsMsiUpdate();

    public LauncherFrame(LauncherServices services) {
        this(services, new SwingExistingImportPrompts(),
                new SwingNormalInstallLocationPrompts(), new LauncherNewsFeed());
    }

    LauncherFrame(LauncherServices services, ExistingImportPrompts existingImportPrompts) {
        this(services, existingImportPrompts, new SwingNormalInstallLocationPrompts(),
                new LauncherNewsFeed());
    }

    LauncherFrame(LauncherServices services,
                  NormalInstallLocationPrompts normalInstallLocationPrompts) {
        this(services, new SwingExistingImportPrompts(), normalInstallLocationPrompts,
                new LauncherNewsFeed());
    }

    LauncherFrame(LauncherServices services, LauncherNewsFeed newsFeed) {
        this(services, new SwingExistingImportPrompts(),
                new SwingNormalInstallLocationPrompts(), newsFeed);
    }

    private LauncherFrame(LauncherServices services,
                          ExistingImportPrompts existingImportPrompts,
                          NormalInstallLocationPrompts normalInstallLocationPrompts,
                          LauncherNewsFeed newsFeed) {
        super(titleFor(LauncherFrame.class.getPackage().getImplementationVersion()));
        this.services = services;
        this.newsFeed = newsFeed;
        this.existingImportPrompts = existingImportPrompts;
        this.normalInstallLocationPrompts = normalInstallLocationPrompts;
        setName("launcherFrame");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(guiScale.scaleForGUI(680, 470));
        setPreferredSize(guiScale.scaleForGUI(1180, 820));
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
        setContentPane(content);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (gate.isBusy()) {
                    OperationContext operation = activeOperation.get();
                    if (operation != null) {
                        if (operation.type() == OperationType.RECOVERY
                                || operation.type() == OperationType.UNINSTALL_RECOVERY) {
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

    static String titleFor(String version) {
        return "MegaMek Launcher" + (version == null || version.isBlank()
                ? " (development build)" : " " + version);
    }

    private void prepareExactNormalInstall(OfficialRepository repository,
                                           FollowChannel futureChannel, String tag,
                                           String source, Path destination) {
        run("Checking exact " + displayProduct(repository.key()) + " " + tag,
                () -> services.prepareExactNormalInstall(repository, futureChannel, tag,
                        source, destination),
                this::showNormalInstallConfirmation);
    }

    public void showWindow() {
        if (!isVisible()) {
            setVisible(true);
            fitWindowToScreen(getSize());
        }
        reload();
    }

    private void reload() {
        homeGeneration++;
        disposeFirstLaunchSplitButton();
        disposeHomeLaunchButtons();
        cancelCheckWorkers();
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
                maybeCheckManagedCopiesOnOpen();
                if (!launcherUpdateChecked && WindowsMsiUpdate.available()) {
                    launcherUpdateChecked = true;
                    checkLauncherUpdate(false);
                }
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
        status.setText("");

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setName("managedHomeDeck");
        center.setBackground(FirstLaunchPanel.PANEL);
        center.setBorder(BorderFactory.createEmptyBorder());
        homeInformationLabel = new JLabel();
        homeInformationLabel.setName("homeInformationMessage");
        homeInformationLabel.setOpaque(false);
        homeInformationLabel.setForeground(new Color(232, 211, 146));
        homeInformationLabel.setFont(guiScale.font(
                homeInformationLabel.getFont(), Font.PLAIN, 12f));
        homeInformationLabel.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        homeInformationRow = new JPanel(new BorderLayout());
        homeInformationRow.setName("homeInformationRow");
        homeInformationRow.setOpaque(false);
        homeInformationRow.setBorder(BorderFactory.createEmptyBorder(0,
                guiScale.scaleForGUI(8), guiScale.scaleForGUI(12),
                guiScale.scaleForGUI(8)));
        homeInformationRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        homeInformationRow.add(homeInformationLabel, BorderLayout.WEST);
        homeInformationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                homeInformationRow.getPreferredSize().height));
        center.add(homeInformationRow);
        updateHomeInformationMessage();
        renderApplicationLaunches(center);

        center.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));

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
        if (next != Page.HOME) {
            disposeFirstLaunchSplitButton();
            disposeHomeLaunchButtons();
        }
        page = next;
        if (next == Page.SETTINGS) {
            launcherSettings = null;
            launcherSettingsError = null;
            loadSettingsPage();
            loadNews();
            return;
        }
        renderCurrentPage();
    }

    private void loadNews() {
        if (newsRequested) return;
        newsRequested = true;
        newsLoading = true;
        renderSettingsPage();
        new SwingWorker<List<LauncherNewsFeed.Article>, Void>() {
            @Override protected List<LauncherNewsFeed.Article> doInBackground()
                    throws IOException, InterruptedException {
                return newsFeed.latest();
            }

            @Override protected void done() {
                newsLoading = false;
                try {
                    newsArticles = get();
                } catch (java.util.concurrent.ExecutionException error) {
                    newsError = error.getCause();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    newsError = error;
                }
                if (newsError != null) {
                    recordErrorAsync("Loading MegaMek news", newsError, ignored -> {});
                }
                if (isDisplayable() && page == Page.SETTINGS) renderSettingsPage();
            }
        }.execute();
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
                    prepareCapturedNormalInstall(QuickInstallSnapshot.DEFAULT_KEY, null);
                },
                key -> {
                    if (!isCurrentFirstLaunch(generation, capturedHome, source[0])) return;
                    prepareCapturedNormalInstall(key, null);
                },
                () -> {
                    if (!isCurrentFirstLaunch(generation, capturedHome, source[0])) return;
                    retryFirstLaunchOptions();
                });
        source[0] = download;
        firstLaunchSplitButton = download;
        JButton useExisting = new FirstLaunchButton("Use existing installation",
                "useExistingCopyButton", false, guiScale);
        useExisting.setMnemonic(java.awt.event.KeyEvent.VK_U);
        useExisting.getAccessibleContext().setAccessibleDescription(
                "Choose and statically inspect an existing MegaMek, MekHQ, or MegaMekLab "
                        + "installation without moving its files.");
        useExisting.addActionListener(event -> chooseExisting());
        status.setText(transientHomeMessage != null && !transientHomeMessage.isBlank()
                ? transientHomeMessage
                : firstLaunchArtworkError == null
                ? ""
                : "Artwork unavailable; diagnostics are available in Settings.");
        FirstLaunchPanel firstLaunch = new FirstLaunchPanel(firstLaunchArtwork, guiScale,
                download, useExisting, status);
        firstLaunchPanel = firstLaunch;
        content.add(firstLaunch, BorderLayout.CENTER);
        applyFirstLaunchMetadata(true);
        if (!homeSizeInitialized) {
            homeSizeInitialized = true;
            Dimension preferred = firstLaunch.getPreferredSize();
            java.awt.Insets frameInsets = getInsets();
            fitWindowToScreen(new Dimension(
                    Math.max(getWidth(), preferred.width + frameInsets.left + frameInsets.right),
                    Math.max(getHeight(), preferred.height + frameInsets.top + frameInsets.bottom)));
        }
    }

    boolean firstLaunchMetadataPending() {
        return firstLaunchOptionsWorker != null;
    }

    private void startFirstLaunchOptionsCheck() {
        if (firstLaunchOptionsWorker != null
                || firstLaunchMetadataState == FirstLaunchMetadataState.READY) {
            return;
        }
        firstLaunchMetadataState = FirstLaunchMetadataState.LOADING;
        firstLaunchMetadataFailure = null;
        final long attempt = ++firstLaunchMetadataAttempt;
        applyFirstLaunchMetadata(false);
        firstLaunchOptionsWorker = new SwingWorker<>() {
            @Override
            protected QuickInstallSnapshot doInBackground() throws Exception {
                return services.quickInstallSnapshot();
            }

            @Override
            protected void done() {
                if (firstLaunchOptionsWorker != this) return;
                firstLaunchOptionsWorker = null;
                if (isCancelled() || !isDisplayable()
                        || attempt != firstLaunchMetadataAttempt) {
                    return;
                }
                try {
                    QuickInstallSnapshot loaded = get();
                    if (loaded == null) {
                        firstLaunchOptionsFailed(
                                "Install versions unavailable: no catalog returned. Choose Retry version check.");
                    } else {
                        firstLaunchSnapshot = loaded;
                        firstLaunchMetadataFailure = null;
                        firstLaunchMetadataState = FirstLaunchMetadataState.READY;
                    }
                } catch (java.util.concurrent.CancellationException ignored) {
                    return;
                } catch (java.util.concurrent.ExecutionException error) {
                    firstLaunchOptionsFailed(
                            "Install versions unavailable: "
                                    + SanitizedErrors.display(error.getCause())
                                    + ". Choose Retry version check.");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    firstLaunchOptionsFailed(
                            "Version check interrupted. Choose Retry version check.");
                }
                applyFirstLaunchMetadata(false);
            }
        };
        try {
            firstLaunchOptionsWorker.execute();
        } catch (RuntimeException error) {
            firstLaunchOptionsWorker = null;
            firstLaunchOptionsFailed(
                    "Version check could not start: " + SanitizedErrors.display(error)
                            + ". Choose Retry version check.");
            applyFirstLaunchMetadata(false);
        }
    }

    private void firstLaunchOptionsFailed(String message) {
        firstLaunchMetadataFailure = message;
        firstLaunchMetadataState = firstLaunchSnapshot == null
                ? FirstLaunchMetadataState.FAILED : FirstLaunchMetadataState.READY;
    }

    private void retryFirstLaunchOptions() {
        if (!isCurrentEmptyFirstLaunch()
                || firstLaunchMetadataState != FirstLaunchMetadataState.FAILED
                && !(firstLaunchMetadataState == FirstLaunchMetadataState.READY
                && firstLaunchSnapshot.options().stream().anyMatch(option -> !option.available()))
                || firstLaunchOptionsWorker != null) {
            return;
        }
        firstLaunchMetadataState = FirstLaunchMetadataState.NOT_STARTED;
        startFirstLaunchOptionsCheck();
    }

    private void applyFirstLaunchMetadata(boolean startInitial) {
        if (!isCurrentEmptyFirstLaunch()) return;
        status.setToolTipText(null);
        switch (firstLaunchMetadataState) {
            case NOT_STARTED -> {
                firstLaunchSplitButton.setOptionsLoading();
                status.setText("");
                if (startInitial) startFirstLaunchOptionsCheck();
            }
            case LOADING -> {
                firstLaunchSplitButton.setOptionsLoading();
                status.setText("");
            }
            case READY -> {
                if (firstLaunchSnapshot == null) {
                    throw new IllegalStateException(
                            "ready first-launch metadata has no snapshot");
                }
                firstLaunchSplitButton.setOptions(firstLaunchSnapshot);
                setStableFirstLaunchStatus();
            }
            case FAILED -> {
                firstLaunchSplitButton.setOptionsUnavailable(firstLaunchMetadataFailure);
                status.setText("Install versions unavailable — choose Retry.");
            }
        }
    }

    private boolean isCurrentEmptyFirstLaunch() {
        return isDisplayable() && page == Page.HOME && state != null
                && state.registry().installations().isEmpty()
                && firstLaunchPanel != null && firstLaunchSplitButton != null
                && firstLaunchPanel.getParent() == content;
    }

    private void setStableFirstLaunchStatus() {
        if (isCurrentEmptyFirstLaunch() && firstLaunchMetadataFailure != null
                && firstLaunchSnapshot != null) {
            status.setText("Version refresh failed — using previous versions. Choose Retry.");
            status.setToolTipText(firstLaunchMetadataFailure);
            return;
        }
        status.setToolTipText(null);
        status.setText(isCurrentEmptyFirstLaunch() && firstLaunchArtworkError != null
                ? "Artwork unavailable; diagnostics are available in Settings."
                : "");
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
        homeInformationRow = null;
        homeInformationLabel = null;
    }

    private void updateHomeInstallationsLabel() {
        if (homeInstallationsButton == null) return;
        long updates = installationChecks.values().stream()
                .filter(check -> check.result() != null && check.result().updateAvailable())
                .count();
        String text = updates == 0 ? "Installations"
                : "Installations (" + updates + (updates == 1 ? " update)" : " updates)");
        homeInstallationsButton.setText(text);
        homeInstallationsButton.getAccessibleContext().setAccessibleName(text);
        homeInstallationsButton.getAccessibleContext().setAccessibleDescription(
                updates == 0 ? "Open installation management."
                        : "Open installation management for " + updates
                        + (updates == 1 ? " available update." : " available updates."));
        updateHomeInformationMessage();
    }

    private void updateHomeInformationMessage() {
        if (homeInformationLabel == null) return;
        String text = homeInformationText();
        homeInformationLabel.setText(text);
        homeInformationRow.setVisible(!text.isBlank());
        homeInformationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                homeInformationRow.getPreferredSize().height));
        homeInformationLabel.getAccessibleContext().setAccessibleName(
                text.isBlank() ? "No launcher message" : text);
        homeInformationRow.revalidate();
        homeInformationRow.repaint();
    }

    private String homeInformationText() {
        if (transientHomeMessage != null && !transientHomeMessage.isBlank()) {
            return transientHomeMessage;
        }
        if (automaticChecksWorker != null
                || installationCheckWorker != null
                || installationChecks.values().stream()
                .anyMatch(check -> "Checking".equals(check.error()))) {
            return "Checking for updates…";
        }
        if (state == null || state.registry() == null
                || state.installationStatuses() == null) {
            return "";
        }
        List<InstallationRecord> checkedRecords = state.registry().installations().stream()
                .filter(record -> managedUpdatesAvailable(installationLocalStatus(record)))
                .filter(record -> installationChecks.containsKey(record.id()))
                .toList();
        if (checkedRecords.isEmpty()) return "";
        long updates = checkedRecords.stream().filter(record -> {
            InstallationCheck check = installationChecks.get(record.id());
            return check != null && check.result() != null
                    && check.result().updateAvailable();
        }).count();
        if (updates > 0) {
            return updates + (updates == 1
                    ? " installation has an update" : " installations have updates");
        }
        boolean failed = checkedRecords.stream().map(record -> installationChecks.get(record.id()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(check -> check.error() != null || check.result() == null
                        || check.result().status() == ChannelUpdateChecker.Status.UNAVAILABLE
                        || check.result().status() == ChannelUpdateChecker.Status.UNCONFIGURED
                        || check.result().status() == ChannelUpdateChecker.Status.NON_COMPARABLE);
        if (failed) return "Some installations could not be checked";
        boolean allChecked = checkedRecords.stream().allMatch(record -> {
            InstallationCheck check = installationChecks.get(record.id());
            return check != null && check.error() == null && check.result() != null;
        });
        boolean allInstallationsManaged =
                checkedRecords.size() == state.registry().installations().size();
        if (!allChecked) return "";
        return allInstallationsManaged
                ? "All installations are up to date"
                : "Checked installations are up to date";
    }

    private void resumeFirstLaunchOptions() {
        applyFirstLaunchMetadata(false);
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
        for (String key : List.of("mekhq", "megamek", "lab")) {
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
        boolean pending = false;
        boolean pendingUninstall = false;
        boolean unavailable = state.preferredError() != null
                && targets.containsValue(state.preferred());
        for (String productKey : List.of("mekhq", "megamek", "lab")) {
            InstallationRecord record = targets.get(productKey);
            if (record == null) continue;
            String channel = homeChannelLabel(record);
            HomeLaunchSplitButton launch = new HomeLaunchSplitButton(guiScale, productKey,
                    displayProduct(productKey), record.observedBuild(), channel,
                    () -> launchDirectly(record, productKey),
                    homeLaunchAlternatives(record, productKey));
            LauncherServices.InstallationStatus local = installationLocalStatus(record);
            pending |= local != null && local.pendingUpdate();
            pendingUninstall |= local != null && local.pendingUninstall();
            unavailable |= local != null && local.error() != null;
            launch.setEnabled(local == null
                    || !local.pendingUpdate() && !local.pendingUninstall()
                    && local.error() == null);
            homeLaunchButtons.add(launch);
            launches.add(launch);
        }
        panel.add(launches);
        if (pending || pendingUninstall || unavailable) {
            panel.add(Box.createVerticalStrut(guiScale.scaleForGUI(7)));
            String message = pendingUninstall
                    ? "Uninstall recovery required."
                    : pending
                    ? "An installation needs update recovery before it can be played."
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
                        homeAlternativeLabel(record, productKey),
                        () -> launchDirectly(record, productKey)))
                .toList();
    }

    private String homeAlternativeLabel(InstallationRecord record, String productKey) {
        String name = installationDisplayName(record);
        String version = knownInstallationVersion(record);
        String channel = homeChannelLabel(record);
        InstallationCheck check = installationChecks.get(record.id());
        LauncherServices.InstallationStatus local = installationLocalStatus(record);
        String update = !managedUpdatesAvailable(local) || check == null
                ? null : channelStatus(check.result(), check.error());
        String product = displayProduct(productKey);
        String details = VersionDisplay.programChannelVersion(product, channel,
                version == null ? "Unknown version" : version);
        String label = name.equalsIgnoreCase(product)
                ? name + (version == null ? "" : " · " + version)
                        + (channel == null ? "" : " · " + channel)
                : name + " · " + details;
        return label + (update == null ? "" : " · " + update);
    }

    private static String knownInstallationVersion(InstallationRecord record) {
        String version = record.observedBuild();
        return version == null || version.isBlank() || version.equalsIgnoreCase("unknown")
                ? null : version;
    }

    private String installationDisplayName(InstallationRecord record) {
        String name = record.name();
        String version = knownInstallationVersion(record);
        if (version != null && name.endsWith(" (" + version + ")")) {
            name = name.substring(0, name.length() - version.length() - 3);
        }
        String channel = homeChannelLabel(record);
        if (channel != null) {
            for (Product product : record.products()) {
                String prefix = displayProduct(product.key()) + " " + channel + " (";
                if (name.startsWith(prefix) && hasVersionSuffix(name, prefix)) {
                    name = displayProduct(product.key());
                    break;
                }
            }
        }
        for (Product product : record.products()) {
            String prefix = displayProduct(product.key()) + " (";
            if (name.startsWith(prefix) && hasVersionSuffix(name, prefix)) {
                name = displayProduct(product.key());
                break;
            }
        }
        String candidate = name;
        if (channel != null && record.products().stream().anyMatch(product ->
                candidate.equalsIgnoreCase(displayProduct(product.key()) + " " + channel))) {
            name = name.substring(0, name.length() - channel.length() - 1);
        }
        return name;
    }

    private static boolean hasVersionSuffix(String name, String prefix) {
        return name.endsWith(")") && name.substring(prefix.length(), name.length() - 1)
                .matches("[vV]?\\d+(?:\\.\\d+)*(?:[-+][A-Za-z0-9.-]+)?");
    }

    private String homeChannelLabel(InstallationRecord record) {
        LauncherServices.InstallationStatus local = installationLocalStatus(record);
        if (hasFixedChannel(local)) {
            return local.channelPreference().preference().channel().toString();
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

    private void cancelCheckWorkers() {
        if (automaticChecksWorker != null) {
            automaticChecksWorker.cancel(true);
            automaticChecksWorker = null;
        }
        if (installationCheckWorker != null) {
            installationCheckWorker.cancel(true);
            installationCheckWorker = null;
        }
    }

    private void cancelFirstLaunchOptionsWorker() {
        firstLaunchMetadataAttempt++;
        if (firstLaunchOptionsWorker != null) {
            firstLaunchOptionsWorker.cancel(true);
            firstLaunchOptionsWorker = null;
        }
        firstLaunchSnapshot = null;
        firstLaunchMetadataFailure = null;
        firstLaunchMetadataState = FirstLaunchMetadataState.NOT_STARTED;
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
                else if (page == Page.HOME) updateHomeInstallationsLabel();
            }
        };
        installationCheckWorker.execute();
    }

    private void maybeCheckManagedCopiesOnOpen() {
        if (state == null || state.registry() == null || automaticChecksWorker != null) return;
        final long generation = homeGeneration;
        final List<InstallationRecord> selectedRecords = AutomaticInstallationChecks.select(
                state.registry().installations(), this::installationLocalStatus, checkedOnOpen);
        if (selectedRecords.isEmpty()) return;
        automaticChecksWorker = new AutomaticInstallationChecks(services, selectedRecords, checkedOnOpen) {
            @Override
            protected void done() {
                if (automaticChecksWorker != this) return;
                automaticChecksWorker = null;
                if (!AutomaticInstallationChecks.canApply(
                        isCancelled(), generation, homeGeneration, isDisplayable())) return;
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
        automaticChecksWorker.execute();
        updateHomeInformationMessage();
    }

    private void recommendedUpdate(InstallationRecord record,
                                   UpdatePreviewService.Eligibility eligibility,
                                   ChannelUpdateChecker.Result checked) {
        if (checked == null || !checked.updateAvailable() || record == null
                || eligibility == null || !eligibility.available()) return;
        ChannelUpdateChecker.Recommendation recommendation = checked.recommendation();
        boolean update = RecommendedUpdateConsentDialog.confirm(
                this, displayProduct(recommendation.repository().key()),
                checked.currentTag(), recommendation.targetTag(),
                recommendation.assetSize(), guiScale);
        if (!update) return;
        runPreparedUpdate(record, eligibility.receipt(),
                eligibility.current(), recommendation.targetTag(),
                recommendation.assetName(),
                recommendation.assetSize(), recommendation.assetDigest(), checked);
    }

    private void chooseExisting() {
        Path preferredParent = null;
        try {
            preferredParent = services.normalInstallDestination(
                    OfficialRepository.MEKHQ, FollowChannel.MILESTONE).getParent();
        } catch (IOException ignored) {
            // The chooser will use its platform fallback if no suggestion can be resolved.
        }
        Path selected = existingImportPrompts.chooseFolder(this, preferredParent);
        if (selected == null) return;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Adding existing installation", "existingImportProgressLog",
                this::showOperationLogs);
        progress.append("Inspecting and adding the selected installation...\n");
        progress.setVisible(true);
        java.util.concurrent.atomic.AtomicBoolean inspectionComplete =
                new java.util.concurrent.atomic.AtomicBoolean();
        runOperation("Adding existing installation", OperationType.IMPORT_EXISTING,
                List.of(selected), progress, context -> {
                    ExistingImportService.Plan plan =
                            services.prepareExistingImport(selected, context);
                    inspectionComplete.set(true);
                    return services.importExisting(plan, existingInstallationName(plan), context);
                },
                result -> {
                    progress.dispose();
                    selectedInstallationId = result.record().id();
                    page = Page.HOME;
                    reload();
                }, error -> {
                    progress.append("\nIMPORT DID NOT COMPLETE: " + errorDetail(error) + "\n");
                    if (inspectionComplete.get()) {
                        progress.setTitle("Could not add installation");
                    } else {
                        progress.setTitle("This folder can't be used");
                        progress.setExpectedFailureSummary(
                                "Choose the main MegaMek, MekHQ, or MegaMekLab installation "
                                        + "folder, then try again.");
                    }
                }, () -> {});
    }

    static String existingInstallationName(ExistingImportService.Plan plan) {
        Set<String> products = plan.inspection().products().stream()
                .map(Product::key).collect(java.util.stream.Collectors.toSet());
        String product = products.contains("mekhq") ? "MekHQ"
                : products.contains("megamek") ? "MegaMek"
                : products.contains("lab") ? "MegaMekLab" : "MegaMek";
        String version = plan.inspection().observedBuild();
        if (version == null || version.isBlank()
                || "unknown".equalsIgnoreCase(version)) {
            return product + " existing installation";
        }
        return VersionDisplay.programChannelVersion(product, null, version);
    }

    private void renderInstallationsPage() {
        installationMenuButtons.clear();
        content.removeAll();
        content.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(18),
                guiScale.scaleForGUI(22), guiScale.scaleForGUI(14),
                guiScale.scaleForGUI(22)));
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(FirstLaunchPanel.BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0,
                guiScale.scaleForGUI(12), 0));
        JLabel title = new JLabel("Installations", javax.swing.SwingConstants.CENTER);
        title.setName("pageTitle");
        title.setForeground(FirstLaunchPanel.TEXT);
        title.setFont(guiScale.font(title.getFont(), Font.BOLD, 24f));
        header.add(title, BorderLayout.NORTH);
        JPanel topActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                guiScale.scaleForGUI(8), 0));
        topActions.setName("installationTopActions");
        topActions.setOpaque(false);
        JButton install = homeButton("Install another version",
                "installAnotherVersionButton");
        install.addActionListener(event -> downloadDialog());
        JButton importExisting = homeButton("Import existing installation",
                "manageAddExistingButton");
        importExisting.addActionListener(event -> chooseExisting());
        topActions.add(install);
        topActions.add(importExisting);
        header.add(topActions, BorderLayout.SOUTH);
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
                guiScale.scaleForGUI(8))) {
            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        card.setName("installationCard-" + record.id());
        card.setBackground(FirstLaunchPanel.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(84, 116, 108)),
                BorderFactory.createEmptyBorder(guiScale.scaleForGUI(12),
                        guiScale.scaleForGUI(14), guiScale.scaleForGUI(12),
                        guiScale.scaleForGUI(14))));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        LauncherServices.InstallationStatus local = installationLocalStatus(record);
        String version = knownInstallationVersion(record);
        String channel = hasFixedChannel(local) ? installationChannel(local) : null;
        String displayName = installationDisplayName(record);
        card.getAccessibleContext().setAccessibleName(displayName
                + (version == null ? "" : " " + version)
                + (channel == null ? "" : " " + channel));

        JPanel summary = new JPanel();
        summary.setOpaque(false);
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        JPanel titleRow = new JPanel();
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(displayName);
        name.setName("installationName-" + record.id());
        name.setForeground(FirstLaunchPanel.TEXT);
        name.setFont(guiScale.font(name.getFont(), Font.BOLD, 18f));
        titleRow.add(name);
        if (version != null || channel != null) {
            titleRow.add(Box.createHorizontalStrut(guiScale.scaleForGUI(10)));
            JLabel details = new JLabel((version == null ? "" : version)
                    + (version != null && channel != null ? " · " : "")
                    + (channel == null ? "" : channel));
            details.setName("installationDetails-" + record.id());
            details.setForeground(FirstLaunchPanel.MUTED);
            details.setFont(guiScale.font(details.getFont(), Font.PLAIN, 14f));
            titleRow.add(details);
        }
        summary.add(titleRow);
        summary.add(Box.createVerticalStrut(guiScale.scaleForGUI(5)));
        boolean ownershipProvenance = hasOwnershipProvenance(local);
        boolean fixedChannel = hasFixedChannel(local);
        boolean updateManaged = managedUpdatesAvailable(local);
        boolean trueImported = trueImported(local);
        String products = record.products().stream()
                .map(Product::key).map(LauncherFrame::displayProduct)
                .collect(java.util.stream.Collectors.joining(", "));
        String provenance = trueImported
                ? "Imported copy · Launch only · Updates unavailable"
                : fixedChannel
                ? products
                : "Managed setup incomplete · Launch only · Updates unavailable";
        JLabel metadata = new JLabel(provenance);
        metadata.setName("installationProvenance-" + record.id());
        metadata.setForeground(FirstLaunchPanel.MUTED);
        metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
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
            preferred.setAlignmentX(Component.LEFT_ALIGNMENT);
            summary.add(preferred);
        }
        String updateText = installationUpdateStatus(record, local);
        if (!provenance.toLowerCase(java.util.Locale.ROOT)
                .contains(updateText.toLowerCase(java.util.Locale.ROOT))) {
            JLabel updateStatus = new JLabel(updateText);
            updateStatus.setName("installationStatus-" + record.id());
            updateStatus.setForeground(local != null
                    && (local.pendingUpdate() || local.pendingUninstall())
                    ? FirstLaunchPanel.GOLD : FirstLaunchPanel.MUTED);
            updateStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
            summary.add(updateStatus);
        }
        if (updateManaged) {
            ChannelPreference expectedPreference = local.channelPreference().preference();
            Boolean pendingSelection = checkPreferenceSaves.get(record.id());
            JCheckBox checkOnOpen = new JCheckBox(
                    "Check for updates when the launcher opens",
                    pendingSelection == null
                            ? expectedPreference.checkOnOpen() : pendingSelection);
            checkOnOpen.setName("checkOnOpenCheckbox-" + record.id());
            checkOnOpen.setOpaque(false);
            checkOnOpen.setForeground(FirstLaunchPanel.TEXT);
            checkOnOpen.setMnemonic(KeyEvent.VK_C);
            checkOnOpen.setAlignmentX(Component.LEFT_ALIGNMENT);
            checkOnOpen.setBorder(BorderFactory.createEmptyBorder());
            checkOnOpen.setMargin(new java.awt.Insets(0, 0, 0, 0));
            checkOnOpen.getAccessibleContext().setAccessibleName(
                    "Check for updates when the launcher opens for " + record.name());
            checkOnOpen.getAccessibleContext().setAccessibleDescription(
                    "Saves immediately for this installation only. It does not change the "
                            + "fixed channel or affect an update check already in progress.");
            checkOnOpen.setEnabled(pendingSelection == null);
            checkOnOpen.addActionListener(event -> saveCheckOnOpen(
                    record, expectedPreference, checkOnOpen));
            summary.add(Box.createVerticalStrut(guiScale.scaleForGUI(4)));
            summary.add(checkOnOpen);
        }
        card.add(summary, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                guiScale.scaleForGUI(7), 0));
        actions.setOpaque(false);
        InstallationCheck checked = installationChecks.get(record.id());
        boolean updateAvailable = checked != null && checked.result() != null
                && checked.result().updateAvailable();
        if (ownershipProvenance && local.pendingUpdate()) {
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
        if (trueImported) {
            JButton adopt = homeButton("Enable Updates",
                    "enableManagedUpdatesButton-" + record.id());
            adopt.setMnemonic(KeyEvent.VK_E);
            adopt.getAccessibleContext().setAccessibleDescription(
                    "Verify this imported copy against one exact official release, without "
                            + "changing application files.");
            adopt.addActionListener(event -> beginAdoption(record));
            actions.add(adopt);
        }
        JButton more = homeButton("More…", "installationMenuButton-" + record.id());
        installationMenuButtons.put(record.id(), more);
        more.getAccessibleContext().setAccessibleDescription(
                "More actions for " + record.name());
        more.addActionListener(event -> run("Checking installation folder", () -> {
            services.requireInstallationPresent(record);
            return null;
        }, ignored -> showInstallationMenu(record), ignored -> {},
                () -> {}, true, false));
        actions.add(more);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private void showInstallationMenu(InstallationRecord record) {
        JButton currentButton = installationMenuButtons.get(record.id());
        if (page != Page.INSTALLATIONS || currentButton == null || !currentButton.isShowing()) return;
        if (state.registry().installations().stream().noneMatch(record::equals)) {
            showError("Installation changed", new IOException("Reopen Installations and try again."));
            return;
        }
        LauncherServices.InstallationStatus local = installationLocalStatus(record);
        installationMenu(record, local, managedUpdatesAvailable(local), hasOwnershipProvenance(local))
                .show(currentButton, 0, currentButton.getHeight());
    }

    private void saveCheckOnOpen(InstallationRecord record,
                                 ChannelPreference expectedPreference,
                                 JCheckBox checkbox) {
        boolean requested = checkbox.isSelected();
        if (checkPreferenceSaves.containsKey(record.id())) return;
        checkPreferenceSaves.put(record.id(), requested);
        checkbox.setEnabled(false);
        new SwingWorker<CheckOnOpenSave, Void>() {
            @Override
            protected CheckOnOpenSave doInBackground() {
                try {
                    ChannelPreference saved = services.setCheckOnOpen(
                            record, expectedPreference, requested);
                    return new CheckOnOpenSave(new ChannelPreferenceStore.ReadResult(
                            ChannelPreferenceStore.Status.CONFIGURED, saved, "Configured"),
                            null);
                } catch (IOException | RuntimeException failure) {
                    try {
                        return new CheckOnOpenSave(
                                services.channelPreference(record), failure);
                    } catch (IOException | RuntimeException reloadFailure) {
                        failure.addSuppressed(reloadFailure);
                        return new CheckOnOpenSave(null, failure);
                    }
                }
            }

            @Override
            protected void done() {
                checkPreferenceSaves.remove(record.id());
                if (!isDisplayable()) return;
                CheckOnOpenSave outcome;
                try {
                    outcome = get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    checkbox.setSelected(expectedPreference.checkOnOpen());
                    checkbox.setEnabled(true);
                    showError("Update check setting was not saved", interrupted);
                    return;
                } catch (java.util.concurrent.ExecutionException failure) {
                    checkbox.setSelected(expectedPreference.checkOnOpen());
                    checkbox.setEnabled(true);
                    showError("Update check setting was not saved", failure.getCause());
                    return;
                }
                if (outcome.authoritative() != null) {
                    if (outcome.failure() == null
                            && outcome.authoritative().status()
                            == ChannelPreferenceStore.Status.CONFIGURED
                            && outcome.authoritative().preference() != null) {
                        ChannelPreference saved = outcome.authoritative().preference();
                        checkedOnOpen.add(checkOnOpenKey(saved));
                    }
                    replaceChannelPreference(record, outcome.authoritative());
                    if (page == Page.INSTALLATIONS) {
                        renderInstallationsPage();
                    } else {
                        boolean configured = outcome.authoritative().status()
                                == ChannelPreferenceStore.Status.CONFIGURED
                                && outcome.authoritative().preference() != null;
                        checkbox.setSelected(configured
                                ? outcome.authoritative().preference().checkOnOpen()
                                : expectedPreference.checkOnOpen());
                        checkbox.setEnabled(configured);
                    }
                } else {
                    checkbox.setSelected(expectedPreference.checkOnOpen());
                    checkbox.setEnabled(true);
                }
                if (outcome.failure() != null) {
                    showError("Update check setting was not saved", outcome.failure());
                }
            }
        }.execute();
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

    private void replaceChannelPreference(
            InstallationRecord record,
            ChannelPreferenceStore.ReadResult preference) {
        if (state == null || state.registry() == null) return;
        Map<String, LauncherServices.InstallationStatus> statuses =
                new java.util.LinkedHashMap<>(state.installationStatuses() == null
                        ? Map.of() : state.installationStatuses());
        LauncherServices.InstallationStatus current = installationLocalStatus(record);
        if (current != null) {
            statuses.put(record.id(), new LauncherServices.InstallationStatus(
                    preference, current.previewEligibility(), current.pendingUpdate(),
                    current.pendingUninstall(), current.error(), current.adoption(),
                    current.adopted(), current.missing()));
        }
        ChannelPreferenceStore.ReadResult preferredPreference =
                record.equals(state.preferred()) ? preference : state.channelPreference();
        state = new LauncherServices.HomeState(state.registry(), state.preferred(),
                state.currentInspection(), state.preferredError(),
                state.previewEligibility(), state.pendingUpdate(), preferredPreference,
                state.preferredApplications(), Map.copyOf(statuses));
    }

    private static String checkOnOpenKey(ChannelPreference preference) {
        return AutomaticInstallationChecks.key(preference);
    }

    private JPopupMenu installationMenu(InstallationRecord record,
                                        LauncherServices.InstallationStatus local,
                                        boolean updateManaged,
                                        boolean ownershipProvenance) {
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
        menu.add(installationMenuSeparator());
        if (local != null && local.pendingUninstall()) {
            JMenuItem recover = installationMenuItem("Recover uninstall");
            recover.addActionListener(event -> recoverUninstall(record));
            menu.add(recover);
            return menu;
        }
        if (ownershipProvenance && local.pendingUpdate()) {
            JMenuItem recover = installationMenuItem("Recover interrupted update");
            recover.addActionListener(event -> recoverUpdate(record));
            menu.add(recover);
        }
        JMenuItem location = installationMenuItem("Open location");
        location.getAccessibleContext().setAccessibleDescription(
                "Open " + record.name() + " in the platform file manager");
        location.addActionListener(event -> openLocation(record));
        menu.add(location);
        JMenuItem rename = installationMenuItem("Rename…");
        rename.setName("renameInstallation-" + record.id());
        rename.addActionListener(event -> renameInstallation(record));
        menu.add(rename);
        JMenuItem resetPreferences = installationMenuItem("Reset preferences…");
        resetPreferences.setName("resetPreferences-" + record.id());
        resetPreferences.addActionListener(event -> resetPreferences(record));
        menu.add(resetPreferences);
        JMenuItem remove = installationMenuItem("Remove from launcher…");
        remove.addActionListener(event -> removeFromLauncher(record));
        menu.add(remove);
        if (updateManaged) {
            menu.add(installationMenuSeparator());
            if (local != null && local.error() == null && !local.pendingUpdate()
                    && local.adoption() == ImportedCopyAdoptionService.Availability.MANAGED
                    && !local.adopted()) {
                JMenuItem repair = installationMenuItem("Repair installation…");
                repair.setName("repairInstallation-" + record.id());
                repair.addActionListener(event -> repairInstallation(record));
                menu.add(repair);
            }
            JMenuItem uninstall = installationMenuItem("Uninstall…");
            uninstall.addActionListener(event -> uninstall(record));
            menu.add(uninstall);
        }
        return menu;
    }

    private JMenuItem installationMenuItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setUI(new javax.swing.plaf.basic.BasicMenuItemUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                checkIcon = null;
                arrowIcon = null;
                defaultTextIconGap = 0;
                selectionBackground = HomeLaunchSplitButton.POPUP_SELECTION;
                selectionForeground = HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND;
                disabledForeground = FirstLaunchPanel.MUTED;
            }
        });
        item.setOpaque(true);
        item.setBackground(HomeLaunchSplitButton.POPUP_BACKGROUND);
        item.setForeground(HomeLaunchSplitButton.POPUP_FOREGROUND);
        item.setIconTextGap(0);
        // BasicMenuItemUI.installDefaults() also pulls the active look and
        // feel's "MenuItem.margin" onto every item, independent of the
        // checkIcon/arrowIcon/iconTextGap already zeroed out above. Most
        // look and feels size that margin to leave room for an icon column,
        // which is exactly the leftover left-hand space these icon-less
        // items don't need. Zero it so only the explicit border below
        // controls spacing.
        item.setMargin(new java.awt.Insets(0, 0, 0, 0));
        item.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(9),
                guiScale.scaleForGUI(12), guiScale.scaleForGUI(9),
                guiScale.scaleForGUI(12)));
        item.getAccessibleContext().setAccessibleName(text);
        return item;
    }

    private JPanel installationMenuSeparator() {
        int height = guiScale.scaleForGUI(11);
        int inset = guiScale.scaleForGUI(12);
        JPanel separator = new JPanel() {
            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                super.paintComponent(graphics);
                graphics.setColor(new Color(84, 116, 108));
                int y = getHeight() / 2;
                graphics.drawLine(inset, y, Math.max(inset, getWidth() - inset), y);
            }
        };
        separator.setName("installationMenuSeparator");
        separator.setOpaque(true);
        separator.setBackground(HomeLaunchSplitButton.POPUP_BACKGROUND);
        separator.setMinimumSize(new Dimension(1, height));
        separator.setPreferredSize(new Dimension(1, height));
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return separator;
    }

    private static String installationChannel(LauncherServices.InstallationStatus local) {
        return local.channelPreference().preference().channel().toString();
    }

    private static boolean hasOwnershipProvenance(
            LauncherServices.InstallationStatus local) {
        return local != null && local.previewEligibility() != null
                && (local.previewEligibility().available()
                || local.previewEligibility().receipt() != null);
    }

    private static boolean managedUpdatesAvailable(
            LauncherServices.InstallationStatus local) {
        return AutomaticInstallationChecks.available(local);
    }

    private static boolean hasFixedChannel(LauncherServices.InstallationStatus local) {
        return local != null && local.channelPreference() != null
                && local.channelPreference().status()
                == ChannelPreferenceStore.Status.CONFIGURED
                && local.channelPreference().preference() != null;
    }

    private static boolean trueImported(LauncherServices.InstallationStatus local) {
        return local != null
                && local.adoption() == ImportedCopyAdoptionService.Availability.TRUE_IMPORTED
                && local.error() == null
                && !local.pendingUpdate();
    }

    private String installationUpdateStatus(InstallationRecord record,
                                            LauncherServices.InstallationStatus local) {
        if (local != null && local.missing()) {
            return "Installation folder no longer exists";
        }
        if (local != null && local.pendingUninstall()) {
            return "Uninstall recovery required";
        }
        if (local != null && local.error() != null) {
            return "Installation needs attention";
        }
        if (!hasOwnershipProvenance(local)) return "Launch only · updates unavailable";
        if (!managedUpdatesAvailable(local)) {
            return "Fixed channel provenance needs repair";
        }
        if (local.pendingUpdate()) return "Recovery required";
        InstallationCheck checked = installationChecks.get(record.id());
        if (checked == null) {
            return local != null && local.previewEligibility() != null
                    && !local.previewEligibility().available()
                    ? "Launch only · updates unavailable" : "Update status not checked";
        }
        if ("Checking".equals(checked.error())) return "Checking…";
        return channelStatus(checked.result(), checked.error());
    }

    private void beginAdoption(InstallationRecord record) {
        final long generation = homeGeneration;
        run("Checking imported copy",
                () -> services.adoptionSuggestion(record),
                suggestion -> {
                    if (isCurrentAdoption(generation, record)) {
                        showAdoptionStart(record, suggestion, generation);
                    }
                },
                error -> {
                    if (!isCurrentAdoption(generation, record)) return;
                    showIneligibleAdoption(new PreparedAdoption.Report(false,
                            record.name(), record.observedBuild(), FollowChannel.MILESTONE,
                            ImportedCopyAdoptionService.AUTOMATIC_MATCH_UNAVAILABLE));
                    recordErrorAsync("Mapping imported copy to an official product",
                            error, ignored -> {});
                }, () -> {}, false);
    }

    private void showAdoptionStart(
            InstallationRecord record,
            ImportedCopyAdoptionService.Suggestion suggestion,
            long generation) {
        if (!isCurrentAdoption(generation, record)) return;
        JPanel details = new JPanel();
        details.setName("adoptionCandidateContent");
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBackground(FirstLaunchPanel.BACKGROUND);
        details.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(14),
                guiScale.scaleForGUI(18), guiScale.scaleForGUI(8),
                guiScale.scaleForGUI(20)));
        int detailGap = guiScale.scaleForGUI(8);

        JLabel heading = new JLabel("Enable managed updates");
        heading.setName("adoptionCandidateHeading");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(guiScale.font(heading.getFont(), Font.BOLD, 22f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(heading);
        details.add(Box.createVerticalStrut(detailGap));

        JLabel application = new JLabel(suggestion.detectedApplication() + " ("
                + suggestion.detectedVersion() + ")");
        application.setName("adoptionDetectedApplicationVersion");
        application.setForeground(FirstLaunchPanel.TEXT);
        application.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(application);
        details.add(Box.createVerticalStrut(detailGap));

        JPanel channelRow = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 0, 0));
        channelRow.setOpaque(false);
        JLabel channelLabel = new JLabel("Update channel:");
        channelLabel.setForeground(FirstLaunchPanel.TEXT);
        StyledComboBox<FollowChannel> channel = new StyledComboBox<>(
                FollowChannel.values(),
                guiScale);
        channel.setName("adoptionChannelCombo");
        channel.setPreferredSize(new Dimension(
                guiScale.scaleForGUI(180), channel.getPreferredSize().height));
        channel.setSelectedItem(FollowChannel.MILESTONE);
        channel.getAccessibleContext().setAccessibleName("Future update channel");
        channel.getAccessibleContext().setAccessibleDescription(
                "Choose Milestone, Development, or Weekly once. This choice is fixed after enabling.");
        channelLabel.setLabelFor(channel);
        channelRow.add(channelLabel);
        channelRow.add(Box.createHorizontalStrut(detailGap));
        channelRow.add(channel);
        channelRow.setMaximumSize(new Dimension(
                Integer.MAX_VALUE, channelRow.getPreferredSize().height));
        channelRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(channelRow);
        details.add(Box.createVerticalStrut(detailGap));

        JLabel explanation = new JLabel(
                "Existing application and personal files will not be changed.");
        explanation.setName("adoptionExplanation");
        explanation.setForeground(FirstLaunchPanel.MUTED);
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(explanation);

        JDialog dialog = new JDialog(this, "Enable managed updates", false);
        dialog.setName("adoptionCandidateDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel actions = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, guiScale.scaleForGUI(8), 0));
        actions.setBackground(FirstLaunchPanel.BACKGROUND);
        actions.setBorder(BorderFactory.createEmptyBorder(
                0, 0, guiScale.scaleForGUI(12), 0));
        JButton cancel = homeButton("Cancel", "cancelAdoptionButton");
        JButton continueButton = homeButton("Continue", "continueAdoptionButton");
        continueButton.setFont(guiScale.font(
                continueButton.getFont(), Font.BOLD, 14f));
        continueButton.setMnemonic(KeyEvent.VK_C);
        continueButton.getAccessibleContext().setAccessibleDescription(
                "Find the matching official version and verify this copy without changing it.");
        cancel.setMnemonic(KeyEvent.VK_A);
        cancel.getAccessibleContext().setAccessibleDescription(
                "Close without contacting the release service.");
        actions.add(cancel);
        actions.add(continueButton);
        JPanel body = new JPanel(new BorderLayout(0, guiScale.scaleForGUI(12)));
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.add(details, BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        Dimension requested = guiScale.scaleForGUI(560, 235);
        dialog.setSize(GuiScale.fitWindow(requested,
                GuiScale.usableBounds(getGraphicsConfiguration())));
        dialog.setLocationRelativeTo(this);

        java.util.concurrent.atomic.AtomicBoolean continuing =
                new java.util.concurrent.atomic.AtomicBoolean();
        cancel.addActionListener(event -> dialog.dispose());
        continueButton.addActionListener(event -> {
            if (!continuing.compareAndSet(false, true)) return;
            if (!isCurrentAdoption(generation, record)) {
                dialog.dispose();
                return;
            }
            FollowChannel selected = (FollowChannel) channel.getSelectedItem();
            if (selected == null) {
                continuing.set(false);
                return;
            }
            continueButton.setEnabled(false);
            cancel.setEnabled(false);
            dialog.dispose();
            prepareAdoption(record, suggestion, null, selected, generation);
        });
        dialog.getRootPane().setDefaultButton(continueButton);
        installEscapeAction(dialog, "cancelAdoption", dialog::dispose);
        dialog.setVisible(true);
    }

    private void openAdoptionReleaseBrowser(
            InstallationRecord record,
            ImportedCopyAdoptionService.Suggestion suggestion,
            FollowChannel fixedChannel,
            long generation) {
        if (!isCurrentAdoption(generation, record)) return;
        DefaultListModel<AdoptionReleaseChoice> model = new DefaultListModel<>();
        JList<AdoptionReleaseChoice> releases = new JList<>(model);
        releases.setName("adoptionReleaseList");
        releases.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        releases.setBackground(FirstLaunchPanel.PANEL);
        releases.setForeground(FirstLaunchPanel.TEXT);
        releases.setSelectionBackground(HomeLaunchSplitButton.POPUP_SELECTION);
        releases.setSelectionForeground(HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND);
        releases.getAccessibleContext().setAccessibleName("Official releases");

        JLabel status = new JLabel("Select Fetch versions to view official releases.");
        status.setName("adoptionReleaseStatus");
        status.setForeground(FirstLaunchPanel.MUTED);
        JButton fetch = homeButton("Fetch versions", "fetchAdoptionReleasesButton");
        JButton next = homeButton("Next page", "nextAdoptionReleasePageButton");
        JButton cancel = homeButton("Cancel", "cancelAdoptionReleaseButton");
        JButton continueButton = homeButton("Continue",
                "continueSelectedAdoptionButton");
        next.setEnabled(false);
        continueButton.setEnabled(false);
        final int[] pageNumber = {0};
        final boolean[] more = {false};

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Choose the matching official version");
        title.setForeground(FirstLaunchPanel.GOLD);
        title.setFont(guiScale.font(title.getFont(), Font.BOLD, 21f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel explanation = new JLabel("<html>Use this list when the detected version could "
                + "not be resolved automatically. The selected package must still match the "
                + "copy exactly.</html>");
        explanation.setForeground(FirstLaunchPanel.MUTED);
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(title);
        heading.add(Box.createVerticalStrut(guiScale.scaleForGUI(6)));
        heading.add(explanation);
        heading.add(Box.createVerticalStrut(guiScale.scaleForGUI(8)));
        heading.add(fetch);

        JPanel actions = new JPanel(new BorderLayout(guiScale.scaleForGUI(8), 0));
        actions.setOpaque(false);
        actions.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, guiScale.scaleForGUI(8), 0));
        buttons.setOpaque(false);
        buttons.add(next);
        buttons.add(cancel);
        buttons.add(continueButton);
        actions.add(buttons, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(guiScale.scaleForGUI(10),
                guiScale.scaleForGUI(10)));
        body.setName("adoptionReleaseBrowserContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(16),
                guiScale.scaleForGUI(18), guiScale.scaleForGUI(14),
                guiScale.scaleForGUI(18)));
        body.add(heading, BorderLayout.NORTH);
        body.add(new JScrollPane(releases), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        JDialog dialog = new JDialog(this, "Choose official version", false);
        dialog.setName("adoptionReleaseBrowser");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(body);
        dialog.setSize(guiScale.scaleForGUI(720, 450));
        dialog.setLocationRelativeTo(this);

        Consumer<Integer> load = requested -> {
            fetch.setEnabled(false);
            next.setEnabled(false);
            continueButton.setEnabled(false);
            run("Fetching official versions",
                    () -> services.releases(suggestion.repository(), requested),
                    result -> {
                        if (!dialog.isDisplayable()
                                || !isCurrentAdoption(generation, record)) {
                            dialog.dispose();
                            return;
                        }
                        model.clear();
                        result.releases().forEach(release -> model.addElement(
                                new AdoptionReleaseChoice(release,
                                        services.assess(suggestion.repository(), release))));
                        pageNumber[0] = result.page();
                        more[0] = result.mayHaveNextPage();
                        fetch.setEnabled(true);
                        next.setEnabled(more[0]);
                        status.setText(result.releases().isEmpty()
                                ? "No releases were found on this page."
                                : "Select the version that matches this copy.");
                    }, error -> {
                        fetch.setEnabled(true);
                        next.setEnabled(pageNumber[0] > 0 && more[0]);
                        status.setText("Official versions could not be loaded. Try again.");
                        recordErrorAsync("Loading official versions for adoption",
                                error, ignored -> {});
                    }, () -> {}, false);
        };
        fetch.addActionListener(event -> load.accept(1));
        next.addActionListener(event -> load.accept(pageNumber[0] + 1));
        releases.addListSelectionListener(event -> {
            AdoptionReleaseChoice selected = releases.getSelectedValue();
            continueButton.setEnabled(selected != null
                    && selected.assessment().eligible());
            if (selected != null && !selected.assessment().eligible()) {
                status.setText("This release cannot be used for safe verification.");
            }
        });
        cancel.addActionListener(event -> dialog.dispose());
        continueButton.addActionListener(event -> {
            AdoptionReleaseChoice selected = releases.getSelectedValue();
            if (selected == null || !selected.assessment().eligible()
                    || !isCurrentAdoption(generation, record)) {
                if (!isCurrentAdoption(generation, record)) dialog.dispose();
                return;
            }
            continueButton.setEnabled(false);
            dialog.dispose();
            prepareAdoption(record, suggestion, selected.release().tag(),
                    fixedChannel, generation);
        });
        installEscapeAction(dialog, "cancelAdoptionRelease", dialog::dispose);
        dialog.setVisible(true);
    }

    private void prepareAdoption(
            InstallationRecord record,
            ImportedCopyAdoptionService.Suggestion suggestion,
            String exactTag, FollowChannel fixedChannel, long generation) {
        if (!isCurrentAdoption(generation, record)) return;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Verifying imported copy", "adoptionProgressLog", this::showOperationLogs);
        JButton browse = button("Choose a different version…",
                "browseAfterAdoptionFailureButton");
        browse.setEnabled(false);
        browse.addActionListener(event -> {
            progress.dispose();
            if (isCurrentAdoption(generation, record)) {
                openAdoptionReleaseBrowser(record, suggestion, fixedChannel, generation);
            }
        });
        progress.addActionButton(browse);
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicBoolean publicationAttempted =
                new java.util.concurrent.atomic.AtomicBoolean();
        runOperation("Verifying imported copy", OperationType.ADOPT_EXISTING,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> {
                    PreparedAdoption prepared = exactTag == null
                            ? services.prepareAutomaticAdoption(
                            record, suggestion.repository(), fixedChannel, stream, context)
                            : services.prepareAdoption(
                            record, suggestion.repository(), exactTag,
                            fixedChannel, stream, context);
                    boolean callerOwnsPrepared = true;
                    try {
                        PreparedAdoption.Report report = prepared.report();
                        if (!report.eligible()) {
                            context.cleanupPhase(
                                    "Discarding the completed verification workspace");
                            callerOwnsPrepared = false;
                            return new AdoptionExecution(
                                    prepared.discardIneligible(stream), false);
                        }
                        publicationAttempted.set(true);
                        // commitAdoption claims the one-use handle before revalidation and
                        // consumes it on every success, failure, and cancellation path.
                        callerOwnsPrepared = false;
                        services.commitAdoption(prepared, context, stream);
                        return new AdoptionExecution(report, true);
                    } finally {
                        if (callerOwnsPrepared) prepared.close();
                    }
                },
                result -> {
                    if (!isCurrentAdoption(generation, record)) {
                        progress.dispose();
                        return;
                    }
                    if (result.enabled()) {
                        progress.dispose();
                        page = Page.INSTALLATIONS;
                        reload();
                    } else {
                        progress.dispose();
                        showIneligibleAdoption(result.report());
                    }
                }, error -> {
                    if (error instanceof
                            ImportedCopyAdoptionService.ChannelMismatchException mismatch) {
                        progress.dispose();
                        if (isCurrentAdoption(generation, record)) {
                            showChannelMismatchAdoption(
                                    record, suggestion, mismatch, generation);
                        }
                        return;
                    }
                    progress.setTitle("This copy remains launch-only");
                    if (publicationAttempted.get()) {
                        progress.setFailureSummary(
                                "This copy could not be enabled and will remain launch-only.");
                    }
                    browse.setEnabled(isCurrentAdoption(generation, record)
                            && error instanceof
                            ImportedCopyAdoptionService.CandidateResolutionException);
                }, stream::close);
    }

    private boolean isCurrentAdoption(long generation, InstallationRecord record) {
        return isDisplayable() && generation == homeGeneration
                && page == Page.INSTALLATIONS && state != null
                && state.registry().installations().contains(record);
    }

    private static void installEscapeAction(JDialog dialog, String key, Runnable action) {
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), key);
        dialog.getRootPane().getActionMap().put(key, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    void showIneligibleAdoption(PreparedAdoption.Report report) {
        JPanel body = adoptionResultBody("Updates can't be enabled",
                report.message()
                        + "<br><br>No files were changed. You can keep using this installation, "
                        + "or install a separate managed copy for updates.");
        JPanel actions = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, guiScale.scaleForGUI(8), 0));
        actions.setOpaque(false);
        JButton close = homeButton("Close", "closeAdoptionResultButton");
        actions.add(close);
        body.add(actions, BorderLayout.SOUTH);
        JDialog dialog = adoptionResultDialog("Updates can't be enabled", body);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        close.addActionListener(event -> dialog.dispose());
        installEscapeAction(dialog, "closeAdoptionResult", dialog::dispose);
        dialog.getRootPane().setDefaultButton(close);
        dialog.setVisible(true);
    }

    private void showChannelMismatchAdoption(
            InstallationRecord record,
            ImportedCopyAdoptionService.Suggestion suggestion,
            ImportedCopyAdoptionService.ChannelMismatchException mismatch,
            long generation) {
        String headingText = "Use " + mismatch.requiredChannel()
                + " for this installation";
        JPanel body = adoptionResultBody(headingText,
                "Version " + mismatch.currentVersion() + " is currently a "
                        + mismatch.requiredChannel() + " release, not a "
                        + mismatch.selectedChannel() + " release."
                        + "<br><br>No files were changed. Select "
                        + mismatch.requiredChannel()
                        + " to verify this copy and enable managed updates.");
        JPanel actions = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, guiScale.scaleForGUI(8), 0));
        actions.setOpaque(false);
        JButton close = homeButton("Close", "closeAdoptionResultButton");
        JButton retry = homeButton("Try " + mismatch.requiredChannel(),
                "retrySuggestedAdoptionButton");
        retry.setFont(guiScale.font(retry.getFont(), Font.BOLD, 14f));
        actions.add(close);
        actions.add(retry);
        body.add(actions, BorderLayout.SOUTH);
        JDialog dialog = adoptionResultDialog(headingText, body);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        close.addActionListener(event -> dialog.dispose());
        retry.addActionListener(event -> {
            if (!isCurrentAdoption(generation, record)) {
                dialog.dispose();
                return;
            }
            retry.setEnabled(false);
            close.setEnabled(false);
            dialog.dispose();
            prepareAdoption(record, suggestion, null,
                    mismatch.requiredChannel(), generation);
        });
        installEscapeAction(dialog, "closeAdoptionChannelMismatch", dialog::dispose);
        dialog.getRootPane().setDefaultButton(retry);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) {
                retry.requestFocusInWindow();
                dialog.getRootPane().setDefaultButton(retry);
            }
        });
        dialog.setVisible(true);
    }

    private JPanel adoptionResultBody(String headingText, String result) {
        JPanel body = new JPanel(new BorderLayout(0, guiScale.scaleForGUI(14)));
        body.setName("adoptionResultContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(18),
                guiScale.scaleForGUI(20), guiScale.scaleForGUI(14),
                guiScale.scaleForGUI(20)));

        JPanel summary = new JPanel();
        summary.setOpaque(false);
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(headingText);
        heading.setName("adoptionResultHeading");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(guiScale.font(heading.getFont(), Font.BOLD, 22f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(heading);
        summary.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));

        JLabel message = new JLabel("<html>" + result + "</html>");
        message.setName("adoptionResultMessage");
        message.setForeground(FirstLaunchPanel.TEXT);
        message.setFont(guiScale.font(message.getFont(), Font.PLAIN, 14f));
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(message);
        body.add(summary, BorderLayout.CENTER);
        return body;
    }

    private JDialog adoptionResultDialog(String title, JPanel body) {
        JDialog dialog = new JDialog(this, title, false);
        dialog.setName("adoptionResultDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setContentPane(body);
        dialog.setSize(guiScale.scaleForGUI(640, 275));
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    private void renameInstallation(InstallationRecord record) {
        JDialog dialog = new JDialog(this, "Rename installation", false);
        dialog.setName("renameInstallationDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        JPanel body = new JPanel(new BorderLayout(guiScale.scaleForGUI(12),
                guiScale.scaleForGUI(12)));
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(20),
                guiScale.scaleForGUI(22), guiScale.scaleForGUI(18),
                guiScale.scaleForGUI(22)));
        JLabel heading = new JLabel("Name this installation");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(guiScale.font(heading.getFont(), Font.BOLD, 20f));
        body.add(heading, BorderLayout.NORTH);

        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setOpaque(false);
        JTextField name = new JTextField(installationDisplayName(record));
        name.setName("installationNameField");
        name.setForeground(FirstLaunchPanel.TEXT);
        name.setBackground(FirstLaunchPanel.PANEL);
        name.setCaretColor(FirstLaunchPanel.TEXT);
        name.setFont(guiScale.font(name.getFont(), Font.PLAIN, 15f));
        name.getAccessibleContext().setAccessibleName("Installation name");
        fields.add(name);
        fields.add(Box.createVerticalStrut(guiScale.scaleForGUI(7)));
        JLabel hint = new JLabel("Only the launcher label changes, not the installation folder.");
        hint.setForeground(FirstLaunchPanel.MUTED);
        fields.add(hint);
        body.add(fields, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                guiScale.scaleForGUI(8), 0));
        actions.setOpaque(false);
        JButton cancel = homeButton("Cancel", "cancelRenameButton");
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = homeButton("Save name", "saveInstallationNameButton");
        save.addActionListener(event -> {
            String requested = name.getText().strip();
            if (requested.isEmpty() || requested.length() > 120
                    || requested.chars().anyMatch(Character::isISOControl)) {
                hint.setText("Use a name of 1–120 printable characters.");
                hint.setForeground(FirstLaunchPanel.GOLD);
                name.requestFocusInWindow();
                return;
            }
            if (requested.equals(record.name())) {
                dialog.dispose();
                return;
            }
            dialog.dispose();
            run("Renaming installation", () -> services.renameInstallation(record, requested),
                    ignored -> reload(), ignored -> {}, () -> {}, true, false);
        });
        actions.add(cancel);
        actions.add(save);
        body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        dialog.getRootPane().setDefaultButton(save);
        dialog.setSize(guiScale.scaleForGUI(530, 190));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        name.selectAll();
        name.requestFocusInWindow();
    }

    private void openLocation(InstallationRecord record) {
        run("Opening installation folder", () -> {
            services.openLocation(record);
            return null;
        }, ignored -> status.setText("Opened installation folder."),
                error -> showError("Open location failed", error));
    }

    private void removeFromLauncher(InstallationRecord record) {
        boolean confirmed = LauncherAlertDialog.showConfirm(this, guiScale, "Remove from launcher",
                "Remove “" + record.name()
                        + "” from the launcher?\nIts files will stay in the installation folder.",
                "Remove");
        if (!confirmed) return;
        run("Removing from launcher", () -> services.removeFromLauncher(record), result -> {
            selectedInstallationId = null;
            transientHomeMessage = result.warning() == null
                    ? "Removed from launcher. Files were kept."
                    : "Removed from launcher. Files were kept. " + result.warning();
            page = Page.HOME;
            reload();
        });
    }

    private void removeMissingInstallation(InstallationRecord record) {
        boolean confirmed = LauncherAlertDialog.showConfirm(this, guiScale,
                "Installation not found",
                "The folder for \"" + record.name() + "\" no longer exists on disk.\n\n"
                        + "Remove this installation from the launcher?",
                "Remove from launcher");
        if (!confirmed) return;
        run("Removing missing installation",
                () -> services.removeMissingFromLauncher(record), result -> {
                    selectedInstallationId = null;
                    transientHomeMessage = "Removed missing installation from launcher."
                            + (result.warning() == null ? "" : " " + result.warning());
                    page = Page.HOME;
                    reload();
                });
    }

    private void resetPreferences(InstallationRecord record) {
        run("Preparing preferences reset", () -> services.planPreferencesReset(record), plan -> {
            boolean confirmed = LauncherAlertDialog.showConfirm(this, guiScale,
                    "Reset preferences",
                    "Close MegaMek, MekHQ, and MegaMekLab before continuing."
                            + "\n\nThis resets the preferences for the programs in this "
                            + "installation. Your saves, campaigns, and custom files will stay."
                            + "\n\nA backup of your current preferences will be kept in case "
                            + "you want to restore them. Its location will be shown after "
                            + "the reset.",
                    "Reset preferences");
            if (!confirmed) return;
            run("Resetting preferences", () -> services.resetPreferences(plan), result -> {
                String message = result.moved().isEmpty()
                        ? "No known preferences files were found; nothing was reset."
                        : "Your previous preferences were backed up here:\n" + result.backup();
                status.setText(message);
                LauncherAlertDialog.showMessage(this, guiScale, "Preferences reset", message);
            });
        });
    }

    private void uninstall(InstallationRecord record) {
        boolean confirmed = LauncherAlertDialog.showConfirm(this, guiScale, "Uninstall",
                "Uninstall " + record.name() + "?\nOfficial application files will be removed. "
                        + "Saves, settings, custom files, and modified files will be kept.",
                "Uninstall");
        if (!confirmed) return;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Uninstalling", "uninstallProgressLog", this::showOperationLogs);
        progress.setVisible(true);
        runOperation("Uninstalling " + record.name(), OperationType.UNINSTALL,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.uninstall(record, context), result -> {
                    progress.dispose();
                    selectedInstallationId = null;
                    transientHomeMessage = result.retainedFiles()
                            ? "Uninstalled. Your custom files were kept in the installation folder."
                            : "Uninstalled.";
                    if (result.warning() != null) {
                        transientHomeMessage += " " + result.warning();
                    }
                    page = Page.HOME;
                    reload();
                }, error -> progress.setTitle("Uninstall failed"), () -> {
                });
    }

    private void recoverUninstall(InstallationRecord record) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Recovering uninstall", "uninstallRecoveryProgressLog",
                this::showOperationLogs);
        progress.startNonCancellable(
                "Uninstall recovery must remain open until it finishes.");
        progress.setVisible(true);
        runOperation("Recovering uninstall", OperationType.UNINSTALL_RECOVERY,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.recoverUninstall(record, context),
                result -> {
                    progress.dispose();
                    transientHomeMessage = result.message()
                            + (result.warning() == null ? "" : " " + result.warning());
                    page = Page.HOME;
                    reload();
                }, error -> progress.setTitle("Uninstall recovery failed"), () -> {
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
        ResponsiveSettingsSections center = new ResponsiveSettingsSections(guiScale);
        center.setName("settingsSections");
        center.setBackground(FirstLaunchPanel.BACKGROUND);
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);
        left.setName("settingsLeftColumn");
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setOpaque(false);
        right.setName("settingsRightColumn");
        center.add(left);
        center.add(right);
        if (settingsLoading) {
            JLabel loading = new JLabel("Loading settings…");
            loading.setName("settingsLoadingMessage");
            loading.setForeground(FirstLaunchPanel.MUTED);
            left.add(loading);
        } else if (launcherSettings != null) {
            JPanel java = settingsSection("Game Java");
            JLabel runtime = new JLabel("Java " + launcherSettings.defaultJavaFeature());
            runtime.setName("defaultJavaStatus");
            runtime.setForeground(FirstLaunchPanel.TEXT);
            runtime.setAlignmentX(Component.LEFT_ALIGNMENT);
            java.add(runtime);
            java.add(Box.createVerticalStrut(guiScale.scaleForGUI(4)));
            JPanel javaRow = new JPanel(new BorderLayout(guiScale.scaleForGUI(10), 0));
            javaRow.setName("defaultJavaRow");
            javaRow.setOpaque(false);
            javaRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            String configuredJava = launcherSettings.defaultJava().toString();
            JTextField javaPath = new JTextField(configuredJava);
            javaPath.setName("defaultJavaPath");
            javaPath.setToolTipText(configuredJava);
            javaPath.setEditable(false);
            javaPath.setBorder(BorderFactory.createEmptyBorder());
            javaPath.setOpaque(false);
            javaPath.setFont(guiScale.font(javaPath.getFont(), Font.PLAIN, 12f));
            javaPath.setForeground(FirstLaunchPanel.MUTED);
            javaPath.setCaretPosition(0);
            javaPath.getAccessibleContext().setAccessibleName("Game Java path");
            javaRow.add(javaPath, BorderLayout.CENTER);
            JButton changeJava = homeButton("Change default Java",
                    "changeDefaultJavaButton");
            changeJava.setAlignmentX(Component.LEFT_ALIGNMENT);
            changeJava.addActionListener(event -> chooseDefaultJava());
            javaRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    javaPath.getPreferredSize().height));
            java.add(javaRow);
            java.add(Box.createVerticalStrut(guiScale.scaleForGUI(6)));
            java.add(changeJava);
            left.add(java);
            left.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));

            JPanel diagnostics = settingsSection("Diagnostics");
            JButton logs = homeButton("View logs", "viewOperationLogsButton");
            logs.setAlignmentX(Component.LEFT_ALIGNMENT);
            logs.addActionListener(event -> showOperationLogs());
            diagnostics.add(logs);
            left.add(diagnostics);
            if (WindowsMsiUpdate.available()) {
                left.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));
                JPanel updater = settingsSection("Launcher update");
                JButton check = homeButton("Check for launcher update", "checkLauncherUpdateButton");
                check.setAlignmentX(Component.LEFT_ALIGNMENT);
                check.addActionListener(event -> checkLauncherUpdate(true));
                updater.add(check);
                left.add(updater);
            }
        } else {
            JLabel unavailable = new JLabel("Settings could not be read and were not reset.");
            unavailable.setName("settingsErrorMessage");
            unavailable.setForeground(FirstLaunchPanel.GOLD);
            unavailable.setToolTipText(launcherSettingsError == null ? null
                    : errorDetail(launcherSettingsError));
            left.add(unavailable);
            JButton retry = homeButton("Retry settings", "retrySettingsButton");
            retry.addActionListener(event -> loadSettingsPage());
            left.add(retry);
        }
        JPanel community = settingsSection("Community");
        JButton discord = homeButton("Join Discord", "openDiscordButton");
        discord.setAlignmentX(Component.LEFT_ALIGNMENT);
        discord.getAccessibleContext().setAccessibleDescription(
                "Opens the MegaMek community Discord invitation in your browser");
        discord.addActionListener(event -> openWebsite(
                java.net.URI.create(DISCORD_INVITE_URL), "Discord"));
        community.add(discord);
        right.add(community);

        JPanel news = settingsSection("Latest news");
        if (newsLoading || !newsRequested) {
            JLabel loading = new JLabel("Loading MegaMek news...");
            loading.setName("newsLoadingMessage");
            loading.setForeground(FirstLaunchPanel.MUTED);
            news.add(loading);
        } else if (newsError != null) {
            JLabel unavailable = new JLabel("News is unavailable right now.");
            unavailable.setName("newsUnavailableMessage");
            unavailable.setForeground(FirstLaunchPanel.MUTED);
            unavailable.setToolTipText(errorDetail(newsError));
            news.add(unavailable);
        } else {
            for (int i = 0; i < newsArticles.size(); i++) {
                LauncherNewsFeed.Article article = newsArticles.get(i);
                String headline = NEWS_DATE.format(article.published())
                        + " - " + article.title();
                JButton link = homeButton(headline, "newsArticleButton" + i);
                link.setAlignmentX(Component.LEFT_ALIGNMENT);
                link.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
                link.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                        link.getPreferredSize().height));
                link.setToolTipText(headline);
                link.getAccessibleContext().setAccessibleName(headline);
                link.addActionListener(event -> openWebsite(article.link(), "MegaMek news"));
                news.add(link);
                news.add(Box.createVerticalStrut(guiScale.scaleForGUI(4)));
            }
        }
        JButton archive = homeButton("All news", "allNewsButton");
        archive.setAlignmentX(Component.LEFT_ALIGNMENT);
        archive.getAccessibleContext().setAccessibleDescription(
                "Open the MegaMek blog archive in your browser");
        archive.addActionListener(event -> openWebsite(LauncherNewsFeed.ARCHIVE, "news archive"));
        news.add(archive);
        right.add(Box.createVerticalStrut(guiScale.scaleForGUI(10)));
        right.add(news);
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

    private void openWebsite(java.net.URI address, String description) {
        run("Opening " + description, () -> {
            java.awt.Desktop desktop = java.awt.Desktop.isDesktopSupported()
                    ? java.awt.Desktop.getDesktop() : null;
            if (desktop == null || !desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                throw new IOException("Opening web links is not supported on this system");
            }
            desktop.browse(address);
            return null;
        }, ignored -> {});
    }

    private JPanel settingsSection(String titleText) {
        JPanel section = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        section.setName("settings" + titleText.replace(" ", "") + "Section");
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setOpaque(false);
        section.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(10),
                guiScale.scaleForGUI(14), guiScale.scaleForGUI(12),
                guiScale.scaleForGUI(14)));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel(titleText);
        title.setForeground(FirstLaunchPanel.GOLD);
        title.setFont(guiScale.font(title.getFont(), Font.BOLD, 17f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(title);
        section.add(Box.createVerticalStrut(guiScale.scaleForGUI(7)));
        return section;
    }

            /**
             * Keep the two independent section groups equal in width without letting a long
             * Java path or headline dictate the viewport width. At narrow sizes the groups
             * follow their natural reading/tab order (left, then right).
             */
            private static final class ResponsiveSettingsSections extends JPanel implements Scrollable {
                private final int gap;
                private final int breakpoint;

                private ResponsiveSettingsSections(GuiScale scale) {
                    gap = scale.scaleForGUI(12);
                    breakpoint = scale.scaleForGUI(800);
                    setLayout(null);
                }

                private boolean twoColumns(int width) {
                    return width >= breakpoint;
                }

                private int availableWidth() {
                    if (getParent() instanceof javax.swing.JViewport viewport && viewport.getWidth() > 0) {
                        return viewport.getWidth();
                    }
                    return getWidth() > 0 ? getWidth() : breakpoint;
                }

                private int columnHeight(Component column) {
                    return column.getPreferredSize().height;
                }

                @Override
                public void doLayout() {
                    if (getComponentCount() != 2) return;
                    int width = getWidth();
                    Component left = getComponent(0);
                    Component right = getComponent(1);
                    if (twoColumns(width)) {
                        int leftWidth = (width - gap) / 2;
                        int rightWidth = width - gap - leftWidth;
                        left.setBounds(0, 0, leftWidth, columnHeight(left));
                        right.setBounds(leftWidth + gap, 0, rightWidth, columnHeight(right));
                    } else {
                        left.setBounds(0, 0, width, columnHeight(left));
                        right.setBounds(0, left.getHeight() + gap, width, columnHeight(right));
                    }
                }

                @Override
                public Dimension getPreferredSize() {
                    if (getComponentCount() != 2) return new Dimension(availableWidth(), 0);
                    int width = availableWidth();
                    int leftHeight = columnHeight(getComponent(0));
                    int rightHeight = columnHeight(getComponent(1));
                    return new Dimension(width, twoColumns(width)
                            ? Math.max(leftHeight, rightHeight) : leftHeight + gap + rightHeight);
                }

                @Override
                public Dimension getPreferredScrollableViewportSize() {
                    return getPreferredSize();
                }

                @Override
                public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
                    return 24;
                }

                @Override
                public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
                    return Math.max(24, visibleRect.height - 24);
                }

                @Override
                public boolean getScrollableTracksViewportWidth() {
                    return true;
                }

                @Override
                public boolean getScrollableTracksViewportHeight() {
                    return false;
                }
            }

    private Path launcherUpdateReport() {
        return services.registry().toAbsolutePath().getParent().resolve("msi-update-result.txt");
    }

    private void checkLauncherUpdate(boolean explicit) {
        run("Checking launcher release", () -> services.checkLauncherUpdate(
                WindowsMsiUpdate.consumeReport(launcherUpdateReport(), WindowsMsiUpdate.currentVersion()),
                launcherUpdater::check), checked -> {
            if (checked.pending()) {
                status.setText(checked.message());
                if (explicit) LauncherAlertDialog.showMessage(this, guiScale,
                        "Launcher update in progress", checked.message());
                return;
            }
            WindowsMsiUpdate.Candidate candidate = checked.candidate();
            if (candidate == null) {
                status.setText(checked.message() == null ? "Launcher is up to date." : checked.message());
                if (checked.message() != null) LauncherAlertDialog.showMessage(this, guiScale,
                        "Launcher update result", checked.message());
                return;
            }
            if (!confirmLauncherUpdate(this, guiScale, candidate, checked.message())) return;
            OperationProgressDialog progress = new OperationProgressDialog(this,
                    "Updating launcher", "launcherUpdateProgressLog", this::showOperationLogs);
            progress.setVisible(true);
            runOperation("Updating launcher", OperationType.LAUNCHER_UPDATE, List.of(), progress, context -> {
                Path msi = launcherUpdater.stage(candidate, context);
                try {
                    context.phase(org.megamek.launcher.operation.OperationPhase.APPLY,
                            "Closing the launcher; Windows Installer will show installation progress. Diagnostics: "
                                    + WindowsMsiUpdate.helperLog(launcherUpdateReport()) + " and "
                                    + WindowsMsiUpdate.installerLog(launcherUpdateReport()));
                    context.enterFinalization("Windows Installer handoff has started.");
                    launcherUpdater.handoff(msi, candidate.version(), candidate.sha256(), launcherUpdateReport());
                    return null;
                } catch (Exception error) {
                    try {
                        launcherUpdater.discard(msi);
                    } catch (IOException cleanup) {
                        error.addSuppressed(cleanup);
                    }
                    throw error;
                }
            }, ignored -> {
                progress.dispose();
                dispose();
                System.exit(0);
            }, error -> progress.append("\nLauncher update failed: " + errorDetail(error) + "\n"), () -> {});
        }, error -> {
            if (!explicit) {
                status.setText("Launcher update: " + SanitizedErrors.text(error.getMessage()));
                recordErrorAsync("Automatic launcher update check", error, ignored -> {});
            }
        }, () -> {}, explicit, explicit);
    }

    static boolean confirmLauncherUpdate(Window owner, GuiScale scale,
                                          WindowsMsiUpdate.Candidate candidate, String notice) {
        String message = "Version " + candidate.version() + " is available.\n\n"
                + "Close any running games before updating.";
        if (notice != null) message = notice + "\n\n" + message;
        return LauncherAlertDialog.showConfirm(owner, scale, "Update MegaMek Launcher", message, "Update now");
    }

    private void chooseDefaultJava() {
        run("Finding Java runtimes", services::javaCandidates,
                this::chooseDefaultJavaFromCandidates);
    }

    private void chooseDefaultJavaFromCandidates(List<Path> candidates) {
        showJavaSelectionDialog("Change default Java", "defaultJavaCandidateCombo",
                candidates, selected -> run("Validating default game Java",
                        () -> {
                            services.selectDefaultJava(selected);
                            return null;
                        }, ignored -> {
                            launcherSettings = null;
                            launcherSettingsError = null;
                            loadSettingsPage();
                        }));
    }

    private void showJavaSelectionDialog(String title, String comboName, List<Path> candidates,
                                         Consumer<Path> selection) {
        StyledComboBox<Object> choices = new StyledComboBox<>(guiScale);
        candidates.forEach(choices::addItem);
        choices.addItem("Browse for Java home or executable…");
        choices.setName(comboName);
        choices.getAccessibleContext().setAccessibleName("Java runtime");
        choices.getAccessibleContext().setAccessibleDescription(
                "Choose a detected Java runtime or browse for a Java home or executable.");

        JDialog dialog = new JDialog(this, title, false);
        dialog.setName("javaSelectionDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(guiScale.scaleForGUI(10),
                guiScale.scaleForGUI(12)));
        body.setName("javaSelectionDialogContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(18),
                guiScale.scaleForGUI(20), guiScale.scaleForGUI(16),
                guiScale.scaleForGUI(20)));
        JLabel heading = new JLabel("Choose Java 21 or newer");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(guiScale.font(heading.getFont(), Font.BOLD, 20f));
        body.add(heading, BorderLayout.NORTH);
        body.add(choices, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                guiScale.scaleForGUI(8), 0));
        actions.setOpaque(false);
        JButton cancel = homeButton("Cancel", "cancelJavaSelectionButton");
        cancel.addActionListener(event -> dialog.dispose());
        JButton use = homeButton("Use selected Java", "confirmJavaSelectionButton");
        use.addActionListener(event -> {
            Object choice = choices.getSelectedItem();
            dialog.dispose();
            if (choice instanceof Path path) {
                selection.accept(path);
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose Java home or java executable");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                selection.accept(chooser.getSelectedFile().toPath());
            }
        });
        actions.add(cancel);
        actions.add(use);
        body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        dialog.getRootPane().setDefaultButton(use);
        dialog.setSize(guiScale.scaleForGUI(720, 190));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        choices.requestFocusInWindow();
    }

    void launchDirectly(InstallationRecord record, String productKey) {
        if (!gate.tryEnter()) {
            LauncherAlertDialog.showMessage(this, guiScale, "Please wait",
                    "Another operation is already running.");
            return;
        }
        InstallationRecord capturedRecord = record;
        String capturedProduct = productKey;
        int restoreState = getExtendedState() & ~JFrame.ICONIFIED;
        status.setText("Running " + displayProduct(capturedProduct) + "…");
        setActionsEnabled(false);
        // This EDT state transition is deliberately complete before execute(), so even an
        // immediate ProcessBuilder/start failure is handled by the restoring done() callback.
        try {
            setExtendedState(restoreState | JFrame.ICONIFIED);
        } catch (RuntimeException error) {
            gate.leave();
            setActionsEnabled(true);
            showError("Launching " + displayProduct(capturedProduct) + " failed", error);
            status.setText("Launch failed — details shown");
            return;
        }
        SwingWorker<Integer, Void> launchWorker = new SwingWorker<>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return services.launch(capturedRecord, capturedProduct);
            }

            @Override
            protected void done() {
                if (!gate.isBusy()) setActionsEnabled(true);
                Integer exit = null;
                Throwable problem = null;
                try {
                    exit = get();
                } catch (java.util.concurrent.ExecutionException error) {
                    problem = error.getCause();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    problem = error;
                }
                if (!isDisplayable()) return;
                if (problem == null && exit != null && exit == 0) {
                    reloadAfterSuccessfulGameExit(capturedProduct);
                    return;
                }
                setExtendedState(restoreState);
                setVisible(true);
                toFront();
                if (problem instanceof UninstallService.MissingInstallationException missing) {
                    status.setText("Installation folder not found.");
                    removeMissingInstallation(missing.record());
                    return;
                }
                if (problem != null) {
                    showError("Launching " + displayProduct(capturedProduct) + " failed",
                            problem);
                } else {
                    int code = exit == null ? -1 : exit;
                    showError("Game exited with code " + code,
                            new IOException("The selected program returned nonzero "
                                    + "exit code " + code));
                }
                status.setText("Launch failed — details shown");
            }
        };
        try {
            launchWorker.execute();
            // The root coordinator, not the GUI gate, protects the installation once the
            // worker begins. Restoring the window manually exposes launch controls again.
            gate.leave();
            setActionsEnabled(true);
        } catch (RuntimeException error) {
            gate.leave();
            setActionsEnabled(true);
            setExtendedState(restoreState);
            setVisible(true);
            toFront();
            showError("Launching " + displayProduct(capturedProduct) + " failed", error);
            status.setText("Launch failed — details shown");
        }
    }

    void reloadAfterSuccessfulGameExit(String productKey) {
        // An unrelated operation may have acquired the gate after launch started.
        // Its completion owns the UI; attempting reload() here would show a
        // misleading "Please wait" dialog and clear the current page.
        if (gate.isBusy()) return;
        status.setText(displayProduct(productKey) + " exited.");
        // Reload while retaining ICONIFIED. No success dialog or foreground request.
        reload();
    }

    private void prepareNormalInstall(OfficialRepository repository, FollowChannel channel,
                                      Path destination) {
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
                () -> services.prepareNormalInstall(repository, channel, target),
                this::showNormalInstallConfirmation,
                error -> normalPlanFailure(repository, channel, target, error), () -> {
                }, false);
    }

    private void prepareCapturedNormalInstall(QuickInstallOption.Key key, Path destination) {
        if (firstLaunchMetadataState != FirstLaunchMetadataState.READY
                || firstLaunchSnapshot == null) {
            return;
        }
        prepareCapturedNormalInstall(firstLaunchSnapshot.option(key), destination);
    }

    private void prepareCapturedNormalInstall(QuickInstallOption option, Path destination) {
        QuickInstallOption.Key key = option.key();
        AtomicReference<Path> attemptedDestination = new AtomicReference<>(destination);
        setStableFirstLaunchStatus();
        run("Preparing install",
                () -> {
                    Path target = destination == null
                            ? services.normalInstallDestination(
                            key.repository(), key.channel()) : destination;
                    attemptedDestination.set(target);
                    return services.prepareCapturedNormalInstall(
                            option, target);
                },
                this::showNormalInstallConfirmation,
                error -> capturedNormalPlanFailure(
                        option, attemptedDestination.get(), error),
                () -> {
                }, false, false);
    }

    private void capturedNormalPlanFailure(QuickInstallOption option, Path target,
                                           Throwable error) {
        QuickInstallOption.Key key = option.key();
        String detail = errorDetail(error);
        boolean javaProblem = detail.toLowerCase(java.util.Locale.ROOT).contains("java");
        String[] choices = javaProblem
                ? new String[]{"Cancel", "Retry", "Open Settings"}
                : new String[]{"Cancel", "Retry"};
        String answer = LauncherAlertDialog.show(this,
                javaProblem ? "Java setup required" : "Could not prepare download",
                (javaProblem
                        ? "A compatible external Java 21 or newer could not be verified."
                        : quickChoiceName(key.repository(), key.channel())
                        + " could not be prepared.")
                        + "\n\n" + detail
                        + "\n\nNo package was downloaded and no destination was created.",
                guiScale, choices);
        if (javaProblem && "Open Settings".equals(answer)) {
            navigateTo(Page.SETTINGS);
        } else if ("Retry".equals(answer)) {
            prepareCapturedNormalInstall(option, target);
        } else {
            resumeFirstLaunchOptions();
        }
    }

    private void normalPlanFailure(OfficialRepository repository, FollowChannel channel,
                                   Path target, Throwable error) {
        String detail = errorDetail(error);
        boolean javaProblem = detail.toLowerCase(java.util.Locale.ROOT).contains("java");
        String[] choices = javaProblem
                ? new String[]{"Cancel", "Retry", "Open Settings"}
                : new String[]{"Cancel", "Retry"};
        String answer = LauncherAlertDialog.show(this,
                javaProblem ? "Java setup required" : "Could not prepare download",
                (javaProblem
                        ? "A compatible external Java 21 or newer could not be verified."
                        : quickChoiceName(repository, channel) + " could not be checked.")
                        + "\n\n" + detail
                        + "\n\nNo package was downloaded and no destination was created.",
                guiScale, choices);
        if (javaProblem && "Open Settings".equals(answer)) {
            navigateTo(Page.SETTINGS);
        } else if ("Retry".equals(answer)) {
            prepareNormalInstall(repository, channel, target);
        } else {
            resumeFirstLaunchOptions();
        }
    }

    private void showNormalInstallConfirmation(NormalInstallService.Plan plan) {
        setStableFirstLaunchStatus();
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
                    if (plan.capturedCurrentTarget()) {
                        QuickInstallOption captured = capturedOption(plan);
                        if (captured == null) {
                            showError("Preparing changed install location failed",
                                    new IOException("The session install target is no longer "
                                            + "available; no new target was selected."));
                            resumeFirstLaunchOptions();
                        } else {
                            prepareCapturedNormalInstall(captured, changed);
                        }
                    } else if (plan.currentChannelTarget()) {
                        prepareNormalInstall(plan.repository(), plan.channel(), changed);
                    } else {
                        prepareExactNormalInstall(plan.repository(), plan.channel(),
                                plan.release().tag(), plan.source(), changed);
                    }
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

    private QuickInstallOption capturedOption(NormalInstallService.Plan plan) {
        if (firstLaunchMetadataState != FirstLaunchMetadataState.READY
                || firstLaunchSnapshot == null || !plan.capturedCurrentTarget()) {
            return null;
        }
        QuickInstallOption option = firstLaunchSnapshot.option(
                new QuickInstallOption.Key(plan.repository(), plan.channel()));
        return option.version().equals(plan.version())
                && option.release().equals(plan.release())
                && option.asset().equals(plan.asset())
                && option.target().source().equals(plan.source()) ? option : null;
    }

    private void runNormalInstall(NormalInstallService.Plan plan) {
        String product = displayProduct(plan.repository().key());
        String release = VersionDisplay.programChannelVersion(
                product, plan.channel().toString(), plan.version());
        String operationDescription = "Installing " + release;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                operationDescription, "normalInstallProgressLog", this::showOperationLogs);
        progress.append("Installing the confirmed official " + product + " "
                + plan.channel() + " selection; the channel is the created installation's "
                + "fixed update track...\n");
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
        new ReleasePickerDialog().showDialog();
    }

    private final class ReleasePickerDialog {
        private final StyledComboBox<OfficialRepository> product = new StyledComboBox<>(
                new OfficialRepository[]{OfficialRepository.MEKHQ,
                        OfficialRepository.MEGAMEK, OfficialRepository.LAB}, guiScale);
        private final StyledComboBox<FollowChannel> channel =
                new StyledComboBox<>(FollowChannel.values(), guiScale);
        private final DefaultListModel<PickerReleaseChoice> model = new DefaultListModel<>();
        private final JList<PickerReleaseChoice> releases = new JList<>(model);
        private final JButton fetch = homeButton(
                "Fetch releases", "fetchReleasesButton");
        private final JButton previous = homeButton(
                "Previous page", "previousReleasePageButton");
        private final JButton next = homeButton("Next page", "nextReleasePageButton");
        private final JButton install = homeButton(
                "Install", "installReleaseButton");
        private final JLabel pageIndicator = new JLabel("Page —");
        private final JLabel requestStatus = new JLabel(" ");
        private final JDialog dialog;
        private SwingWorker<?, Void> worker;
        private long generation;
        private OfficialRepository loadedRepository;
        private FollowChannel loadedChannel;
        private int displayedPage;
        private boolean displayedMayHaveNext;

        private ReleasePickerDialog() {
            product.setName("downloadProductCombo");
            product.setSelectedItem(OfficialRepository.MEKHQ);
            product.setDisplayText(value ->
                    value == null ? "" : displayProduct(value.key()));
            product.getAccessibleContext().setAccessibleName("Product");
            product.getAccessibleContext().setAccessibleDescription(
                    "Official product repository whose releases will be fetched.");
            channel.setName("downloadChannelCombo");
            channel.setSelectedItem(FollowChannel.MILESTONE);
            channel.getAccessibleContext().setAccessibleName("Channel");
            channel.getAccessibleContext().setAccessibleDescription(
                    "Update channel for the new installation.");

            releases.setName("releaseList");
            releases.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            releases.setBackground(FirstLaunchPanel.PANEL);
            releases.setForeground(FirstLaunchPanel.TEXT);
            releases.setSelectionBackground(HomeLaunchSplitButton.POPUP_SELECTION);
            releases.setSelectionForeground(
                    HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND);
            releases.setFixedCellHeight(guiScale.scaleForGUI(42));
            releases.setFont(guiScale.font(releases.getFont(), Font.PLAIN, 13f));
            releases.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(5),
                    guiScale.scaleForGUI(5), guiScale.scaleForGUI(5),
                    guiScale.scaleForGUI(5)));
            releases.getAccessibleContext().setAccessibleName("Available official releases");
            releases.getAccessibleContext().setAccessibleDescription(
                    "The selected current channel release and historical releases whose channel "
                            + "membership is unknown.");
            releases.setCellRenderer((list, value, index, selected, focused) -> {
                JLabel label = new JLabel(value == null ? "" : value.toString());
                label.setOpaque(true);
                label.setEnabled(value != null && value.assessment().eligible());
                label.setFont(guiScale.font(label.getFont(), Font.PLAIN, 13f));
                label.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(8),
                        guiScale.scaleForGUI(10), guiScale.scaleForGUI(8),
                        guiScale.scaleForGUI(10)));
                label.setBackground(selected
                        ? HomeLaunchSplitButton.POPUP_SELECTION : FirstLaunchPanel.PANEL);
                label.setForeground(selected
                        ? HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND
                        : value != null && !value.assessment().eligible()
                        ? FirstLaunchPanel.MUTED : FirstLaunchPanel.TEXT);
                if (value != null && !value.assessment().eligible()) {
                    label.setToolTipText("Unavailable: "
                            + SanitizedErrors.text(value.assessment().reason()));
                    label.getAccessibleContext().setAccessibleDescription(
                            "Unavailable. "
                                    + SanitizedErrors.text(value.assessment().reason()));
                }
                return label;
            });

            fetch.getAccessibleContext().setAccessibleDescription(
                    "Fetch page one for the selected product and channel.");
            previous.getAccessibleContext().setAccessibleDescription(
                    "Fetch the previous page for the unchanged product and channel.");
            next.getAccessibleContext().setAccessibleDescription(
                    "Fetch the next page for the unchanged product and channel.");
            install.getAccessibleContext().setAccessibleDescription(
                    "Install the selected release after choosing a destination and confirming.");
            previous.setEnabled(false);
            next.setEnabled(false);
            install.setEnabled(false);

            JPanel selectors = new JPanel(new java.awt.GridBagLayout());
            selectors.setOpaque(false);
            java.awt.GridBagConstraints selectorCell = new java.awt.GridBagConstraints();
            selectorCell.gridy = 0;
            selectorCell.insets = new java.awt.Insets(0, 0, 0, guiScale.scaleForGUI(7));
            selectorCell.anchor = java.awt.GridBagConstraints.LINE_START;
            JLabel productLabel = styledPickerLabel("Product:");
            productLabel.setLabelFor(product);
            selectorCell.gridx = 0;
            selectorCell.weightx = 0;
            selectorCell.fill = java.awt.GridBagConstraints.NONE;
            selectors.add(productLabel, selectorCell);
            selectorCell.gridx = 1;
            selectorCell.weightx = 0.5;
            selectorCell.fill = java.awt.GridBagConstraints.HORIZONTAL;
            selectors.add(product, selectorCell);
            JLabel channelLabel = styledPickerLabel("Channel:");
            channelLabel.setLabelFor(channel);
            selectorCell.gridx = 2;
            selectorCell.weightx = 0;
            selectorCell.fill = java.awt.GridBagConstraints.NONE;
            selectors.add(channelLabel, selectorCell);
            selectorCell.gridx = 3;
            selectorCell.weightx = 0.5;
            selectorCell.fill = java.awt.GridBagConstraints.HORIZONTAL;
            selectors.add(channel, selectorCell);
            selectorCell.gridx = 4;
            selectorCell.weightx = 0;
            selectorCell.fill = java.awt.GridBagConstraints.NONE;
            selectorCell.insets = new java.awt.Insets(0, 0, 0, 0);
            selectors.add(fetch, selectorCell);

            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            JLabel title = styledPickerLabel("Install another version");
            title.setName("installAnotherVersionHeading");
            title.setForeground(FirstLaunchPanel.GOLD);
            title.setFont(guiScale.font(title.getFont(), Font.BOLD, 22f));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            selectors.setAlignmentX(Component.LEFT_ALIGNMENT);
            heading.add(title);
            heading.add(Box.createVerticalStrut(guiScale.scaleForGUI(12)));
            heading.add(selectors);

            JScrollPane scroll = new JScrollPane(releases);
            scroll.setName("releaseListScrollPane");
            scroll.setBorder(BorderFactory.createLineBorder(
                    HomeLaunchSplitButton.POPUP_BORDER));
            scroll.getViewport().setBackground(FirstLaunchPanel.PANEL);

            JPanel resultActions = new JPanel(new java.awt.FlowLayout(
                    java.awt.FlowLayout.RIGHT, guiScale.scaleForGUI(8), 0));
            resultActions.setOpaque(false);
            resultActions.add(previous);
            pageIndicator.setName("releasePageIndicator");
            pageIndicator.setForeground(FirstLaunchPanel.TEXT);
            pageIndicator.setFont(guiScale.font(
                    pageIndicator.getFont(), Font.BOLD, 12f));
            pageIndicator.getAccessibleContext().setAccessibleName("Release page");
            resultActions.add(pageIndicator);
            resultActions.add(next);
            resultActions.add(install);
            requestStatus.setName("releasePickerStatus");
            requestStatus.setForeground(FirstLaunchPanel.MUTED);
            requestStatus.setFont(guiScale.font(
                    requestStatus.getFont(), Font.PLAIN, 12f));
            requestStatus.getAccessibleContext().setAccessibleName("Release picker status");
            requestStatus.setPreferredSize(new Dimension(1, guiScale.scaleForGUI(20)));
            JPanel bottom = new JPanel(new BorderLayout(guiScale.scaleForGUI(8),
                    guiScale.scaleForGUI(3)));
            bottom.setOpaque(false);
            bottom.add(requestStatus, BorderLayout.NORTH);
            bottom.add(resultActions, BorderLayout.CENTER);

            JPanel body = new JPanel(new BorderLayout(guiScale.scaleForGUI(12),
                    guiScale.scaleForGUI(12)));
            body.setName("releasePickerContent");
            body.setBackground(FirstLaunchPanel.BACKGROUND);
            body.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(18),
                    guiScale.scaleForGUI(20), guiScale.scaleForGUI(18),
                    guiScale.scaleForGUI(20)));
            body.add(heading, BorderLayout.NORTH);
            body.add(scroll, BorderLayout.CENTER);
            body.add(bottom, BorderLayout.SOUTH);

            dialog = new JDialog(LauncherFrame.this,
                    "Download an official release", false);
            dialog.setName("releasePickerDialog");
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setContentPane(body);
            Rectangle available = GuiScale.usableBounds(
                    LauncherFrame.this.getGraphicsConfiguration());
            Dimension minimum = guiScale.scaleForGUI(680, 420);
            Dimension requested = guiScale.scaleForGUI(940, 540);
            Dimension fitted = GuiScale.fitWindow(requested, available);
            dialog.setMinimumSize(new Dimension(
                    Math.min(minimum.width, fitted.width),
                    Math.min(minimum.height, fitted.height)));
            dialog.setSize(fitted);
            dialog.setLocationRelativeTo(LauncherFrame.this);
            Rectangle placed = dialog.getBounds();
            dialog.setLocation(
                    Math.max(available.x, Math.min(placed.x,
                            available.x + available.width - placed.width)),
                    Math.max(available.y, Math.min(placed.y,
                            available.y + available.height - placed.height)));
            dialog.getAccessibleContext().setAccessibleName("Install another version");
            dialog.getAccessibleContext().setAccessibleDescription(
                    "Fetch official releases for one selected product and fixed update channel.");
            dialog.getRootPane().setDefaultButton(fetch);
            dialog.getRootPane().getInputMap(
                    javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    "closeReleasePicker");
            dialog.getRootPane().getActionMap().put("closeReleasePicker",
                    new AbstractAction() {
                        @Override
                        public void actionPerformed(ActionEvent event) {
                            dialog.dispose();
                        }
                    });
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    generation++;
                    if (worker != null) worker.cancel(true);
                }
            });

            fetch.addActionListener(event -> fetchPage(1, true));
            previous.addActionListener(event -> {
                if (displayedPage > 1) {
                    fetchPage(displayedPage - 1, false);
                }
            });
            next.addActionListener(event -> {
                if (displayedPage > 0 && displayedMayHaveNext) {
                    fetchPage(displayedPage + 1, false);
                }
            });
            product.addActionListener(event -> invalidateResults());
            channel.addActionListener(event -> invalidateResults());
            releases.addListSelectionListener(event -> updateInstallEnabled());
            install.addActionListener(event -> chooseDestination());
        }

        private JLabel styledPickerLabel(String text) {
            JLabel label = new JLabel(text);
            label.setForeground(FirstLaunchPanel.TEXT);
            label.setFont(guiScale.font(label.getFont(), Font.BOLD, 13f));
            return label;
        }

        private void showDialog() {
            dialog.setVisible(true);
            product.requestFocusInWindow();
        }

        private void invalidateResults() {
            generation++;
            if (worker != null) worker.cancel(true);
            loadedRepository = null;
            loadedChannel = null;
            displayedPage = 0;
            displayedMayHaveNext = false;
            model.clear();
            previous.setEnabled(false);
            next.setEnabled(false);
            install.setEnabled(false);
            pageIndicator.setText("Page —");
            requestStatus.setText(" ");
        }

        private void fetchPage(int requestedPage, boolean reset) {
            OfficialRepository repository =
                    (OfficialRepository) product.getSelectedItem();
            FollowChannel selectedChannel = (FollowChannel) channel.getSelectedItem();
            if (repository == null || selectedChannel == null) return;
            if (!reset && (repository != loadedRepository
                    || selectedChannel != loadedChannel || displayedPage < 1)) {
                return;
            }
            if (reset) {
                generation++;
                loadedRepository = null;
                loadedChannel = null;
                displayedPage = 0;
                displayedMayHaveNext = false;
                model.clear();
                pageIndicator.setText("Page —");
                install.setEnabled(false);
            }
            previous.setEnabled(false);
            next.setEnabled(false);
            startTask("Fetching releases",
                    () -> services.installableReleases(
                            repository, selectedChannel, requestedPage), result -> {
                        if (product.getSelectedItem() != repository
                                || channel.getSelectedItem() != selectedChannel
                                || result.repository() != repository
                                || result.channel() != selectedChannel
                                || result.page() != requestedPage) {
                            return;
                        }
                        model.clear();
                        result.entries().forEach(entry -> model.addElement(
                                PickerReleaseChoice.from(entry, selectedChannel)));
                        loadedRepository = repository;
                        loadedChannel = selectedChannel;
                        displayedPage = result.page();
                        displayedMayHaveNext = result.mayHaveNextPage();
                        pageIndicator.setText("Page " + result.page());
                        requestStatus.setText(" ");
                        previous.setEnabled(result.page() > 1);
                        next.setEnabled(result.mayHaveNextPage());
                        updateInstallEnabled();
                    });
        }

        private void updateInstallEnabled() {
            PickerReleaseChoice choice = releases.getSelectedValue();
            OfficialRepository repository =
                    (OfficialRepository) product.getSelectedItem();
            FollowChannel selectedChannel = (FollowChannel) channel.getSelectedItem();
            boolean matching = choice != null && choice.assessment().eligible()
                    && repository != null && repository == loadedRepository
                    && selectedChannel != null && choice.repository() == repository
                    && selectedChannel == loadedChannel
                    && choice.channel() == selectedChannel;
            install.setEnabled(matching && worker == null);
        }

        private void chooseDestination() {
            PickerReleaseChoice choice = releases.getSelectedValue();
            OfficialRepository repository =
                    (OfficialRepository) product.getSelectedItem();
            FollowChannel selectedChannel = (FollowChannel) channel.getSelectedItem();
            if (choice == null || repository == null || selectedChannel == null
                    || !choice.assessment().eligible() || choice.repository() != repository
                    || repository != loadedRepository || selectedChannel != loadedChannel
                    || choice.channel() != selectedChannel) {
                return;
            }
            Path destination = chooseNewInstallDestination(dialog, repository,
                    selectedChannel, choice.release().tag());
            if (destination == null || !dialog.isDisplayable()) return;
            String tag = choice.release().tag();
            startTask("Revalidating exact install plan",
                    () -> services.prepareExactNormalInstall(
                            repository, selectedChannel, tag, choice.source(), destination),
                    plan -> {
                        if (!dialog.isDisplayable() || choice != releases.getSelectedValue()
                                || product.getSelectedItem() != repository
                                || channel.getSelectedItem() != selectedChannel
                                || loadedRepository != repository
                                || loadedChannel != selectedChannel) {
                            return;
                        }
                        dialog.dispose();
                        showNormalInstallConfirmation(plan);
                    });
        }

        private <T> void startTask(String description, Callable<T> operation,
                                   Consumer<T> success) {
            if (!gate.tryEnter()) {
                LauncherAlertDialog.showMessage(dialog, guiScale, "Please wait",
                        "Another operation is already running.");
                return;
            }
            long request = ++generation;
            status.setText(description + "…");
            requestStatus.setText(description + "…");
            if (description.equals("Fetching releases")) {
                fetch.setText("Fetching…");
            }
            setActionsEnabled(false);
            SwingWorker<T, Void> started = new SwingWorker<>() {
                @Override
                protected T doInBackground() throws Exception {
                    return operation.call();
                }

                @Override
                protected void done() {
                    gate.leave();
                    if (worker == this) worker = null;
                    setActionsEnabled(true);
                    fetch.setText("Fetch releases");
                    if (isCancelled() || !dialog.isDisplayable()
                            || request != generation) return;
                    try {
                        success.accept(get());
                    } catch (java.util.concurrent.ExecutionException error) {
                        requestStatus.setText("Request failed; controls are available to retry.");
                        previous.setEnabled(displayedPage > 1);
                        next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                        showError(description + " failed", error.getCause());
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        requestStatus.setText(
                                "Request interrupted; controls are available to retry.");
                        previous.setEnabled(displayedPage > 1);
                        next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                        showError(description + " interrupted", error);
                    } catch (RuntimeException error) {
                        requestStatus.setText("Request failed; controls are available to retry.");
                        previous.setEnabled(displayedPage > 1);
                        next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                        showError(description + " failed", error);
                    }
                    updateInstallEnabled();
                }
            };
            worker = started;
            try {
                started.execute();
            } catch (RuntimeException error) {
                worker = null;
                gate.leave();
                setActionsEnabled(true);
                fetch.setText("Fetch releases");
                requestStatus.setText(
                        "Request could not start; controls are available to retry.");
                previous.setEnabled(displayedPage > 1);
                next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                showError(description + " failed", error);
            }
        }
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
        JButton inspect = button(applyWorkflow ? "Update…" : "Download and preview…",
                "runPreviewButton");
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
        JDialog dialog = dialog("Choose update", body, null,
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
            if (applyWorkflow) {
                boolean update = RecommendedUpdateConsentDialog.confirm(
                        this, displayProduct(repository.key()),
                        sourceCurrent == null ? sourceReceipt.tag() : sourceCurrent.tag(),
                        choice.release().tag(), asset.size(), guiScale);
                if (!update) return;
                dialog.dispose();
                runPreparedUpdate(sourceRecord, sourceReceipt, sourceCurrent,
                        choice.release().tag(), asset.name(), asset.size(), asset.digest(), null);
                return;
            }
            boolean download = LauncherAlertDialog.showConfirm(dialog, guiScale,
                    "Confirm read-only preview download",
                    "Current verified source: "
                            + (sourceCurrent == null ? sourceReceipt.tag() : sourceCurrent.tag())
                            + " / " + (sourceCurrent == null ? sourceReceipt.assetName()
                            : sourceCurrent.assetName())
                            + "\nTarget: " + choice.release().tag() + " / " + asset.name()
                            + "\nDownload: " + NumberFormat.getIntegerInstance().format(asset.size())
                            + " bytes (a full release package; time depends on your connection)"
                            + "\n\nDownload, verify, and inspect in external temporary storage?"
                            + "\nThis step is READ-ONLY: no installed files or metadata will change.",
                    "Download");
            if (!download) return;
            dialog.dispose();
            runUpdatePreview(sourceRecord, sourceReceipt, choice.release().tag(), false);
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

    private void runPreparedUpdate(
            InstallationRecord record, org.megamek.launcher.update.OwnershipReceipt receipt,
            org.megamek.launcher.update.CurrentUpdateState current, String tag,
            String assetName, long assetSize, String assetDigest,
            ChannelUpdateChecker.Result recommended) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                "Preparing update", "applyUpdateProgressLog", this::showOperationLogs);
        progress.append("Downloading and verifying the exact consented package once for "
                + tag + "...\n");
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation("Preparing update", OperationType.UPDATE_APPLY,
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
                    SwingUtilities.invokeLater(() ->
                            progress.setTitleIfCurrent("Preparing update",
                                    "Applying verified update"));
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
                if (result.cleanupWarning() != null) {
                    progress.append("\nCLEANUP WARNING: " + result.cleanupWarning() + "\n");
                    progress.showWarning("Update complete — cleanup warning",
                            "The update was applied, but temporary files could not be removed. "
                                    + "Do not run Update again.");
                } else {
                    progress.dispose();
                }
                page = Page.INSTALLATIONS;
                reload();
            }, failure -> {
                    progress.append("\nUPDATE ATTEMPT FAILED: " + errorDetail(failure)
                            + "\nNo second package download was attempted. If application "
                            + "mutation began, use Check update recovery after closing every "
                            + "suite application.\n");
                    progress.setTitle("Update failed — recovery may be required");
                    reload();
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

    private void repairInstallation(InstallationRecord record) {
        // Capture local provenance off the EDT. No download or mutation precedes consent.
        run("Checking repair eligibility", () -> services.repairSource(record),
                source -> {
                    if (!isDisplayable()) return;
                    String size = source.current().assetSize() > 0
                            ? BinarySizeFormat.mebibytes(source.current().assetSize())
                            : "unknown";
                    boolean confirmed = LauncherAlertDialog.showConfirm(this, guiScale,
                            "Repair installation",
                            "Download size: " + size
                                    + "\n\nClose all MegaMek, MekHQ, and MegaMekLab windows "
                                    + "before continuing."
                                    + "\n\nRepair will replace missing or changed game files. "
                                    + "Your saves and settings will stay."
                                    + "\n\nIf you changed game files you want to keep, back "
                                    + "them up first.",
                            "Repair");
                    if (!confirmed) return;
                    OperationProgressDialog progress = new OperationProgressDialog(this,
                            "Repairing installation", "repairProgressLog",
                            this::showOperationLogs);
                    progress.useRepairProgress();
                    progress.startNonCancellable(
                            "Please keep the launcher open until repair finishes.");
                    progress.setVisible(true);
                    PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                            StandardCharsets.UTF_8);
                    runOperation("Repairing " + record.name(), OperationType.UPDATE_APPLY,
                            List.of(source.root()), progress,
                            context -> {
                                // The backend owns the root gate, live-child check, transaction
                                // journal, and recovery. Never call it with a stale consent.
                                context.checkpoint();
                                return services.repair(source, stream, context);
                            }, result -> {
                                progress.append("\nRestored " + result.missingRestored()
                                        + " missing and " + result.modifiedRestored()
                                        + " modified official files.\n");
                                if (!result.extraLibJars().isEmpty()) {
                                    progress.append("WARNING: extra unowned lib JARs remain: "
                                            + String.join(", ", result.extraLibJars())
                                            + ". They may affect the game.\n");
                                }
                                progress.append(result.warning() + "\n");
                                progress.showRepairComplete(
                                        result.missingRestored() + result.modifiedRestored() == 0
                                                ? "Repair complete. No missing or changed "
                                                        + "installation files were found."
                                                : "Repair complete. Your installation is ready "
                                                        + "to use.",
                                        result.extraLibJars().isEmpty() ? null
                                                : "Some added files may still affect the game. "
                                                        + "View logs for details.");
                                page = Page.INSTALLATIONS;
                                reload();
                            }, error -> {
                                progress.append("\nREPAIR FAILED: " + errorDetail(error)
                                        + "\nIf recovery is pending, close all suite games and "
                                        + "use Recover interrupted update. Do not retry repair "
                                        + "until recovery completes.\n");
                                progress.setTitle("Repair failed — recovery may be required");
                                reload();
                            }, stream::close);
                }, error -> showError("Repair unavailable", error));
    }

    private void recoverUpdate(InstallationRecord record) {
        boolean confirmed = LauncherAlertDialog.showConfirm(this, guiScale,
                "Confirm update recovery",
                "Close ALL MegaMek, MekHQ, and MegaMekLab applications, including ones started "
                        + "outside this launcher.\n\nRecovery never kills a process and refuses a "
                        + "verified live launcher-started child. It completes a fully applied "
                        + "transaction or rolls back with verified backups; unexpected edits "
                        + "remain untouched and block recovery.\n\nContinue?",
                "Recover");
        if (!confirmed) return;
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
                    LauncherAlertDialog.showMessage(this, guiScale, "Recovery complete",
                            result.outcome());
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
        try {
            for (String jar : OwnershipPolicy.extraLibJars(
                    Path.of(result.record().canonicalRoot()),
                    result.currentState().officialManifest(), result.targetManifest())) {
                text.append("WARNING: Preserved unowned lib JAR ").append(jar)
                        .append(" may affect the game; Update will not remove it.\n");
            }
        } catch (IOException e) {
            text.append("WARNING: Could not inspect extra lib JARs: ")
                    .append(e.getMessage()).append('\n');
        }
        JTextArea details = textArea(text.toString());
        details.setName("updatePreviewResults");
        JDialog dialog = dialog("Read-only update preview", new JScrollPane(details), null,
                new Dimension(900, 620));
        dialog.setVisible(true);
    }

    private Path chooseNewInstallDestination(JDialog owner, OfficialRepository repository,
                                             FollowChannel channel, String tag) {
        Path preferredParent = null;
        try {
            preferredParent = services.normalInstallDestination(repository, channel).getParent();
        } catch (IOException ignored) {
            // The chooser still has a safe platform default when the suggested path is unavailable.
        }
        JFileChooser chooser = folders("Choose an existing writable parent folder",
                preferredParent);
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null;
        final String suggestion;
        try {
            suggestion = NormalInstallService.friendlyName(repository, channel, tag);
        } catch (IOException invalid) {
            showError("Preparing install location failed", invalid);
            return null;
        }
        Path folder = SubfolderDialog.show(owner, suggestion, guiScale);
        return folder == null ? null : chooser.getSelectedFile().toPath().resolve(folder);
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
                List.of(destination), progress,
                context -> services.install(repository, tag, destination, name, channel,
                        stream, context),
                    result -> {
                        progress.dispose();
                        progress.append("\nInstalled and registered at "
                                + result.destination() + "\n");
                        LauncherAlertDialog.showMessage(LauncherFrame.this, guiScale,
                                "Install complete",
                                "Installed and registered successfully.\nNothing was launched.");
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
            LauncherAlertDialog.showMessage(this, guiScale, "Please wait",
                    "Another operation is already running.");
            return;
        }
        LauncherServices.LoggedOperation logged = persistLog ? services.beginOperation(
                type, progress, potentialInstallationRoots) : null;
        OperationContext context = logged == null
                ? new OperationContext(type, progress) : logged.context();
        progress.bind(context);
        if (progress.isRepairProgress()) {
            // Disable cancellation in the context as well as the dialog, before the worker
            // starts. A repair must not be interruptible even during its download.
            try {
                context.enterFinalization("This operation must finish or retain recovery state.");
            } catch (OperationCancelledException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        if (!activeOperation.compareAndSet(null, context)) {
            gate.leave();
            progress.dispose();
            throw new IllegalStateException("operation gate and active context disagree");
        }
        status.setText("");
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
                    progress.append("\nCANCELLED before finalization. "
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
                    if (result.problem() instanceof UninstallService.MissingInstallationException missing) {
                        progress.dispose();
                        removeMissingInstallation(missing.record());
                        return;
                    }
                    failure.accept(result.problem());
                    // Some failures (notably a known adoption channel mismatch) close
                    // progress and present their own styled result instead.
                    if (progress.isDisplayable()) {
                        progress.showFailure(result.problem(), result.loggingWarning());
                    }
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
        run(description, operation, success, failure, completion, showAutomaticError, true);
    }

    private <T> void run(String description, Callable<T> operation, Consumer<T> success,
                         Consumer<Throwable> failure, Runnable completion,
                         boolean showAutomaticError, boolean updateStatus) {
        if (!gate.tryEnter()) {
            LauncherAlertDialog.showMessage(this, guiScale, "Please wait",
                    "Another operation is already running.");
            return;
        }
        if (updateStatus) status.setText(description + "…");
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
                    if (problem instanceof UninstallService.MissingInstallationException missing) {
                        status.setText("Installation folder not found.");
                        removeMissingInstallation(missing.record());
                        return;
                    }
                    failure.accept(problem);
                    if (showAutomaticError) {
                        showError(description + (problem instanceof InterruptedException
                                ? " interrupted" : " failed"), problem);
                        if (updateStatus) status.setText("Operation failed — details shown");
                    } else if (updateStatus) {
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
            if (child instanceof FirstLaunchSplitButton split) {
                if (!enabled) {
                    enabledBeforeWork.put(split, split.isEnabled());
                    split.setEnabled(false);
                } else {
                    split.setEnabled(enabledBeforeWork.getOrDefault(split, true));
                }
                continue;
            }
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
        if (error instanceof UninstallService.MissingInstallationException missing) {
            removeMissingInstallation(missing.record());
            return;
        }
        String details = SanitizedErrors.display(error);
        String loggingStatus = record ? "Saving local diagnostics..."
                : "Local operation diagnostics are available when recording succeeded.";
        LauncherErrorDialog errorDialog = LauncherErrorDialog.show(this, title, details,
                loggingStatus, guiScale, () -> copySanitized(details), this::showOperationLogs);
        if (record) {
            recordErrorAsync(title, error, errorDialog::updateLoggingStatus);
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
        area.setBackground(FirstLaunchPanel.PANEL);
        area.setForeground(FirstLaunchPanel.TEXT);
        area.setCaretColor(FirstLaunchPanel.GOLD);
        area.setSelectionColor(HomeLaunchSplitButton.POPUP_SELECTION);
        area.setSelectedTextColor(HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND);
        area.setFont(guiScale.font(new Font(Font.MONOSPACED, Font.PLAIN, 12),
                Font.PLAIN, 12f));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setName("operationLogScrollPane");
        scroll.setBorder(BorderFactory.createLineBorder(new Color(92, 121, 112)));
        scroll.getViewport().setBackground(FirstLaunchPanel.PANEL);

        JDialog viewer = new JDialog(this, "Local operation logs", false);
        viewer.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(guiScale.scaleForGUI(8),
                guiScale.scaleForGUI(8)));
        body.setName("operationLogDialogContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(guiScale.scaleForGUI(16),
                guiScale.scaleForGUI(18), guiScale.scaleForGUI(14),
                guiScale.scaleForGUI(18)));
        JLabel heading = new JLabel("Local operation logs");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(guiScale.font(heading.getFont(), Font.BOLD, 20f));
        body.add(heading, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);

        JButton copy = homeButton("Copy sanitized log", "copyOperationLogButton");
        copy.addActionListener(event -> copySanitized(area.getText()));
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.add(copy);
        JButton close = homeButton("Close", "closeOperationLogButton");
        close.addActionListener(event -> viewer.dispose());
        actions.add(close);
        body.add(actions, BorderLayout.SOUTH);
        viewer.setContentPane(body);
        viewer.setSize(guiScale.scaleForGUI(850, 600));
        viewer.setLocationRelativeTo(this);
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
        return folders(title, null);
    }

    private static JFileChooser folders(String title, Path preferredDirectory) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        Path current = preferredDirectory;
        while (current != null && (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(current))) {
            current = current.getParent();
        }
        if (current != null) chooser.setCurrentDirectory(current.toFile());
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
                "Activate " + text + ".");
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

    @FunctionalInterface
    interface ExistingImportPrompts {
        Path chooseFolder(Component parent);

        default Path chooseFolder(Component parent, Path preferredDirectory) {
            return chooseFolder(parent);
        }
    }

    @FunctionalInterface
    interface NormalInstallLocationPrompts {
        Path choose(Component parent, NormalInstallService.Plan plan) throws IOException;
    }

    private static final class SwingNormalInstallLocationPrompts
            implements NormalInstallLocationPrompts {
        @Override
        public Path choose(Component parent, NormalInstallService.Plan plan) throws IOException {
            JFileChooser chooser = folders("Choose an existing writable parent folder",
                    plan.destination().getParent());
            if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
            String proposedName =
                    NormalInstallService.friendlyName(plan);
            Path folder = SubfolderDialog.show(parent, proposedName, GuiScale.DEFAULT);
            if (folder == null) return null;
            return chooser.getSelectedFile().toPath().resolve(folder);
        }
    }

    private static final class SwingExistingImportPrompts implements ExistingImportPrompts {
        @Override
        public Path chooseFolder(Component parent) {
            return chooseFolder(parent, null);
        }

        @Override
        public Path chooseFolder(Component parent, Path preferredDirectory) {
            JFileChooser chooser = folders("Choose an extracted MegaMek or MekHQ folder",
                    preferredDirectory);
            return chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile().toPath() : null;
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
        cancelCheckWorkers();
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

    private record AdoptionReleaseChoice(ReleaseCatalog.Release release,
                                         ReleaseCatalog.Assessment assessment) {
        @Override public String toString() {
            String tag = release.tag();
            String version = tag != null && tag.length() > 1
                    && (tag.charAt(0) == 'v' || tag.charAt(0) == 'V')
                    ? tag.substring(1) : tag;
            return assessment.eligible()
                    ? version + " — " + release.title()
                    : version + " — unavailable for safe verification";
        }
    }

    private record AdoptionExecution(PreparedAdoption.Report report, boolean enabled) {
    }

    private record PickerReleaseChoice(OfficialRepository repository,
                                       FollowChannel channel,
                                       SelectedChannelReleaseCatalog.Classification classification,
                                       ReleaseCatalog.Release release,
                                       ReleaseCatalog.Assessment assessment,
                                       String source,
                                       String label) {
        private static PickerReleaseChoice from(
                SelectedChannelReleaseCatalog.Entry entry, FollowChannel channel) {
            String tag = entry.release().tag();
            String version = tag != null && tag.length() > 1
                    && (tag.charAt(0) == 'v' || tag.charAt(0) == 'V')
                    ? tag.substring(1) : tag;
            return new PickerReleaseChoice(entry.identity().repository(), channel,
                    entry.classification(), entry.release(), entry.assessment(), entry.source(),
                    VersionDisplay.programChannelVersion(
                            displayProduct(entry.identity().repository().key()),
                            channel.toString(), version));
        }

        @Override
        public String toString() {
            if (!assessment.eligible()) {
                return label + " — Unavailable";
            }
            ReleaseCatalog.Asset asset = assessment.asset();
            return label + " — "
                    + NormalInstallConfirmationDialog.formatBinaryBytes(asset.size());
        }
    }

    private record HomeLoad(LauncherServices.HomeState state, Throwable error,
                            java.awt.image.BufferedImage artwork, Throwable artworkError) {
    }

    private record CheckOnOpenSave(ChannelPreferenceStore.ReadResult authoritative,
                                   Throwable failure) {
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

        private int columnsForWidth(int width, int count) {
            if (width <= 0) return 1;
            int cell = scale.scaleForGUI(300);
            return Math.max(1, Math.min(Math.min(3, count),
                    (width + gap) / (cell + gap)));
        }

        private int availableWidth() {
            if (getWidth() > 0) return getWidth();
            Container parent = getParent();
            if (parent == null) return 0;
            int width = parent.getWidth();
            if (width == 0 && parent.getParent() != null) {
                width = parent.getParent().getWidth();
            }
            java.awt.Insets insets = parent.getInsets();
            return Math.max(0, width - insets.left - insets.right);
        }

        @Override
        public void doLayout() {
            int count = Math.max(1, getComponentCount());
            int next = columnsForWidth(availableWidth(), count);
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
            int usedColumns = columnsForWidth(availableWidth(), count);
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

    private enum FirstLaunchMetadataState {
        NOT_STARTED,
        LOADING,
        READY,
        FAILED
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
