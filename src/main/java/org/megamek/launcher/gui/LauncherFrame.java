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

import org.megamek.launcher.design.swing.TokenPanel;
import org.megamek.launcher.design.swing.TokenTooltips;

import org.megamek.launcher.gui.AutomaticInstallationChecks.InstallationCheck;
import org.megamek.launcher.internationalization.I18n;
import org.megamek.launcher.internationalization.LocalizedIOException;
import org.megamek.launcher.internationalization.LocalizedText;

import org.megamek.launcher.channel.ChannelCatalog;
import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.SuiteReleaseRecord;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.channel.SelectedChannelReleaseCatalog;
import org.megamek.launcher.diagnostics.BugReport;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.launch.LaunchObserver;
import org.megamek.launcher.launch.LaunchOptions;
import org.megamek.launcher.launch.LaunchOptionsStore;
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
import org.megamek.launcher.update.InstallationRepairService;
import org.megamek.launcher.update.PreparedAdoption;
import org.megamek.launcher.update.PreparedUpdate;
import org.megamek.launcher.update.UpdatePreviewService;
import org.megamek.launcher.update.RealUpdateService;
import org.megamek.launcher.update.UninstallService;
import org.megamek.launcher.update.LauncherSelfUpdate;

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
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
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
import java.util.ArrayList;
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
    private static final String BUNDLE = "org.megamek.launcher.gui.frame";
    private static final String CHANGE_LOCATION = I18n.getTextAt(BUNDLE, "LauncherFrame.changeLocation");
    static final String LOCATE_FOLDER = I18n.getTextAt(BUNDLE, "LauncherFrame.locateFolder");
    static final String REMOVE_FROM_LAUNCHER = I18n.getTextAt(BUNDLE, "LauncherFrame.removeFromLauncher");
    static final String SAVE_CHANGES = I18n.getTextAt(BUNDLE, "LauncherFrame.saveChanges");
    static final String DONT_SAVE_CHANGES = I18n.getTextAt(BUNDLE, "LauncherFrame.dontSaveChanges");
    private static final DateTimeFormatter NEWS_DATE =
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);
    static final String DISCORD_INVITE_URL = "https://discord.gg/megamek";
    static final String PLAY_ONLINE_URL = "https://megamek.games/";
    static final String MEKBAY_URL = "https://mekbay.com/";
    static final String FIRST_CORE_BUILD = "0.51.1";
    static final String RETIRED_CHANNEL_STATUS = I18n.getTextAt(BUNDLE, "LauncherFrame.retiredChannelStatus");
    static final String EXPERIMENTAL_CONFIRM_TITLE = I18n.getTextAt(BUNDLE, "LauncherFrame.experimentalConfirmTitle");
    static final String EXPERIMENTAL_CONFIRM_MESSAGE = I18n.getTextAt(BUNDLE, "LauncherFrame.experimentalConfirmMessage");
    static final String CORE_RELEASE_INFO = I18n.getTextAt(BUNDLE, "LauncherFrame.coreReleaseInfo");
    static final String EXPERIMENTAL_RELEASE_INFO = I18n.getTextAt(BUNDLE, "LauncherFrame.experimentalReleaseInfo");
    private final LauncherServices services;
    private final Object appearanceSaveLock = new Object();
    private final LauncherNewsFeed newsFeed;
    private final ExistingImportPrompts existingImportPrompts;
    private final NormalInstallLocationPrompts normalInstallLocationPrompts;
    private final BusyGate gate = new BusyGate();
    private final Map<Component, Boolean> enabledBeforeWork = new IdentityHashMap<>();
    private final JPanel content = new JPanel(new BorderLayout(
            GuiScale.DEFAULT.space("lg"), GuiScale.DEFAULT.space("lg")));
    private final JLabel status = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingInstallations"));
    private final Set<String> checkedOnOpen =
            java.util.Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Boolean> checkPreferenceSaves = new HashMap<>();
    private LauncherServices.HomeState state;
    private SwingWorker<QuickInstallSnapshot, Void> firstLaunchOptionsWorker;
    private FirstLaunchMetadataState firstLaunchMetadataState =
            FirstLaunchMetadataState.NOT_STARTED;
    private QuickInstallSnapshot firstLaunchSnapshot;
    private boolean showExperimental;
    private final LatestSettingSave<Boolean> showExperimentalSave;
    private boolean playIntro;
    /** The window waits to be shown until Home has loaded, when the intro is off. */
    private boolean showWhenLoaded;
    private final LatestSettingSave<Boolean> playIntroSave;
    /** The saved language, or {@code null} for the system language; it applies on the next start. */
    private String language;
    private final LatestSettingSave<String> languageSave;
    private String firstLaunchMetadataFailure;
    /** The last version check worked but nothing offered has been released yet. */
    private boolean firstLaunchNothingReleased;
    private long firstLaunchMetadataAttempt;
    private SwingWorker<Map<String, InstallationCheck>, Void> automaticChecksWorker;
    private SwingWorker<InstallationCheck, Void> installationCheckWorker;
    private final Map<String, InstallationCheck> installationChecks = new HashMap<>();
    private final Map<String, JButton> installationMenuButtons = new HashMap<>();
    private boolean installationMenuPending;
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
    private final LaunchCurtain curtain = new LaunchCurtain(guiScale);
    /** Counts launches so a finished or superseded one cannot move the window. */
    private long launchGeneration;
    private long settledLaunch;
    private Page page = Page.HOME;
    private String selectedInstallationId;
    private LauncherServices.SettingsView launcherSettings;
    private Throwable launcherSettingsError;
    private boolean settingsLoading;
    /** How long Settings may load behind the previous page before its placeholder is shown. */
    private static final int SETTINGS_PLACEHOLDER_DELAY_MS = 400;
    private javax.swing.Timer settingsPlaceholderTimer;
    private boolean newsRequested;
    private boolean newsLoading;
    private List<LauncherNewsFeed.Article> newsArticles = List.of();
    private Throwable newsError;
    private JPanel homeNewsPanel;
    private JPanel homeNewsBody;
    private LaunchOptionsStore.Document launchOptionsDocument;
    private LaunchOptionsForm launchOptionsForm;
    private JLabel launchOptionsMessage;
    private LaunchOptions launchOptionsDraft;
    private Throwable launchOptionsError;
    private String transientHomeMessage;
    private boolean launcherUpdateChecked;
    private final LauncherSelfUpdate launcherUpdater;
    private BugReportDialog bugReportDialog;
    private WhatsNewDialog whatsNewDialog;

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
        this.launcherUpdater = new LauncherSelfUpdate(services.releaseTransport());
        this.showExperimental = services.showExperimental();
        this.showExperimentalSave = new LatestSettingSave<>(services::saveShowExperimental);
        this.playIntro = services.playIntro();
        this.playIntroSave = new LatestSettingSave<>(services::savePlayIntro);
        this.language = services.language();
        this.languageSave = new LatestSettingSave<>(services::saveLanguage);
        curtain.setIntro(playIntro);
        this.newsFeed = newsFeed;
        this.existingImportPrompts = existingImportPrompts;
        this.normalInstallLocationPrompts = normalInstallLocationPrompts;
        setName("launcherFrame");
        setIconImages(LauncherIcons.images());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(guiScale.scaleForGUI(680, 470));
        setPreferredSize(guiScale.scaleForGUI(1180, 820));
        content.setBorder(guiScale.padding("xxl", "xxl", "xl", "xxl"));
        setContentPane(content);
        setGlassPane(curtain);
        curtain.setTargets(() -> homeLaunchButtons);
        addWindowListener(new WindowAdapter() {
            @Override public void windowIconified(WindowEvent event) {
                curtain.pause();
            }

            @Override public void windowDeiconified(WindowEvent event) {
                curtain.reveal();
            }

            @Override public void windowClosing(WindowEvent event) {
                if (gate.isBusy()) {
                    OperationContext operation = activeOperation.get();
                    if (operation != null) {
                        if (operation.type() == OperationType.RECOVERY
                                || operation.type() == OperationType.UNINSTALL_RECOVERY) {
                            status.setText(I18n.getTextAt(BUNDLE,
                                    "LauncherFrame.recoveryFinishing"));
                            return;
                        }
                        OperationContext.CancellationRequest request =
                                operation.requestCancellation();
                        if (request.accepted()) {
                            status.setText(I18n.getTextAt(BUNDLE,
                                    "LauncherFrame.cancellingAtCheckpoint"));
                            return;
                        }
                        status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.operationFinishing"));
                        return;
                    }
                    status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.operationStillRunning"));
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
        return version == null || version.isBlank()
                ? I18n.getTextAt(BUNDLE, "LauncherFrame.titleDevelopmentBuild")
                : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.titleVersion", version);
    }

    private void prepareExactNormalInstall(OfficialRepository repository,
                                           FollowChannel futureChannel, String tag,
                                           String source, Path destination) {
        run(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.checkingExact",
                        displayProduct(repository.key()), tag),
                () -> services.prepareExactNormalInstall(repository, futureChannel, tag,
                        source, destination),
                this::showNormalInstallConfirmation,
                error -> exactPlanFailure(this, repository, futureChannel, tag, source,
                        destination, error, () -> prepareExactNormalInstall(repository,
                                futureChannel, tag, source, destination)),
                () -> {
                }, false);
    }

    /**
     * Shows the window behind the welcome greeting, which opens on Home once it has loaded. With
     * the intro off, the window stays hidden until Home has loaded so it opens on its content.
     */
    public void showWindowWithGreeting() {
        if (!isVisible()) {
            if (!playIntro) {
                showWhenLoaded = true;
                reload();
                return;
            }
            curtain.greet();
        }
        showWindow();
    }

    public void showWindow() {
        if (!isVisible()) {
            setVisible(true);
            fitWindowToScreen(getSize());
        }
        reload();
    }

    private void showIfWaitingForHome() {
        if (!showWhenLoaded) return;
        showWhenLoaded = false;
        if (!isVisible()) {
            setVisible(true);
            fitWindowToScreen(getSize());
        }
    }

    private void reload() {
        homeGeneration++;
        disposeFirstLaunchSplitButton();
        disposeHomeLaunchButtons();
        cancelCheckWorkers();
        installationChecks.clear();
        status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingInstallations"));
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingInstallationsTask"), () -> {
            // Notices a launcher update before the release notes are first opened.
            services.rememberLauncherVersion();
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
                curtain.contentReady(I18n.getTextAt(BUNDLE, "LauncherFrame.welcomeCommander"));
                showIfWaitingForHome();
                if (firstLaunchArtworkError != null) {
                    recordErrorAsync("Loading first-launch artwork", firstLaunchArtworkError,
                            message -> status.setText(I18n.getFormattedTextAt(BUNDLE,
                                    "LauncherFrame.artworkUnavailableWithStatus", message)));
                }
                maybeCheckManagedCopiesOnOpen();
                if (!launcherUpdateChecked
                        && (LauncherSelfUpdate.available() || LauncherSelfUpdate.notifyOnly())) {
                    launcherUpdateChecked = true;
                    checkLauncherUpdate(false);
                }
            } else {
                renderLoadError(loaded.error());
                curtain.contentReady("");
                showIfWaitingForHome();
            }
        }, error -> showIfWaitingForHome());
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
        homeInformationLabel = TokenTooltips.whenClipped(new JLabel());
        homeInformationLabel.setName("homeInformationMessage");
        homeInformationLabel.setOpaque(false);
        homeInformationLabel.setForeground(LauncherTheme.ACCENT);
        homeInformationLabel.setFont(guiScale.font(
                homeInformationLabel.getFont(), "small"));
        homeInformationLabel.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        homeInformationRow = new JPanel(new BorderLayout());
        homeInformationRow.setName("homeInformationRow");
        homeInformationRow.setOpaque(false);
        homeInformationRow.setBorder(BorderFactory.createEmptyBorder(0,
                guiScale.space("md"), guiScale.space("lg"),
                guiScale.space("md")));
        homeInformationRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        homeInformationRow.add(homeInformationLabel, BorderLayout.WEST);
        // Reserve one line of text height so Home does not shift when the message clears.
        homeInformationRow.add(Box.createVerticalStrut(homeInformationLabel.getFontMetrics(
                homeInformationLabel.getFont()).getHeight()), BorderLayout.EAST);
        homeInformationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                homeInformationRow.getPreferredSize().height));
        center.add(homeInformationRow);
        updateHomeInformationMessage();
        renderApplicationLaunches(center);

        center.add(Box.createVerticalStrut(guiScale.space("lg")));

        JButton manage = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.installations"),
                "manageInstallationsButton");
        manage.setMnemonic(java.awt.event.KeyEvent.VK_I);
        manage.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        homeInstallationsButton = manage;
        updateHomeInstallationsLabel();
        JButton settings = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.settings"), "settingsButton");
        settings.setMnemonic(java.awt.event.KeyEvent.VK_S);
        settings.addActionListener(event -> navigateTo(Page.SETTINGS));
        JPanel navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER,
                guiScale.space("md"), 0));
        navigation.setName("managedHomeNavigation");
        navigation.setOpaque(false);
        navigation.setAlignmentX(Component.CENTER_ALIGNMENT);
        navigation.add(manage);
        navigation.add(settings);
        JPanel navigationRow = homeNavigationRow(navigation);
        navigationRow.setName("managedHomeNavigationRow");
        center.add(navigationRow);

        homeNewsBody = new JPanel();
        homeNewsBody.setName("homeNewsBody");
        homeNewsBody.setLayout(new BoxLayout(homeNewsBody, BoxLayout.Y_AXIS));
        TokenPanel newsPanel = new TokenPanel(I18n.getTextAt(BUNDLE, "LauncherFrame.latestNews"))
                .body(homeNewsBody);
        newsPanel.setName("homeNewsPanel");
        homeNewsPanel = newsPanel;
        fillHomeNews();
        content.add(FirstLaunchPanel.managedHome(firstLaunchArtwork, guiScale, center, homeNewsPanel),
                BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
        loadNews();
    }

    private void fillHomeNews() {
        JPanel news = homeNewsBody;
        if (news == null) return;
        news.removeAll();
        if (newsLoading || !newsRequested) {
            JLabel loading = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingNews"));
            loading.setName("newsLoadingMessage");
            loading.setForeground(FirstLaunchPanel.MUTED);
            loading.setAlignmentX(Component.LEFT_ALIGNMENT);
            news.add(loading);
            news.add(Box.createVerticalStrut(guiScale.space("sm")));
        } else if (newsError != null) {
            JLabel unavailable = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.newsUnavailable"));
            unavailable.setName("newsUnavailableMessage");
            unavailable.setForeground(FirstLaunchPanel.MUTED);
            unavailable.setToolTipText(errorDetail(newsError));
            unavailable.setAlignmentX(Component.LEFT_ALIGNMENT);
            news.add(unavailable);
            news.add(Box.createVerticalStrut(guiScale.space("sm")));
        } else {
            for (int i = 0; i < newsArticles.size(); i++) {
                LauncherNewsFeed.Article article = newsArticles.get(i);
                String headline = NEWS_DATE.format(article.published())
                        + " - " + article.title();
                JLabel date = new JLabel(NEWS_DATE.format(article.published()));
                date.setName("newsArticleDate" + i);
                date.setFont(guiScale.font(date.getFont(), "caption"));
                date.setForeground(FirstLaunchPanel.MUTED);
                date.setBorder(BorderFactory.createEmptyBorder(0, guiScale.space("xs"), 0, 0));
                date.setAlignmentX(Component.LEFT_ALIGNMENT);
                news.add(date);
                JButton link = new LauncherLink(article.title(), "newsArticleButton" + i, guiScale);
                link.setAlignmentX(Component.LEFT_ALIGNMENT);
                link.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
                link.setToolTipText(headline);
                link.getAccessibleContext().setAccessibleName(headline);
                link.addActionListener(event -> openWebsite(article.link(),
                        I18n.getTextAt(BUNDLE, "LauncherFrame.websiteMegaMekNews")));
                news.add(link);
                news.add(Box.createVerticalStrut(guiScale.space("sm")));
            }
        }
        JButton archive = new LauncherLink(I18n.getTextAt(BUNDLE, "LauncherFrame.allNews"),
                "allNewsButton", guiScale);
        archive.setAlignmentX(Component.LEFT_ALIGNMENT);
        archive.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.allNewsDescription"));
        archive.addActionListener(event -> openWebsite(LauncherNewsFeed.ARCHIVE,
                I18n.getTextAt(BUNDLE, "LauncherFrame.websiteNewsArchive")));
        news.add(archive);
        news.revalidate();
        news.repaint();
        Container plate = news.getParent();
        if (plate != null && plate.getParent() != null) {
            plate.getParent().revalidate();
            plate.getParent().repaint();
        }
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
        if (page == Page.SETTINGS && next != Page.SETTINGS && launchOptionsDirty()) {
            String answer = LauncherAlertDialog.show(this,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.saveLaunchOptionsTitle"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.saveLaunchOptionsMessage"), guiScale,
                    SAVE_CHANGES, DONT_SAVE_CHANGES, LauncherAlertDialog.CANCEL);
            if (SAVE_CHANGES.equals(answer)) {
                saveLaunchOptionsThenNavigate(next);
                return;
            }
            if (!DONT_SAVE_CHANGES.equals(answer)) return;
        }
        if (page == Page.SETTINGS && next != Page.SETTINGS) {
            launchOptionsDraft = null;
            launchOptionsForm = null;
        }
        if (next != Page.HOME) {
            disposeFirstLaunchSplitButton();
            disposeHomeLaunchButtons();
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

    private void loadNews() {
        if (newsRequested) return;
        newsRequested = true;
        newsLoading = true;
        fillHomeNews();
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
                if (isDisplayable() && page == Page.HOME) fillHomeNews();
            }
        }.execute();
    }

    private void loadSettingsPage() {
        if (settingsLoading) return;
        settingsLoading = true;
        // A quick load swaps straight to the finished page instead of flashing a placeholder.
        settingsPlaceholderTimer = new javax.swing.Timer(SETTINGS_PLACEHOLDER_DELAY_MS, event -> {
            if (settingsLoading && page == Page.SETTINGS) renderSettingsPage();
        });
        settingsPlaceholderTimer.setRepeats(false);
        settingsPlaceholderTimer.start();
        // Settings has its own delayed placeholder; a Home status line here would only grow the
        // page being left for a moment.
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingSettingsTask"), () -> {
            LauncherServices.SettingsView view = services.settingsView();
            try {
                return new SettingsLoad(view, services.launchOptions(), null);
            } catch (IOException error) {
                return new SettingsLoad(view, null, error);
            }
        }, loaded -> {
            settingsLoading = false;
            settingsPlaceholderTimer.stop();
            launcherSettings = loaded.view();
            launcherSettingsError = null;
            launchOptionsDocument = loaded.launchOptions();
            launchOptionsError = loaded.launchOptionsError();
            if (launchOptionsError != null) {
                recordErrorAsync("Reading launch options", launchOptionsError, ignored -> {
                });
            }
            renderSettingsPage();
        }, error -> {
            settingsLoading = false;
            settingsPlaceholderTimer.stop();
            launcherSettings = null;
            launcherSettingsError = error;
            recordErrorAsync("Reading launcher settings", error, ignored -> {
            });
            renderSettingsPage();
        }, () -> {
        }, false, false);
    }

    private void renderLoadError(Throwable error) {
        state = null;
        content.removeAll();
        restoreStandardChrome();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.registryLoadFailed"));
        heading.setName("registryErrorTitle");
        heading.setFont(guiScale.font(heading.getFont(), "headline"));
        panel.add(heading);
        panel.add(Box.createVerticalStrut(guiScale.space("lg")));
        JTextArea details = textArea(errorDetail(error));
        details.setName("registryErrorDetails");
        panel.add(details);
        JLabel logging = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.checkingDiagnosticsStorage"));
        logging.setName("registryErrorLoggingStatus");
        panel.add(logging);
        recordErrorAsync("Launcher registry could not be loaded", error, logging::setText);
        panel.add(Box.createVerticalStrut(guiScale.space("lg")));
        JButton retry = button(I18n.getTextAt(BUNDLE, "LauncherFrame.retry"), "retryRegistryButton");
        retry.addActionListener(event -> reload());
        panel.add(retry);
        JButton home = button(I18n.getTextAt(BUNDLE, "LauncherFrame.home"), "homeButton");
        home.addActionListener(event -> {
            page = Page.HOME;
            reload();
        });
        panel.add(home);
        JButton logs = button(I18n.getTextAt(BUNDLE, "LauncherFrame.viewLogs"),
                "viewOperationLogsButton");
        logs.addActionListener(event -> showOperationLogs());
        panel.add(logs);
        content.add(panel, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    private void restoreStandardChrome() {
        content.setBorder(guiScale.padding("xxl", "xxl", "xl", "xxl"));
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
                },
                showExperimental);
        source[0] = download;
        firstLaunchSplitButton = download;
        JButton useExisting = new FirstLaunchButton(
                I18n.getTextAt(BUNDLE, "LauncherFrame.useExistingInstallation"),
                "useExistingCopyButton", false, guiScale);
        useExisting.setMnemonic(java.awt.event.KeyEvent.VK_U);
        useExisting.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.useExistingInstallationDescription"));
        useExisting.addActionListener(event -> chooseExisting());
        status.setText(transientHomeMessage != null && !transientHomeMessage.isBlank()
                ? transientHomeMessage
                : firstLaunchArtworkError == null
                ? ""
                : I18n.getTextAt(BUNDLE, "LauncherFrame.artworkUnavailable"));
        FirstLaunchPanel firstLaunch = new FirstLaunchPanel(firstLaunchArtwork, guiScale,
                download, useExisting, status, firstLaunchNavigation());
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
        firstLaunchNothingReleased = false;
        final long attempt = ++firstLaunchMetadataAttempt;
        applyFirstLaunchMetadata(false);
        final boolean experimental = showExperimental;
        firstLaunchOptionsWorker = new SwingWorker<>() {
            @Override
            protected QuickInstallSnapshot doInBackground() throws Exception {
                return services.quickInstallSnapshot(experimental);
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
                        firstLaunchOptionsFailed(I18n.getTextAt(BUNDLE,
                                "LauncherFrame.installVersionsNoCatalog"));
                    } else {
                        firstLaunchSnapshot = loaded;
                        firstLaunchMetadataFailure = null;
                        firstLaunchMetadataState = FirstLaunchMetadataState.READY;
                    }
                } catch (java.util.concurrent.CancellationException ignored) {
                    return;
                } catch (java.util.concurrent.ExecutionException error) {
                    if (error.getCause() instanceof ChannelCatalog.UnpublishedChannelException) {
                        firstLaunchNothingReleased = true;
                        firstLaunchOptionsFailed(I18n.getTextAt(BUNDLE,
                                "LauncherFrame.noReleaseToInstall"));
                    } else {
                        firstLaunchOptionsFailed(I18n.getFormattedTextAt(BUNDLE,
                                "LauncherFrame.installVersionsUnavailable",
                                SanitizedErrors.display(error.getCause())));
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    firstLaunchOptionsFailed(I18n.getTextAt(BUNDLE,
                            "LauncherFrame.versionCheckInterrupted"));
                }
                applyFirstLaunchMetadata(false);
            }
        };
        try {
            firstLaunchOptionsWorker.execute();
        } catch (RuntimeException error) {
            firstLaunchOptionsWorker = null;
            firstLaunchOptionsFailed(I18n.getFormattedTextAt(BUNDLE,
                    "LauncherFrame.versionCheckCouldNotStart", SanitizedErrors.display(error)));
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
                // A missing release is not a failure the player can act on, so it stays quiet.
                if (firstLaunchNothingReleased) {
                    status.setText("");
                } else {
                    status.setText(wrappingStatus(I18n.getTextAt(BUNDLE,
                            "LauncherFrame.couldNotCheckInstallVersions")));
                    status.setToolTipText(firstLaunchMetadataFailure);
                }
            }
        }
    }

    /** Empty Home is narrow-safe only if its guidance wraps instead of clipping. */
    private static String wrappingStatus(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='text-align:center'>" + escaped + "</div></html>";
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
            status.setText(wrappingStatus(I18n.getTextAt(BUNDLE,
                    "LauncherFrame.versionRefreshFailed")));
            status.setToolTipText(firstLaunchMetadataFailure);
            return;
        }
        status.setToolTipText(null);
        status.setText(isCurrentEmptyFirstLaunch() && firstLaunchArtworkError != null
                ? wrappingStatus(I18n.getTextAt(BUNDLE, "LauncherFrame.artworkUnavailable"))
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
        String count = updates == 0 ? null
                : updates == 1 ? I18n.getTextAt(BUNDLE, "LauncherFrame.oneUpdate")
                : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.updatesCount",
                String.valueOf(updates));
        ButtonDetail.setBadge(homeInstallationsButton, count);
        homeInstallationsButton.getAccessibleContext().setAccessibleName(
                count == null ? I18n.getTextAt(BUNDLE, "LauncherFrame.installations")
                        : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.installationsWithCount",
                        count));
        homeInstallationsButton.getAccessibleContext().setAccessibleDescription(
                updates == 0 ? I18n.getTextAt(BUNDLE, "LauncherFrame.openInstallationManagement")
                        : updates == 1
                        ? I18n.getTextAt(BUNDLE, "LauncherFrame.openInstallationManagementOneUpdate")
                        : I18n.getFormattedTextAt(BUNDLE,
                        "LauncherFrame.openInstallationManagementUpdates", String.valueOf(updates)));
        updateHomeInformationMessage();
    }

    private void updateHomeInformationMessage() {
        if (homeInformationLabel == null) return;
        String text = homeInformationText();
        homeInformationLabel.setText(text);
        homeInformationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                homeInformationRow.getPreferredSize().height));
        homeInformationLabel.getAccessibleContext().setAccessibleName(
                text.isBlank() ? I18n.getTextAt(BUNDLE, "LauncherFrame.noLauncherMessage") : text);
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
            return I18n.getTextAt(BUNDLE, "LauncherFrame.checkingForUpdates");
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
            // The Installations button's count badge already says this.
            return "";
        }
        boolean failed = checkedRecords.stream().map(record -> installationChecks.get(record.id()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(check -> check.error() != null || check.result() == null
                        || check.result().retryable()
                        && check.result().status() != ChannelUpdateChecker.Status.CHANNEL_NOT_PUBLISHED);
        if (failed) return I18n.getTextAt(BUNDLE, "LauncherFrame.someInstallationsNotChecked");
        java.util.EnumSet<FollowChannel> unpublished = java.util.EnumSet.noneOf(FollowChannel.class);
        for (InstallationRecord record : checkedRecords) {
            InstallationCheck check = installationChecks.get(record.id());
            if (check != null && check.result() != null
                    && check.result().status() == ChannelUpdateChecker.Status.CHANNEL_NOT_PUBLISHED) {
                unpublished.add(check.result().preference().channel());
            }
        }
        if (unpublished.size() == 1) return unpublishedChannelMessage(unpublished.iterator().next());
        if (!unpublished.isEmpty()) {
            return I18n.getTextAt(BUNDLE, "LauncherFrame.updateInformationNotAvailableSome");
        }
        boolean allChecked = checkedRecords.stream().allMatch(record -> {
            InstallationCheck check = installationChecks.get(record.id());
            return check != null && check.error() == null && check.result() != null;
        });
        boolean allInstallationsManaged =
                checkedRecords.size() == state.registry().installations().size();
        if (!allChecked) return "";
        return allInstallationsManaged
                ? I18n.getTextAt(BUNDLE, "LauncherFrame.allInstallationsUpToDate")
                : I18n.getTextAt(BUNDLE, "LauncherFrame.checkedInstallationsUpToDate");
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
        boolean unavailable = false;
        for (String productKey : List.of("mekhq", "megamek", "lab")) {
            InstallationRecord record = targets.get(productKey);
            if (record == null) continue;
            String channel = homeChannelLabel(record);
            HomeLaunchSplitButton launch = new HomeLaunchSplitButton(guiScale, productKey,
                    displayProduct(productKey), record.observedBuild(), channel,
                    homeAlternativeLabel(record, productKey),
                    () -> launchDirectly(record, productKey),
                    homeLaunchAlternatives(record, productKey));
            LauncherServices.InstallationStatus local = installationLocalStatus(record);
            if (local != null && local.missing()) {
                String value = VersionDisplay.channelVersion(channel, record.observedBuild());
                ButtonDetail.setDetail(launch.primaryButton(),
                        value.isEmpty() ? I18n.getTextAt(BUNDLE, "LauncherFrame.notFound")
                                : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.notFoundWithValue",
                                value));
                launch.primaryButton().getAccessibleContext().setAccessibleName(
                        I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.valueNotFound",
                                VersionDisplay.programChannelVersion(displayProduct(productKey),
                                        channel, record.observedBuild())));
                launch.primaryButton().getAccessibleContext().setAccessibleDescription(
                        I18n.getTextAt(BUNDLE, "LauncherFrame.preferredFolderNotFound"));
                launch.getAccessibleContext().setAccessibleDescription(
                        I18n.getTextAt(BUNDLE, "LauncherFrame.preferredFolderNotFound"));
            } else {
                pending |= local != null && local.pendingUpdate();
                pendingUninstall |= local != null && local.pendingUninstall();
                unavailable |= local != null && local.error() != null;
            }
            launch.setPrimaryAvailable(homeLaunchAvailable(local));
            homeLaunchButtons.add(launch);
            launches.add(launch);
        }
        if (launches.getComponentCount() > 0) {
            // MekBay is the suite's web tool; it sits with the programs once any of them is installed
            HomeLaunchSplitButton mekBay = HomeLaunchSplitButton.website(guiScale, "mekbay",
                    "MekBay", I18n.getTextAt(BUNDLE, "LauncherFrame.webApp"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.mekBayDescription"),
                    () -> openWebsite(java.net.URI.create(MEKBAY_URL), "MekBay"));
            homeLaunchButtons.add(mekBay);
            launches.add(mekBay);
        }
        panel.add(launches);
        if (pending || pendingUninstall || unavailable) {
            panel.add(Box.createVerticalStrut(guiScale.space("md")));
            String message = pendingUninstall
                    ? I18n.getTextAt(BUNDLE, "LauncherFrame.uninstallRecoveryRequiredHome")
                    : pending
                    ? I18n.getTextAt(BUNDLE, "LauncherFrame.updateRecoveryNeededHome")
                    : I18n.getTextAt(BUNDLE, "LauncherFrame.installationChangedHome");
            JLabel blocker = homeStatusLabel(message);
            blocker.setName("homeActionRequiredMessage");
            blocker.setForeground(LauncherTheme.WARNING);
            // BoxLayout offsets the whole column when one child keeps JLabel's default left alignment.
            blocker.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(blocker);
        }
    }

    private static boolean homeLaunchAvailable(LauncherServices.InstallationStatus local) {
        return local == null || !local.missing() && !local.pendingUpdate()
                && !local.pendingUninstall() && local.error() == null;
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
                        () -> launchDirectly(record, productKey),
                        homeLaunchAvailable(installationLocalStatus(record))))
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
                version == null ? I18n.getTextAt(BUNDLE, "LauncherFrame.unknownVersion") : version);
        String label = name.equalsIgnoreCase(product)
                ? name + (version == null ? "" : " · " + version)
                        + (channel == null ? "" : " · " + channel)
                : name + " · " + details;
        return local != null && local.missing()
                ? I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.valueNotFound", label)
                : label + (update == null ? "" : " · " + update);
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
        label.setForeground(FirstLaunchPanel.ACCENT);
        label.setFont(guiScale.font(label.getFont(), "body.strong"));
        return label;
    }

    private static String channelStatus(ChannelUpdateChecker.Result result, String error) {
        if (error != null) return I18n.getTextAt(BUNDLE, "LauncherFrame.couldNotCheck");
        if (result == null) return I18n.getTextAt(BUNDLE, "LauncherFrame.notChecked");
        return switch (result.status()) {
            case EXACT_CURRENT -> I18n.getTextAt(BUNDLE, "LauncherFrame.upToDate");
            case UPDATE_AVAILABLE -> I18n.getTextAt(BUNDLE, "LauncherFrame.updateAvailable");
            case INSTALLED_AHEAD -> I18n.getTextAt(BUNDLE, "LauncherFrame.installedAhead");
            case UNCONFIGURED -> I18n.getTextAt(BUNDLE, "LauncherFrame.releaseTypeNotSet");
            case RETIRED_CHANNEL -> RETIRED_CHANNEL_STATUS;
            case CHANNEL_NOT_PUBLISHED -> unpublishedChannelMessage(result.preference().channel());
            case UNAVAILABLE, NON_COMPARABLE -> I18n.getTextAt(BUNDLE, "LauncherFrame.couldNotCheck");
        };
    }

    private static String unpublishedChannelMessage(FollowChannel channel) {
        return I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.channelUpdateInformationNotAvailable",
                String.valueOf(channel));
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
        firstLaunchNothingReleased = false;
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
                        return new InstallationCheck(null, I18n.getTextAt(BUNDLE,
                                "LauncherFrame.checkBindingChanged"));
                    }
                    return new InstallationCheck(result, null);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return new InstallationCheck(null, I18n.getTextAt(BUNDLE,
                            "LauncherFrame.checkCancelled"));
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
                    OfficialRepository.MEKHQ, FollowChannel.CORE).getParent();
        } catch (IOException ignored) {
            // The chooser will use its platform fallback if no suggestion can be resolved.
        }
        Path selected = existingImportPrompts.chooseFolder(this, preferredParent);
        if (selected == null) return;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.addingExistingInstallation"),
                "existingImportProgressLog",
                this::showOperationLogs);
        progress.append("Inspecting and adding the selected installation...\n");
        progress.setVisible(true);
        java.util.concurrent.atomic.AtomicBoolean inspectionComplete =
                new java.util.concurrent.atomic.AtomicBoolean();
        runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.addingExistingInstallation"),
                OperationType.IMPORT_EXISTING,
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
                        progress.setHeading(I18n.getTextAt(BUNDLE,
                                "LauncherFrame.couldNotAddInstallation"));
                    } else {
                        progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.folderCannotBeUsed"));
                        progress.setExpectedFailureSummary(I18n.getTextAt(BUNDLE,
                                "LauncherFrame.chooseMainInstallationFolder"));
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
            return I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.existingInstallationName", product);
        }
        return VersionDisplay.programChannelVersion(product, null, version);
    }

    private void renderInstallationsPage() {
        installationMenuButtons.clear();
        content.removeAll();
        content.setBorder(guiScale.padding("xxl", "xxl", "xl", "xxl"));
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        JPanel header = new JPanel(new BorderLayout(0, guiScale.space("lg")));
        header.setBackground(FirstLaunchPanel.BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0,
                guiScale.space("lg"), 0));
        JLabel title = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.installations"),
                javax.swing.SwingConstants.CENTER);
        title.setName("pageTitle");
        title.setForeground(FirstLaunchPanel.TEXT);
        title.setFont(guiScale.font(title.getFont(), "headline"));
        header.add(title, BorderLayout.NORTH);
        JPanel topActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                0, 0));
        topActions.setName("installationTopActions");
        topActions.setOpaque(false);
        JButton install = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.installAnotherVersion"),
                "installAnotherVersionButton");
        install.addActionListener(event -> downloadDialog());
        JButton importExisting = homeButton(
                I18n.getTextAt(BUNDLE, "LauncherFrame.importExistingInstallation"),
                "manageAddExistingButton");
        importExisting.addActionListener(event -> chooseExisting());
        OfficialRepository coreRepository = coreInstallRepository();
        if (coreRepository != null) {
            JButton installCore = new FirstLaunchButton(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.installCore"), "installCoreButton",
                    true, guiScale, FirstLaunchButton.Size.SMALL);
            installCore.getAccessibleContext().setAccessibleDescription(
                    I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.installCoreDescription",
                            displayProduct(coreRepository.key())));
            installCore.addActionListener(event ->
                    prepareNormalInstall(coreRepository, FollowChannel.CORE, null));
            topActions.add(installCore);
            topActions.add(Box.createHorizontalStrut(guiScale.space("md")));
        }
        topActions.add(install);
        topActions.add(Box.createHorizontalStrut(guiScale.space("md")));
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
            cards.add(Box.createVerticalStrut(guiScale.space("md")));
        }
        JScrollPane scroll = LauncherTheme.scrollPane(cards);
        scroll.setName("installationCardsScrollPane");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(FirstLaunchPanel.BACKGROUND);
        scroll.getViewport().addChangeListener(event -> {
            int rightInset = scroll.getWidth() - scroll.getViewport().getX()
                    - scroll.getViewport().getWidth();
            if (topActions.getInsets().right != rightInset) {
                topActions.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, rightInset));
                topActions.revalidate();
            }
        });
        content.add(scroll, BorderLayout.CENTER);
        JPanel navigation = pageNavigation(Page.INSTALLATIONS);
        navigation.setBackground(FirstLaunchPanel.BACKGROUND);
        content.add(navigation, BorderLayout.SOUTH);
        content.revalidate();
        content.repaint();
    }

    private JPanel installationCard(InstallationRecord record) {
        JPanel card = new JPanel(new BorderLayout(guiScale.space("lg"),
                guiScale.space("md"))) {
            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        card.setName("installationCard-" + record.id());
        card.setBackground(FirstLaunchPanel.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LauncherTheme.BORDER),
                guiScale.padding("lg", "xl")));
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
        name.setFont(guiScale.font(name.getFont(), "title"));
        titleRow.add(name);
        if (version != null || channel != null) {
            titleRow.add(Box.createHorizontalStrut(guiScale.space("md")));
            JLabel details = new JLabel((version == null ? "" : version)
                    + (version != null && channel != null ? " · " : "")
                    + (channel == null ? "" : channel));
            details.setName("installationDetails-" + record.id());
            details.setForeground(FirstLaunchPanel.MUTED);
            details.setFont(guiScale.font(details.getFont(), "body"));
            titleRow.add(details);
        }
        summary.add(titleRow);
        summary.add(Box.createVerticalStrut(guiScale.space("xs")));
        boolean missing = local != null && local.missing();
        boolean ownershipProvenance = !missing && hasOwnershipProvenance(local);
        boolean fixedChannel = hasFixedChannel(local);
        boolean updateManaged = !missing && managedUpdatesAvailable(local);
        boolean trueImported = !missing && trueImported(local);
        String products = record.products().stream()
                .map(Product::key).map(LauncherFrame::displayProduct)
                .collect(java.util.stream.Collectors.joining(", "));
        String provenance = missing
                ? products
                : legacyRelease(record, local)
                ? I18n.getTextAt(BUNDLE, "LauncherFrame.provenanceLegacyRelease")
                : trueImported
                ? I18n.getTextAt(BUNDLE, "LauncherFrame.provenanceImportedCopy")
                : fixedChannel
                ? products
                : I18n.getTextAt(BUNDLE, "LauncherFrame.provenanceSetupIncomplete");
        String markers = record.products().stream().map(Product::key)
                .filter(key -> (state.preferredApplications() != null
                        && record.equals(state.preferredApplications().get(key)))
                        || record.id().equals(
                                state.registry().preferredInstallationIds().get(key)))
                .map(key -> I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.preferredFor",
                        displayProduct(key)))
                .collect(java.util.stream.Collectors.joining(" · "));
        if (!markers.isBlank()) {
            JLabel preferred = new JLabel(markers);
            preferred.setName("preferredApplicationMarkers");
            preferred.setForeground(LauncherTheme.WARNING);
            preferred.setAlignmentX(Component.LEFT_ALIGNMENT);
            summary.add(preferred);
        }
        JLabel metadata = new JLabel(provenance);
        metadata.setName("installationProvenance-" + record.id());
        metadata.setForeground(FirstLaunchPanel.MUTED);
        metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(metadata);
        String updateText = installationUpdateStatus(record, local);
        if (!provenance.toLowerCase(java.util.Locale.ROOT)
                .contains(updateText.toLowerCase(java.util.Locale.ROOT))) {
            JLabel updateStatus = new JLabel(updateText);
            updateStatus.setName("installationStatus-" + record.id());
            updateStatus.setForeground(local != null
                    && (local.pendingUpdate() || local.pendingUninstall())
                    ? LauncherTheme.WARNING : FirstLaunchPanel.MUTED);
            updateStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
            summary.add(updateStatus);
        }
        if (updateManaged) {
            ChannelPreference expectedPreference = local.channelPreference().preference();
            Boolean pendingSelection = checkPreferenceSaves.get(record.id());
            JCheckBox checkOnOpen = new MapCheckBox(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.checkOnOpen"),
                    pendingSelection == null
                            ? expectedPreference.checkOnOpen() : pendingSelection, guiScale);
            checkOnOpen.setName("checkOnOpenCheckbox-" + record.id());
            checkOnOpen.setOpaque(false);
            checkOnOpen.setMnemonic(KeyEvent.VK_C);
            checkOnOpen.setAlignmentX(Component.LEFT_ALIGNMENT);
            checkOnOpen.setBorder(BorderFactory.createEmptyBorder());
            checkOnOpen.setMargin(new java.awt.Insets(0, 0, 0, 0));
            checkOnOpen.getAccessibleContext().setAccessibleName(
                    I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.checkOnOpenFor", record.name()));
            checkOnOpen.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.checkOnOpenDescription"));
            checkOnOpen.setEnabled(pendingSelection == null);
            checkOnOpen.addActionListener(event -> saveCheckOnOpen(
                    record, expectedPreference, checkOnOpen));
            summary.add(Box.createVerticalStrut(guiScale.space("xs")));
            summary.add(checkOnOpen);
        }
        card.add(summary, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT,
                guiScale.space("md"), 0));
        actions.setOpaque(false);
        InstallationCheck checked = installationChecks.get(record.id());
        boolean updateAvailable = checked != null && checked.result() != null
                && checked.result().updateAvailable();
        if (ownershipProvenance && local.pendingUpdate()) {
            JButton recover = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.recover"),
                    "recoverUpdateButton");
            recover.addActionListener(event -> recoverUpdate(record));
            actions.add(recover);
        } else if (updateAvailable && updateManaged) {
            JButton update = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.update"),
                    "applyUpdateButton");
            update.setFont(guiScale.font(update.getFont(), "label"));
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
                    || checked.result().retryable();
            if (needsCheck) {
                String checkText = checking ? I18n.getTextAt(BUNDLE, "LauncherFrame.checking")
                        : checked != null ? I18n.getTextAt(BUNDLE, "LauncherFrame.retryCheck")
                        : I18n.getTextAt(BUNDLE, "LauncherFrame.check");
                JButton check = homeButton(checkText, "checkUpdatesButton");
                check.setEnabled(installationCheckWorker == null && !checking);
                check.addActionListener(event -> startInstallationCheck(record));
                actions.add(check);
            }
        }
        JButton more = homeButton(missing ? I18n.getTextAt(BUNDLE, "LauncherFrame.locate")
                        : I18n.getTextAt(BUNDLE, "LauncherFrame.more"),
                "installationMenuButton-" + record.id());
        installationMenuButtons.put(record.id(), more);
        more.getAccessibleContext().setAccessibleDescription(missing
                ? I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.locateOrRemove", record.name())
                : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.moreActionsFor", record.name()));
        more.addActionListener(event -> openInstallationMenu(record));
        actions.add(more);
        JPanel centeredActions = new JPanel(new java.awt.GridBagLayout());
        centeredActions.setName("installationActions-" + record.id());
        centeredActions.setOpaque(false);
        centeredActions.add(actions);
        card.add(centeredActions, BorderLayout.EAST);
        return card;
    }

    /**
     * Confirms the folder still exists, then opens the card's menu. The check is quick, so unlike
     * {@link #run} it leaves the page's controls enabled; dimming them all reads as a page redraw.
     */
    private void openInstallationMenu(InstallationRecord record) {
        if (installationMenuPending) return;
        if (!gate.tryEnter()) {
            LauncherAlertDialog.showMessage(this, guiScale, I18n.getTextAt(BUNDLE, "LauncherFrame.pleaseWait"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.anotherOperationRunning"));
            return;
        }
        installationMenuPending = true;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                services.requireInstallationPresent(record);
                return null;
            }

            @Override protected void done() {
                gate.leave();
                installationMenuPending = false;
                Throwable problem = null;
                try {
                    get();
                } catch (java.util.concurrent.ExecutionException e) {
                    problem = e.getCause();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    problem = e;
                }
                if (!isDisplayable()) return;
                if (problem == null) {
                    showInstallationMenu(record);
                } else if (problem instanceof UninstallService.MissingInstallationException missing) {
                    status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.installationFolderNotFound"));
                    removeMissingInstallation(missing.record());
                } else {
                    showError(LocalizedText.of(BUNDLE, problem instanceof InterruptedException
                            ? "LauncherFrame.checkingFolderInterrupted"
                            : "LauncherFrame.checkingFolderFailed"), problem);
                }
            }
        }.execute();
    }

    private void showInstallationMenu(InstallationRecord record) {
        JButton currentButton = installationMenuButtons.get(record.id());
        if (page != Page.INSTALLATIONS || currentButton == null || !currentButton.isShowing()) return;
        if (state.registry().installations().stream().noneMatch(record::equals)) {
            showError(LocalizedText.of(BUNDLE, "LauncherFrame.installationChanged"), new LocalizedIOException(
                    LocalizedText.of(BUNDLE, "LauncherFrame.reopenInstallations")));
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
                    showError(LocalizedText.of(BUNDLE, "LauncherFrame.updateCheckSettingNotSaved"), interrupted);
                    return;
                } catch (java.util.concurrent.ExecutionException failure) {
                    checkbox.setSelected(expectedPreference.checkOnOpen());
                    checkbox.setEnabled(true);
                    showError(LocalizedText.of(BUNDLE, "LauncherFrame.updateCheckSettingNotSaved"), failure.getCause());
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
                    showError(LocalizedText.of(BUNDLE, "LauncherFrame.updateCheckSettingNotSaved"), outcome.failure());
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
        menu.setBackground(LauncherTheme.POPUP_BACKGROUND);
        menu.setBorder(BorderFactory.createLineBorder(LauncherTheme.BORDER));
        addPreferenceActions(menu, record);
        menu.add(installationMenuSeparator());
        if (local != null && local.pendingUninstall()) {
            JMenuItem recover = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.recoverUninstall"));
            recover.addActionListener(event -> recoverUninstall(record));
            menu.add(recover);
            return menu;
        }
        if (ownershipProvenance && local.pendingUpdate()) {
            JMenuItem recover = installationMenuItem(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.recoverInterruptedUpdate"));
            recover.addActionListener(event -> recoverUpdate(record));
            menu.add(recover);
        }
        JMenuItem location = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.openLocation"));
        location.getAccessibleContext().setAccessibleDescription(
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.openLocationDescription", record.name()));
        location.addActionListener(event -> openLocation(record));
        menu.add(location);
        JMenuItem rename = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.renameMenu"));
        rename.setName("renameInstallation-" + record.id());
        rename.addActionListener(event -> renameInstallation(record));
        menu.add(rename);
        JMenuItem launchOptions = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.launchOptionsMenu"));
        launchOptions.setName("launchOptions-" + record.id());
        launchOptions.getAccessibleContext().setAccessibleDescription(
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.launchOptionsDescription", record.name()));
        launchOptions.addActionListener(event -> openInstallationLaunchOptions(record));
        menu.add(launchOptions);
        JMenuItem repair = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.repairMenu"));
        repair.setName("repairInstallation-" + record.id());
        repair.getAccessibleContext().setAccessibleDescription(
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.repairDescription", record.name()));
        repair.setEnabled(local == null || !local.pendingUpdate() && !local.missing());
        repair.addActionListener(event -> repairInstallation(record));
        menu.add(repair);
        JMenuItem remove = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.removeFromLauncherMenu"));
        remove.addActionListener(event -> removeFromLauncher(record));
        menu.add(remove);
        if (enableUpdatesOffered(record, local)) {
            menu.add(installationMenuSeparator());
            JMenuItem adopt = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.enableUpdatesMenu"));
            adopt.setName("enableManagedUpdates-" + record.id());
            adopt.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.enableUpdatesDescription"));
            adopt.addActionListener(event -> beginAdoption(record));
            menu.add(adopt);
        }
        if (updateManaged) {
            menu.add(installationMenuSeparator());
            JMenuItem uninstall = installationMenuItem(I18n.getTextAt(BUNDLE, "LauncherFrame.uninstallMenu"));
            uninstall.addActionListener(event -> uninstall(record));
            menu.add(uninstall);
        }
        return menu;
    }

    /**
     * A copy with several applications offers one "for all applications" action first, the common
     * case when switching between suite copies, with the per-application choices in a submenu. A
     * single-application copy keeps its one direct action.
     */
    private void addPreferenceActions(JPopupMenu menu, InstallationRecord record) {
        Map<String, String> preferred = state.registry().preferredInstallationIds();
        List<JMenuItem> perApplication = new ArrayList<>();
        for (Product product : record.products()) {
            String label = displayProduct(product.key());
            JMenuItem item = installationMenuItem(
                    record.products().size() > 1 ? label
                            : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.useAsPreferredFor", label));
            item.setName("preferFor-" + product.key() + "-" + record.id());
            item.setEnabled(!record.id().equals(preferred.get(product.key())));
            item.addActionListener(event -> run(
                    I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.changingPreference", label),
                    () -> {
                        services.selectPreferred(product.key(), record);
                        return null;
                    }, ignored -> reload()));
            perApplication.add(item);
        }
        if (perApplication.size() <= 1) {
            perApplication.forEach(menu::add);
            return;
        }
        boolean anyChangeable = perApplication.stream().anyMatch(JMenuItem::isEnabled);
        JMenuItem all = installationMenuItem(
                I18n.getTextAt(BUNDLE, "LauncherFrame.useAsPreferredForAll"));
        all.setName("preferForAll-" + record.id());
        all.setEnabled(anyChangeable);
        all.addActionListener(event -> run(I18n.getTextAt(BUNDLE, "LauncherFrame.changingApplicationPreferences"),
                () -> {
                    services.selectPreferredForAll(record);
                    return null;
                }, ignored -> reload()));
        menu.add(all);
        JMenu submenu = installationSubmenu(I18n.getTextAt(BUNDLE, "LauncherFrame.useAsPreferredForMenu"));
        submenu.setName("preferForSubmenu-" + record.id());
        submenu.setEnabled(anyChangeable);
        perApplication.forEach(submenu::add);
        menu.add(submenu);
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
                selectionBackground = LauncherTheme.SELECTION_BACKGROUND;
                selectionForeground = LauncherTheme.TEXT;
                disabledForeground = FirstLaunchPanel.MUTED;
            }
        });
        return styleInstallationMenuItem(item, text);
    }

    /** Like {@link #installationMenuItem} but opens a submenu, so it keeps the arrow icon. */
    private JMenu installationSubmenu(String text) {
        JMenu submenu = new JMenu(text);
        submenu.setUI(new javax.swing.plaf.basic.BasicMenuUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                checkIcon = null;
                defaultTextIconGap = 0;
                selectionBackground = LauncherTheme.SELECTION_BACKGROUND;
                selectionForeground = LauncherTheme.TEXT;
                disabledForeground = FirstLaunchPanel.MUTED;
            }
        });
        styleInstallationMenuItem(submenu, text);
        JPopupMenu popup = submenu.getPopupMenu();
        popup.setBackground(LauncherTheme.POPUP_BACKGROUND);
        popup.setBorder(BorderFactory.createLineBorder(LauncherTheme.BORDER));
        return submenu;
    }

    private <T extends JMenuItem> T styleInstallationMenuItem(T item, String text) {
        item.setOpaque(true);
        item.setBackground(LauncherTheme.POPUP_BACKGROUND);
        item.setForeground(LauncherTheme.TEXT);
        item.setIconTextGap(0);
        // BasicMenuItemUI.installDefaults() also pulls the active look and
        // feel's "MenuItem.margin" onto every item, independent of the
        // checkIcon/arrowIcon/iconTextGap already zeroed out above. Most
        // look and feels size that margin to leave room for an icon column,
        // which is exactly the leftover left-hand space these icon-less
        // items don't need. Zero it so only the explicit border below
        // controls spacing.
        item.setMargin(new java.awt.Insets(0, 0, 0, 0));
        item.setBorder(guiScale.padding("md", "lg"));
        item.getAccessibleContext().setAccessibleName(text);
        return item;
    }

    private JPanel installationMenuSeparator() {
        int height = guiScale.space("lg");
        int inset = guiScale.space("lg");
        JPanel separator = new JPanel() {
            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                super.paintComponent(graphics);
                graphics.setColor(LauncherTheme.BORDER);
                int y = getHeight() / 2;
                graphics.drawLine(inset, y, Math.max(inset, getWidth() - inset), y);
            }
        };
        separator.setName("installationMenuSeparator");
        separator.setOpaque(true);
        separator.setBackground(LauncherTheme.POPUP_BACKGROUND);
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

    /**
     * A copy from before Core/Experimental: either its channel sidecar names a retired channel,
     * or it has no release type and its build predates the first Core release. Launch-only.
     */
    /**
     * Adoption can only match an official Core/Experimental release, whose versions are plain
     * dotted numbers; suffixed (nightly/snapshot) or unreadable builds could never succeed.
     */
    private static boolean enableUpdatesOffered(InstallationRecord record,
                                        LauncherServices.InstallationStatus local) {
        return local != null && !local.missing() && trueImported(local)
                && !legacyRelease(record, local) && officialVersionShape(record.observedBuild());
    }

    static boolean officialVersionShape(String build) {
        return build != null && build.strip().matches("[vV]?\\d+(\\.\\d+)+");
    }

    private static boolean legacyRelease(InstallationRecord record,
                                         LauncherServices.InstallationStatus local) {
        if (local == null || local.missing()) return false;
        if (local.channelPreference() != null
                && local.channelPreference().status() == ChannelPreferenceStore.Status.RETIRED) {
            return true;
        }
        return !hasFixedChannel(local) && record != null && preCoreBuild(record.observedBuild());
    }

    static boolean preCoreBuild(String build) {
        if (build == null) return false;
        java.util.regex.Matcher numeric =
                java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(build);
        if (!numeric.find()) return false;
        return SuiteReleaseRecord.compareVersions(numeric.group(1) + "." + numeric.group(2)
                + "." + numeric.group(3), FIRST_CORE_BUILD) < 0;
    }

    /**
     * The Core package to offer from the Installations header, or {@code null} once any present
     * copy follows Core. The package covers every program the registered copies provide.
     */
    private OfficialRepository coreInstallRepository() {
        Set<String> keys = new java.util.HashSet<>();
        for (InstallationRecord record : state.registry().installations()) {
            LauncherServices.InstallationStatus status = installationLocalStatus(record);
            if (status != null && !status.missing() && hasFixedChannel(status)
                    && status.channelPreference().preference().channel() == FollowChannel.CORE) {
                return null;
            }
            record.products().forEach(product -> keys.add(product.key()));
        }
        if (keys.equals(Set.of("megamek"))) return OfficialRepository.MEGAMEK;
        if (keys.equals(Set.of("lab"))) return OfficialRepository.LAB;
        return OfficialRepository.MEKHQ;
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
            return I18n.getTextAt(BUNDLE, "LauncherFrame.folderNotFound");
        }
        if (local != null && local.pendingUninstall()) {
            return I18n.getTextAt(BUNDLE, "LauncherFrame.uninstallRecoveryRequired");
        }
        if (local != null && local.error() != null) {
            return I18n.getTextAt(BUNDLE, "LauncherFrame.installationNeedsAttention");
        }
        if (legacyRelease(record, local)) return RETIRED_CHANNEL_STATUS;
        if (!hasOwnershipProvenance(local)) return I18n.getTextAt(BUNDLE, "LauncherFrame.launchOnly");
        if (!managedUpdatesAvailable(local)) {
            return I18n.getTextAt(BUNDLE, "LauncherFrame.releaseTypeNeedsRepair");
        }
        if (local.pendingUpdate()) return I18n.getTextAt(BUNDLE, "LauncherFrame.recoveryRequired");
        InstallationCheck checked = installationChecks.get(record.id());
        if (checked == null) {
            return local != null && local.previewEligibility() != null
                    && !local.previewEligibility().available()
                    ? I18n.getTextAt(BUNDLE, "LauncherFrame.launchOnlyUpdatesUnavailable")
                    : I18n.getTextAt(BUNDLE, "LauncherFrame.updateStatusNotChecked");
        }
        if ("Checking".equals(checked.error())) return I18n.getTextAt(BUNDLE, "LauncherFrame.checking");
        return channelStatus(checked.result(), checked.error());
    }

    private void beginAdoption(InstallationRecord record) {
        final long generation = homeGeneration;
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.checkingImportedCopy"),
                () -> services.adoptionSuggestion(record),
                suggestion -> {
                    if (isCurrentAdoption(generation, record)) {
                        showAdoptionStart(record, suggestion, generation);
                    }
                },
                error -> {
                    if (!isCurrentAdoption(generation, record)) return;
                    showIneligibleAdoption(new PreparedAdoption.Report(false,
                            record.name(), record.observedBuild(), FollowChannel.CORE,
                            ImportedCopyAdoptionService.automaticMatchUnavailable()));
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
        details.setBorder(guiScale.padding("xl", "xxl", "md", "xxl"));
        int detailGap = guiScale.space("md");

        JLabel heading = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.enableManagedUpdates"));
        heading.setName("adoptionCandidateHeading");
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(guiScale.font(heading.getFont(), "headline"));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(heading);
        details.add(Box.createVerticalStrut(detailGap));

        JLabel application = new JLabel(I18n.getFormattedTextAt(BUNDLE,
                "LauncherFrame.detectedApplicationVersion", suggestion.detectedApplication(),
                suggestion.detectedVersion()));
        application.setName("adoptionDetectedApplicationVersion");
        application.setForeground(FirstLaunchPanel.TEXT);
        application.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(application);
        details.add(Box.createVerticalStrut(detailGap));

        JPanel channelRow = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 0, 0));
        channelRow.setOpaque(false);
        JLabel channelLabel = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.releaseTypeLabel"));
        channelLabel.setForeground(FirstLaunchPanel.TEXT);
        StyledComboBox<FollowChannel> channel = new StyledComboBox<>(
                FollowChannel.offered(showExperimental).toArray(FollowChannel[]::new),
                guiScale);
        channel.setName("adoptionChannelCombo");
        channel.setPreferredSize(new Dimension(
                guiScale.scaleForGUI(180), channel.getPreferredSize().height));
        channel.setSelectedItem(FollowChannel.CORE);
        channel.setToolTipText(channelChoiceDescription());
        channel.getAccessibleContext().setAccessibleName(
                I18n.getTextAt(BUNDLE, "LauncherFrame.releaseType"));
        channel.getAccessibleContext().setAccessibleDescription(I18n.getFormattedTextAt(BUNDLE,
                "LauncherFrame.channelChoiceFixedAfterEnabling", channelChoiceDescription()));
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
                I18n.getTextAt(BUNDLE, "LauncherFrame.existingFilesUnchanged"));
        explanation.setName("adoptionExplanation");
        explanation.setForeground(FirstLaunchPanel.MUTED);
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(explanation);

        JDialog dialog = new JDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.enableManagedUpdates"), false);
        dialog.setName("adoptionCandidateDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JButton cancel = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.cancel"),
                "cancelAdoptionButton");
        JButton continueButton = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.continue"),
                "continueAdoptionButton", true);
        continueButton.setFont(guiScale.font(continueButton.getFont(), "label"));
        continueButton.setMnemonic(KeyEvent.VK_C);
        continueButton.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.continueAdoptionDescription"));
        cancel.setMnemonic(KeyEvent.VK_A);
        cancel.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.cancelAdoptionDescription"));
        DialogFooter footer = new DialogFooter(guiScale).primary(continueButton).cancel(cancel);
        JPanel actions = footer.panel();
        actions.setName("adoptionCandidateActions");
        actions.setBorder(BorderFactory.createEmptyBorder(
                0, 0, guiScale.space("lg"), 0));
        JPanel body = new JPanel(new BorderLayout(0, guiScale.space("xl")));
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.add(details, BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        packToWidth(dialog, "dialog.md");
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
        footer.install(dialog, dialog::dispose);
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
        releases.setSelectionBackground(LauncherTheme.SELECTION_BACKGROUND);
        releases.setSelectionForeground(LauncherTheme.TEXT);
        releases.getAccessibleContext().setAccessibleName(
                I18n.getTextAt(BUNDLE, "LauncherFrame.officialReleases"));

        JLabel status = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.selectFetchVersions"));
        status.setName("adoptionReleaseStatus");
        status.setForeground(FirstLaunchPanel.MUTED);
        JButton fetch = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchVersions"),
                "fetchAdoptionReleasesButton");
        JButton next = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.nextPage"),
                "nextAdoptionReleasePageButton");
        JButton cancel = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.cancel"),
                "cancelAdoptionReleaseButton");
        JButton continueButton = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.continue"),
                "continueSelectedAdoptionButton", true);
        next.setEnabled(false);
        continueButton.setEnabled(false);
        final int[] pageNumber = {0};
        final boolean[] more = {false};

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.chooseMatchingOfficialVersion"));
        title.setForeground(FirstLaunchPanel.ACCENT);
        title.setFont(guiScale.font(title.getFont(), "headline"));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel explanation = new JLabel(
                I18n.getTextAt(BUNDLE, "LauncherFrame.chooseMatchingOfficialVersionExplanation"));
        explanation.setForeground(FirstLaunchPanel.MUTED);
        explanation.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(title);
        heading.add(Box.createVerticalStrut(guiScale.space("xs")));
        heading.add(explanation);
        heading.add(Box.createVerticalStrut(guiScale.space("md")));
        heading.add(fetch);

        // Paging belongs to the list; the footer holds only the dialog's own choices.
        JPanel paging = new JPanel(new BorderLayout(guiScale.space("md"), 0));
        paging.setOpaque(false);
        paging.add(status, BorderLayout.CENTER);
        paging.add(next, BorderLayout.EAST);
        DialogFooter footer = new DialogFooter(guiScale).primary(continueButton).cancel(cancel);
        JPanel actions = new JPanel(new BorderLayout(0, guiScale.space("xl")));
        actions.setOpaque(false);
        actions.add(paging, BorderLayout.NORTH);
        actions.add(footer.panel(), BorderLayout.SOUTH);

        JPanel body = new JPanel(new BorderLayout(guiScale.space("lg"),
                guiScale.space("lg")));
        body.setName("adoptionReleaseBrowserContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(guiScale.padding("xxl", "xxl"));
        body.add(heading, BorderLayout.NORTH);
        body.add(LauncherTheme.scrollPane(releases), BorderLayout.CENTER);
        body.add(actions, BorderLayout.SOUTH);
        JDialog dialog = new JDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.chooseOfficialVersionTitle"), false);
        dialog.setName("adoptionReleaseBrowser");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(body);
        dialog.setSize(guiScale.size("dialog.lg"), guiScale.size("dialog.sm"));
        dialog.setLocationRelativeTo(this);

        Consumer<Integer> load = requested -> {
            fetch.setEnabled(false);
            next.setEnabled(false);
            continueButton.setEnabled(false);
            run(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchingOfficialVersions"),
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
                                ? I18n.getTextAt(BUNDLE, "LauncherFrame.noReleasesOnPage")
                                : I18n.getTextAt(BUNDLE, "LauncherFrame.selectMatchingVersion"));
                    }, error -> {
                        fetch.setEnabled(true);
                        next.setEnabled(pageNumber[0] > 0 && more[0]);
                        status.setText(I18n.getTextAt(BUNDLE,
                                "LauncherFrame.officialVersionsNotLoaded"));
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
                status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.releaseNotVerifiable"));
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
        footer.install(dialog, dialog::dispose);
        DialogFooter.pressOnEnter(fetch);
        DialogFooter.pressOnEnter(next);
        dialog.setVisible(true);
    }

    private void prepareAdoption(
            InstallationRecord record,
            ImportedCopyAdoptionService.Suggestion suggestion,
            String exactTag, FollowChannel fixedChannel, long generation) {
        if (!isCurrentAdoption(generation, record)) return;
        if (!FollowChannel.offered(showExperimental).contains(fixedChannel)) {
            showError(LocalizedText.of(BUNDLE, "LauncherFrame.releaseTypeNotAvailable"),
                    new LocalizedIOException(LocalizedText.of(BUNDLE,
                            "LauncherFrame.turnOnShowReleases", String.valueOf(fixedChannel))));
            return;
        }
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.verifyingImportedCopy"),
                "adoptionProgressLog", this::showOperationLogs);
        JButton browse = button(I18n.getTextAt(BUNDLE, "LauncherFrame.chooseDifferentVersion"),
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
        runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.verifyingImportedCopy"),
                OperationType.ADOPT_EXISTING,
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
                    progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.copyRemainsLaunchOnly"));
                    if (publicationAttempted.get()) {
                        progress.setFailureSummary(I18n.getTextAt(BUNDLE,
                                "LauncherFrame.copyCouldNotBeEnabled"));
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


    void showIneligibleAdoption(PreparedAdoption.Report report) {
        JPanel body = adoptionResultBody(I18n.getTextAt(BUNDLE, "LauncherFrame.updatesCannotBeEnabled"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.updatesCannotBeEnabledMessage",
                        report.message()));
        JButton close = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.close"), "closeAdoptionResultButton", true);
        DialogFooter footer = new DialogFooter(guiScale).primary(close);
        body.add(footer.panel(), BorderLayout.SOUTH);
        JDialog dialog = adoptionResultDialog(
                I18n.getTextAt(BUNDLE, "LauncherFrame.updatesCannotBeEnabled"), body);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        close.addActionListener(event -> dialog.dispose());
        footer.install(dialog, dialog::dispose);
        dialog.setVisible(true);
    }

    private void showChannelMismatchAdoption(
            InstallationRecord record,
            ImportedCopyAdoptionService.Suggestion suggestion,
            ImportedCopyAdoptionService.ChannelMismatchException mismatch,
            long generation) {
        String headingText = I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.useChannelForInstallation",
                String.valueOf(mismatch.requiredChannel()));
        boolean retryOffered = FollowChannel.offered(showExperimental)
                .contains(mismatch.requiredChannel());
        String guidance = retryOffered
                ? I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.selectChannelToVerify",
                        String.valueOf(mismatch.requiredChannel()))
                : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.turnOnChannelToEnable",
                        String.valueOf(mismatch.requiredChannel()));
        JPanel body = adoptionResultBody(headingText,
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.channelMismatchMessage",
                        mismatch.currentVersion(), withArticle(mismatch.requiredChannel()),
                        withArticle(mismatch.selectedChannel()), guidance));
        if (!retryOffered) {
            JButton close = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.close"), "closeAdoptionResultButton", true);
            DialogFooter footer = new DialogFooter(guiScale).primary(close);
            body.add(footer.panel(), BorderLayout.SOUTH);
            JDialog dialog = adoptionResultDialog(headingText, body);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            close.addActionListener(event -> dialog.dispose());
            footer.install(dialog, dialog::dispose);
            dialog.setVisible(true);
            return;
        }
        JButton close = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.close"), "closeAdoptionResultButton");
        JButton retry = homeButton(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.tryChannel",
                        String.valueOf(mismatch.requiredChannel())),
                "retrySuggestedAdoptionButton", true);
        retry.setFont(guiScale.font(retry.getFont(), "label"));
        DialogFooter footer = new DialogFooter(guiScale).primary(retry).cancel(close);
        body.add(footer.panel(), BorderLayout.SOUTH);
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
            if (!FollowChannel.offered(showExperimental).contains(mismatch.requiredChannel())) {
                return;
            }
            prepareAdoption(record, suggestion, null,
                    mismatch.requiredChannel(), generation);
        });
        footer.install(dialog, dialog::dispose);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) {
                retry.requestFocusInWindow();
                dialog.getRootPane().setDefaultButton(retry);
            }
        });
        dialog.setVisible(true);
    }

    private static String withArticle(FollowChannel channel) {
        return I18n.getFormattedTextAt(BUNDLE, channel == FollowChannel.EXPERIMENTAL
                ? "LauncherFrame.channelWithAn" : "LauncherFrame.channelWithA", String.valueOf(channel));
    }

    private JPanel adoptionResultBody(String headingText, String result) {
        JPanel body = new JPanel(new BorderLayout(0, guiScale.space("xl")));
        body.setName("adoptionResultContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(guiScale.padding("xl", "xxl"));

        JPanel summary = new JPanel();
        summary.setOpaque(false);
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(headingText);
        heading.setName("adoptionResultHeading");
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(guiScale.font(heading.getFont(), "headline"));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(heading);
        summary.add(Box.createVerticalStrut(guiScale.space("lg")));

        int textWidth = guiScale.size("dialog.md") - 2 * guiScale.space("xxl");
        JLabel message = new JLabel("<html><body style='width:" + textWidth + "px'>"
                + result + "</body></html>");
        message.setName("adoptionResultMessage");
        message.setForeground(FirstLaunchPanel.TEXT);
        message.setFont(guiScale.font(message.getFont(), "body"));
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(message);
        JScrollPane scroll = new JScrollPane(summary);
        scroll.setName("adoptionResultScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        LauncherTheme.styleScrollPane(scroll, guiScale);
        body.add(scroll, BorderLayout.CENTER);
        return body;
    }

    private JDialog adoptionResultDialog(String title, JPanel body) {
        JDialog dialog = new JDialog(this, title, false);
        dialog.setName("adoptionResultDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setContentPane(body);
        packToWidth(dialog, "dialog.md");
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    /**
     * Sizes {@code dialog} to its content, at least as wide as the design token {@code widthToken}
     * and never larger than the usable screen area, so long names or paths cannot push its actions
     * off-screen. The height is measured again at the final width.
     */
    private void packToWidth(JDialog dialog, String widthToken) {
        dialog.pack();
        Rectangle available = GuiScale.usableBounds(dialog.getGraphicsConfiguration());
        dialog.setSize(GuiScale.fitWindow(new Dimension(
                Math.max(dialog.getWidth(), guiScale.size(widthToken)), dialog.getHeight()), available));
        dialog.validate();
        dialog.setSize(GuiScale.fitWindow(new Dimension(dialog.getWidth(),
                Math.max(dialog.getHeight(), dialog.getPreferredSize().height)), available));
    }

    private void renameInstallation(InstallationRecord record) {
        JDialog dialog = new JDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.renameInstallationTitle"), false);
        dialog.setName("renameInstallationDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        JPanel body = new JPanel(new BorderLayout(guiScale.space("lg"),
                guiScale.space("lg")));
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(guiScale.padding("xl", "xxl"));
        JLabel heading = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.nameThisInstallation"));
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(guiScale.font(heading.getFont(), "headline"));
        body.add(heading, BorderLayout.NORTH);

        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setOpaque(false);
        JTextField name = new JTextField(installationDisplayName(record));
        name.setName("installationNameField");
        name.setForeground(FirstLaunchPanel.TEXT);
        name.setBackground(FirstLaunchPanel.PANEL);
        name.setCaretColor(FirstLaunchPanel.TEXT);
        name.setFont(guiScale.font(name.getFont(), "body"));
        name.getAccessibleContext().setAccessibleName(
                I18n.getTextAt(BUNDLE, "LauncherFrame.installationName"));
        fields.add(name);
        fields.add(Box.createVerticalStrut(guiScale.space("xs")));
        JLabel hint = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.renameHint"));
        hint.setForeground(FirstLaunchPanel.MUTED);
        fields.add(hint);
        body.add(fields, BorderLayout.CENTER);

        JButton cancel = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.cancel"),
                "cancelRenameButton");
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.saveName"),
                "saveInstallationNameButton", true);
        save.addActionListener(event -> {
            String requested = name.getText().strip();
            if (requested.isEmpty() || requested.length() > 120
                    || requested.chars().anyMatch(Character::isISOControl)) {
                hint.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.renameInvalid"));
                hint.setForeground(LauncherTheme.WARNING);
                name.requestFocusInWindow();
                return;
            }
            if (requested.equals(record.name())) {
                dialog.dispose();
                return;
            }
            dialog.dispose();
            run(I18n.getTextAt(BUNDLE, "LauncherFrame.renamingInstallation"),
                    () -> services.renameInstallation(record, requested),
                    ignored -> reload(), ignored -> {}, () -> {}, true, false);
        });
        DialogFooter footer = new DialogFooter(guiScale).primary(save).cancel(cancel);
        body.add(footer.panel(), BorderLayout.SOUTH);
        dialog.setContentPane(body);
        footer.install(dialog, dialog::dispose);
        packToWidth(dialog, "dialog.md");
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        name.selectAll();
        name.requestFocusInWindow();
    }

    private void openLocation(InstallationRecord record) {
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.openingInstallationFolder"), () -> {
            services.openLocation(record);
            return null;
        }, ignored -> status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.openedInstallationFolder")),
                error -> showError(LocalizedText.of(BUNDLE, "LauncherFrame.openLocationFailed"), error));
    }

    private void removeFromLauncher(InstallationRecord record) {
        boolean confirmed = LauncherAlertDialog.showDangerConfirm(this, guiScale,
                I18n.getTextAt(BUNDLE, "LauncherFrame.removeFromLauncherTitle"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.removeFromLauncherMessage",
                        record.name()),
                I18n.getTextAt(BUNDLE, "LauncherFrame.remove"));
        if (!confirmed) return;
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.removingFromLauncher"),
                () -> services.removeFromLauncher(record), result -> {
            selectedInstallationId = null;
            transientHomeMessage = result.warning() == null
                    ? I18n.getTextAt(BUNDLE, "LauncherFrame.removedFilesKept")
                    : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.removedFilesKeptWarning",
                            result.warning());
            page = Page.HOME;
            reload();
        });
    }

    private void removeMissingInstallation(InstallationRecord record) {
        String choice = LauncherAlertDialog.show(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.installationNotFoundTitle"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.installationNotFoundMessage",
                        record.name()),
                guiScale, LOCATE_FOLDER, REMOVE_FROM_LAUNCHER, LauncherAlertDialog.CANCEL);
        if (LOCATE_FOLDER.equals(choice)) {
            locateMissingInstallation(record);
            return;
        }
        if (!REMOVE_FROM_LAUNCHER.equals(choice)) return;
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.removingMissingInstallation"),
                () -> services.removeMissingFromLauncher(record), result -> {
                    selectedInstallationId = null;
                    transientHomeMessage = result.warning() == null
                            ? I18n.getTextAt(BUNDLE, "LauncherFrame.removedMissingInstallation")
                            : I18n.getFormattedTextAt(BUNDLE,
                            "LauncherFrame.removedMissingInstallationWarning", result.warning());
                    page = Page.HOME;
                    reload();
                });
    }

    private void locateMissingInstallation(InstallationRecord record) {
        Path oldRoot = Path.of(record.canonicalRoot());
        Path selected = existingImportPrompts.chooseFolder(this, oldRoot.getParent());
        if (selected == null) return;
        run(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.locating", record.name()),
                () -> services.relocate(record, selected,
                        OperationContext.none(OperationType.IMPORT_EXISTING)),
                moved -> {
                    selectedInstallationId = moved.id();
                    transientHomeMessage = I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.foundAt",
                            moved.name(), moved.canonicalRoot());
                    page = Page.HOME;
                    reload();
                });
    }

    private void uninstall(InstallationRecord record) {
        Boolean removeLogs = LauncherAlertDialog.showConfirmWithOption(this, guiScale,
                I18n.getTextAt(BUNDLE, "LauncherFrame.uninstall"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.uninstallMessage", record.name()),
                I18n.getTextAt(BUNDLE, "LauncherFrame.alsoDeleteLogs"), true,
                I18n.getTextAt(BUNDLE, "LauncherFrame.uninstall"), true);
        if (removeLogs == null) return;
        boolean deleteLogs = removeLogs;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.uninstalling"), "uninstallProgressLog",
                this::showOperationLogs);
        progress.setVisible(true);
        runOperation(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.uninstallingName", record.name()),
                OperationType.UNINSTALL,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.uninstall(record, deleteLogs, context), result -> {
                    progress.dispose();
                    selectedInstallationId = null;
                    transientHomeMessage = I18n.getTextAt(BUNDLE, result.retainedFiles()
                            ? "LauncherFrame.uninstalledKeptFiles" : "LauncherFrame.uninstalled");
                    if (result.warning() != null) {
                        transientHomeMessage = I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.messageWithWarning",
                                transientHomeMessage, result.warning());
                    }
                    page = Page.HOME;
                    reload();
                }, error -> progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.uninstallFailed")),
                () -> {
                });
    }

    private void recoverUninstall(InstallationRecord record) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.recoveringUninstall"),
                "uninstallRecoveryProgressLog", this::showOperationLogs);
        progress.startNonCancellable(
                I18n.getTextAt(BUNDLE, "LauncherFrame.uninstallRecoveryMustRemainOpen"));
        progress.setVisible(true);
        runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.recoveringUninstall"),
                OperationType.UNINSTALL_RECOVERY,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.recoverUninstall(record, context),
                result -> {
                    progress.dispose();
                    transientHomeMessage = result.warning() == null ? result.message()
                            : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.messageWithWarning",
                            result.message(), result.warning());
                    page = Page.HOME;
                    reload();
                }, error -> progress.setHeading(I18n.getTextAt(BUNDLE,
                        "LauncherFrame.uninstallRecoveryFailed")), () -> {
                });
    }

    private JPanel pageHeader(String titleText, String subtitle) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(titleText);
        title.setFont(guiScale.font(title.getFont(), "headline"));
        title.setName("pageTitle");
        header.add(title);
        header.add(new JLabel(subtitle));
        return header;
    }

    private JPanel pageNavigation(Page current) {
        JPanel navigation = new JPanel();
        JButton home = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.home"), "homeButton");
        home.setMnemonic(java.awt.event.KeyEvent.VK_H);
        home.addActionListener(event -> navigateTo(Page.HOME));
        JButton installations = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.installations"),
                "installationsButton");
        installations.setEnabled(current != Page.INSTALLATIONS);
        installations.addActionListener(event -> navigateTo(Page.INSTALLATIONS));
        JButton settings = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.settings"), "settingsButton");
        settings.setEnabled(current != Page.SETTINGS);
        settings.addActionListener(event -> navigateTo(Page.SETTINGS));
        navigation.add(home);
        navigation.add(installations);
        navigation.add(settings);
        return navigation;
    }

    private void renderSettingsPage() {
        if (launchOptionsForm != null) {
            launchOptionsDraft = launchOptionsDirty() ? launchOptionsForm.values() : null;
        }
        content.removeAll();
        content.setBorder(guiScale.padding("xxl", "xxl", "xl", "xxl"));
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        JPanel heading = pageHeader(I18n.getTextAt(BUNDLE, "LauncherFrame.settings"),
                I18n.getTextAt(BUNDLE, "LauncherFrame.settingsSubtitle"));
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
        List<JButton> utilityButtons = new java.util.ArrayList<>();
        if (settingsLoading) {
            JLabel loading = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingSettings"));
            loading.setName("settingsLoadingMessage");
            loading.setForeground(FirstLaunchPanel.MUTED);
            left.add(loading);
        } else if (launcherSettings != null) {
            left.add(appearanceSection());
            left.add(Box.createVerticalStrut(guiScale.space("lg")));
            left.add(releasesSection());
            left.add(Box.createVerticalStrut(guiScale.space("lg")));
            JPanel java = settingsSection("GameJava", I18n.getTextAt(BUNDLE, "LauncherFrame.gameJava"));
            JLabel runtime = new JLabel(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.javaVersion",
                    String.valueOf(launcherSettings.defaultJavaFeature())));
            runtime.setName("defaultJavaStatus");
            runtime.setForeground(FirstLaunchPanel.TEXT);
            runtime.setAlignmentX(Component.LEFT_ALIGNMENT);
            java.add(runtime);
            java.add(Box.createVerticalStrut(guiScale.space("xs")));
            JPanel javaRow = new JPanel(new BorderLayout(guiScale.space("lg"), 0));
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
            javaPath.setFont(guiScale.font(javaPath.getFont(), "small"));
            javaPath.setForeground(FirstLaunchPanel.MUTED);
            javaPath.setCaretPosition(0);
            javaPath.getAccessibleContext().setAccessibleName(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.gameJavaPath"));
            javaRow.add(javaPath, BorderLayout.CENTER);
            JButton changeJava = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.changeDefaultJava"),
                    "changeDefaultJavaButton");
            changeJava.setAlignmentX(Component.LEFT_ALIGNMENT);
            changeJava.addActionListener(event -> chooseDefaultJava());
            utilityButtons.add(changeJava);
            javaRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    javaPath.getPreferredSize().height));
            java.add(javaRow);
            java.add(Box.createVerticalStrut(guiScale.space("sm")));
            java.add(changeJava);
            left.add(java);
            left.add(Box.createVerticalStrut(guiScale.space("lg")));

            JPanel diagnostics = settingsSection("Diagnostics",
                    I18n.getTextAt(BUNDLE, "LauncherFrame.diagnostics"));
            JButton logs = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.viewLogs"),
                    "viewOperationLogsButton");
            logs.setAlignmentX(Component.LEFT_ALIGNMENT);
            logs.addActionListener(event -> showOperationLogs());
            utilityButtons.add(logs);
            diagnostics.add(logs);
            diagnostics.add(Box.createVerticalStrut(guiScale.space("sm")));
            JButton report = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.reportBug"),
                    "reportBugSettingsButton");
            report.setAlignmentX(Component.LEFT_ALIGNMENT);
            report.addActionListener(event -> showBugReport());
            utilityButtons.add(report);
            diagnostics.add(report);
            left.add(diagnostics);
            if (LauncherSelfUpdate.available() || LauncherSelfUpdate.notifyOnly()) {
                left.add(Box.createVerticalStrut(guiScale.space("lg")));
                JPanel updater = settingsSection("LauncherUpdate",
                        I18n.getTextAt(BUNDLE, "LauncherFrame.launcherUpdate"));
                JButton check = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.checkForLauncherUpdate"),
                        "checkLauncherUpdateButton");
                check.setAlignmentX(Component.LEFT_ALIGNMENT);
                check.addActionListener(event -> checkLauncherUpdate(true));
                utilityButtons.add(check);
                updater.add(check);
                left.add(updater);
            }
        } else {
            JLabel unavailable = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.settingsUnreadable"));
            unavailable.setName("settingsErrorMessage");
            unavailable.setForeground(LauncherTheme.WARNING);
            unavailable.setToolTipText(launcherSettingsError == null ? null
                    : errorDetail(launcherSettingsError));
            left.add(unavailable);
            JButton retry = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.retrySettings"),
                    "retrySettingsButton");
            retry.addActionListener(event -> loadSettingsPage());
            utilityButtons.add(retry);
            left.add(retry);
        }
        Dimension utilitySize = new Dimension();
        for (JButton button : utilityButtons) {
            Dimension preferred = button.getPreferredSize();
            utilitySize.width = Math.max(utilitySize.width, preferred.width);
            utilitySize.height = Math.max(utilitySize.height, preferred.height);
        }
        for (JButton button : utilityButtons) {
            button.setPreferredSize(utilitySize);
            button.setMinimumSize(utilitySize);
            button.setMaximumSize(utilitySize);
        }
        if (!settingsLoading && launcherSettings != null) right.add(launchOptionsSection());
        JScrollPane scroll = LauncherTheme.scrollPane(center);
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

    private JPanel launchOptionsSection() {
        JPanel section = settingsSection("LaunchOptions",
                I18n.getTextAt(BUNDLE, "LauncherFrame.launchOptions"));
        launchOptionsForm = null;
        if (launchOptionsDocument == null) {
            JLabel unavailable = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.launchOptionsUnreadable"));
            unavailable.setName("launchOptionsErrorMessage");
            unavailable.setForeground(LauncherTheme.WARNING);
            unavailable.setToolTipText(launchOptionsError == null ? null : errorDetail(launchOptionsError));
            unavailable.setAlignmentX(Component.LEFT_ALIGNMENT);
            section.add(unavailable);
            return section;
        }
        JLabel intro = mutedSettingsLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.launchOptionsIntro"));
        intro.setName("launchOptionsIntro");
        section.add(intro);
        section.add(Box.createVerticalStrut(guiScale.space("md")));
        JLabel message = new JLabel(" ");
        message.setName("settingsLaunchOptionsMessage");
        message.setForeground(LauncherTheme.WARNING);
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        launchOptionsMessage = message;
        JButton revert = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.revert"),
                "revertLaunchOptionsButton");
        JButton save = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.saveLaunchOptions"),
                "saveGlobalLaunchOptionsButton");
        LaunchOptions current = launchOptionsDraft != null ? launchOptionsDraft : launchOptionsDocument.global();
        LaunchOptionsForm form = new LaunchOptionsForm(guiScale, LaunchOptions.builtIn(), current,
                I18n.getTextAt(BUNDLE, "LauncherFrame.useBuiltInDefault"), () -> {
                    message.setText(" ");
                    boolean dirty = launchOptionsDirty();
                    revert.setEnabled(dirty);
                    save.setEnabled(dirty);
                });
        form.setAlignmentX(Component.LEFT_ALIGNMENT);
        launchOptionsForm = form;
        section.add(form);
        section.add(Box.createVerticalStrut(guiScale.space("sm")));
        section.add(message);
        section.add(Box.createVerticalStrut(guiScale.space("sm")));
        boolean dirty = launchOptionsDirty();
        revert.setEnabled(dirty);
        save.setEnabled(dirty);
        revert.addActionListener(event -> {
            launchOptionsDraft = null;
            launchOptionsForm = null;
            renderSettingsPage();
        });
        save.addActionListener(event -> {
            LaunchOptions options;
            try {
                options = form.collect();
            } catch (IllegalArgumentException invalid) {
                message.setText(invalid.getMessage());
                return;
            }
            run(I18n.getTextAt(BUNDLE, "LauncherFrame.savingLaunchOptions"),
                    () -> services.saveGlobalLaunchOptions(options), saved -> {
                // Text fields stay editable while saving; keep anything changed after Save was pressed.
                LaunchOptions live = launchOptionsForm == form ? form.values() : null;
                launchOptionsDocument = saved;
                launchOptionsError = null;
                launchOptionsDraft = live != null && !live.equals(saved.global()) ? live : null;
                launchOptionsForm = null;
                status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.savedLaunchOptions"));
                if (page == Page.SETTINGS) renderSettingsPage();
            });
        });
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        actions.setName("launchOptionsActions");
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.add(revert);
        actions.add(Box.createHorizontalStrut(guiScale.space("md")));
        actions.add(save);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, actions.getPreferredSize().height));
        section.add(actions);
        return section;
    }

    private boolean launchOptionsDirty() {
        return launchOptionsForm != null && launchOptionsDocument != null
                && !launchOptionsForm.values().equals(launchOptionsDocument.global());
    }

    /** Saves the edited launch options, then leaves Settings; invalid values keep the page open. */
    private void saveLaunchOptionsThenNavigate(Page next) {
        LaunchOptions options;
        try {
            options = launchOptionsForm.collect();
        } catch (IllegalArgumentException invalid) {
            if (launchOptionsMessage != null) launchOptionsMessage.setText(invalid.getMessage());
            return;
        }
        LaunchOptionsForm form = launchOptionsForm;
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.savingLaunchOptions"),
                () -> services.saveGlobalLaunchOptions(options), saved -> {
            // Text fields stay editable while saving; an edit made since then keeps Settings open.
            LaunchOptions live = launchOptionsForm == form ? form.values() : null;
            boolean editedSince = live != null && !live.equals(saved.global());
            launchOptionsDocument = saved;
            launchOptionsError = null;
            launchOptionsDraft = editedSince ? live : null;
            launchOptionsForm = null;
            status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.savedLaunchOptions"));
            if (page != Page.SETTINGS) return;
            if (editedSince) {
                renderSettingsPage();
            } else {
                navigateTo(next);
            }
        });
    }
    private JLabel mutedSettingsLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(FirstLaunchPanel.MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JPanel appearanceSection() {
        JPanel section = settingsSection("Appearance",
                I18n.getTextAt(BUNDLE, "LauncherFrame.appearance"));
        LauncherAppearance current = LauncherAppearance.current();
        JLabel themeLabel = mutedSettingsLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.theme"));
        section.add(themeLabel);
        section.add(Box.createVerticalStrut(guiScale.space("xs")));
        StyledComboBox<LauncherAppearance.Theme> theme =
                new StyledComboBox<>(LauncherAppearance.Theme.values(), guiScale);
        theme.setName("appearanceThemeCombo");
        theme.setSelectedItem(current.theme());
        theme.getAccessibleContext().setAccessibleName(I18n.getTextAt(BUNDLE, "LauncherFrame.theme"));
        themeLabel.setLabelFor(theme);
        theme.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension themeSize = theme.getPreferredSize();
        themeSize.width = Math.max(themeSize.width, guiScale.scaleForGUI(180));
        theme.setPreferredSize(themeSize);
        theme.setMaximumSize(themeSize);
        theme.addActionListener(event -> {
            if (theme.getSelectedItem() instanceof LauncherAppearance.Theme selected) {
                applyAppearance(LauncherAppearance.current().withTheme(selected));
            }
        });
        section.add(theme);
        section.add(Box.createVerticalStrut(guiScale.space("md")));
        JCheckBox intro = new MapCheckBox(I18n.getTextAt(BUNDLE, "LauncherFrame.playIntro"),
                playIntro, guiScale);
        intro.setName("playIntroCheckbox");
        intro.setOpaque(false);
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        intro.setBorder(BorderFactory.createEmptyBorder());
        intro.setMargin(new java.awt.Insets(0, 0, 0, 0));
        intro.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.playIntroDescription"));
        intro.addActionListener(event -> applyPlayIntro(intro.isSelected()));
        section.add(intro);
        section.add(Box.createVerticalStrut(guiScale.space("md")));
        JLabel languageLabel = mutedSettingsLabel(I18n.getText("Settings.language"));
        section.add(languageLabel);
        section.add(Box.createVerticalStrut(guiScale.space("xs")));
        List<LanguageChoice> choices = new ArrayList<>();
        choices.add(new LanguageChoice(null, I18n.getText("Settings.language.system")));
        for (String tag : I18n.LOCALE_CHOICES) {
            choices.add(new LanguageChoice(tag, LanguageChoice.nativeName(tag)));
        }
        StyledComboBox<LanguageChoice> languages =
                new StyledComboBox<>(choices.toArray(LanguageChoice[]::new), guiScale);
        languages.setName("languageCombo");
        choices.stream().filter(choice -> java.util.Objects.equals(choice.tag(), language))
                .findFirst().ifPresent(languages::setSelectedItem);
        languages.getAccessibleContext().setAccessibleName(I18n.getText("Settings.language"));
        languageLabel.setLabelFor(languages);
        languages.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension languageSize = languages.getPreferredSize();
        languageSize.width = Math.max(languageSize.width, guiScale.scaleForGUI(180));
        languages.setPreferredSize(languageSize);
        languages.setMaximumSize(languageSize);
        languages.addActionListener(event -> {
            if (languages.getSelectedItem() instanceof LanguageChoice selected) {
                applyLanguage(selected.tag());
            }
        });
        section.add(languages);
        section.add(Box.createVerticalStrut(guiScale.space("xs")));
        JLabel languageNote = mutedSettingsLabel(I18n.getText("Settings.language.restart"));
        languageNote.setName("languageRestartNote");
        section.add(languageNote);
        return section;
    }

    /** A Language choice; {@code tag} is {@code null} for the system language. */
    record LanguageChoice(String tag, String label) {
        /** The language's name in that language, such as Deutsch, so players can find their own. */
        static String nativeName(String tag) {
            Locale locale = Locale.forLanguageTag(tag);
            String name = locale.getDisplayLanguage(locale);
            return name.isEmpty() ? tag : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void applyLanguage(String requested) {
        if (java.util.Objects.equals(requested, language)) return;
        language = requested;
        long revision = languageSave.request();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                languageSave.saveIfLatest(revision, requested);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (isDisplayable()) {
                        showError(LocalizedText.of("org.megamek.launcher.messages", "Settings.language.notSaved"), failure.getCause());
                    }
                }
            }
        }.execute();
    }

    private JPanel releasesSection() {
        JPanel section = settingsSection("Releases",
                I18n.getTextAt(BUNDLE, "LauncherFrame.releases"));
        JCheckBox experimental = new MapCheckBox(
                I18n.getTextAt(BUNDLE, "LauncherFrame.showExperimentalReleases"),
                showExperimental, guiScale);
        experimental.setName("showExperimentalReleasesCheckbox");
        experimental.setOpaque(false);
        experimental.setAlignmentX(Component.LEFT_ALIGNMENT);
        experimental.setBorder(BorderFactory.createEmptyBorder());
        experimental.setMargin(new java.awt.Insets(0, 0, 0, 0));
        experimental.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.showExperimentalReleasesDescription"));
        experimental.addActionListener(event -> {
            boolean requested = experimental.isSelected();
            if (requested && !showExperimental && !LauncherAlertDialog.showConfirm(this, guiScale,
                    EXPERIMENTAL_CONFIRM_TITLE, EXPERIMENTAL_CONFIRM_MESSAGE,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.showExperimental"))) {
                experimental.setSelected(false);
                return;
            }
            applyShowExperimental(requested);
        });
        section.add(experimental);
        section.add(Box.createVerticalStrut(guiScale.space("xs")));
        JLabel warning = mutedSettingsLabel(
                I18n.getTextAt(BUNDLE, "LauncherFrame.experimentalWarning"));
        warning.setName("experimentalReleasesWarning");
        warning.setForeground(LauncherTheme.WARNING);
        section.add(warning);
        return section;
    }

    /** Changes only what is offered; the quick-install catalog is reloaded on next use. */
    private void applyShowExperimental(boolean requested) {
        if (requested == showExperimental) return;
        showExperimental = requested;
        cancelFirstLaunchOptionsWorker();
        long revision = showExperimentalSave.request();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                showExperimentalSave.saveIfLatest(revision, requested);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (isDisplayable()) {
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.releaseSettingNotSaved"),
                                failure.getCause());
                    }
                }
            }
        }.execute();
    }

    private void applyPlayIntro(boolean requested) {
        if (requested == playIntro) return;
        playIntro = requested;
        curtain.setIntro(requested);
        long revision = playIntroSave.request();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                playIntroSave.saveIfLatest(revision, requested);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (isDisplayable()) {
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.introSettingNotSaved"),
                                failure.getCause());
                    }
                }
            }
        }.execute();
    }

    private void applyAppearance(LauncherAppearance requested) {
        if (requested.equals(LauncherAppearance.current())) return;
        LauncherAppearance.install(requested);
        renderCurrentPage();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                synchronized (appearanceSaveLock) {
                    services.saveAppearance(LauncherAppearance.current());
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (isDisplayable()) {
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.appearanceNotSaved"),
                                failure.getCause());
                    }
                }
            }
        }.execute();
    }

    private void openInstallationLaunchOptions(InstallationRecord record) {
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.readingLaunchOptions"), services::launchOptions, document -> {
            LaunchOptions inherited = document.global().over(LaunchOptions.builtIn());
            LaunchOptionsDialog[] dialog = new LaunchOptionsDialog[1];
            dialog[0] = new LaunchOptionsDialog(this, guiScale,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.launchOptions"),
                    I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.launchOptionsFor",
                            installationDisplayName(record)),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.launchOptionsOverride"),
                    inherited, document.installation(record.id()),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.useLauncherDefault"),
                    options -> run(I18n.getTextAt(BUNDLE, "LauncherFrame.savingLaunchOptions"),
                            () -> services.saveInstallationLaunchOptions(record, options), saved -> {
                                launchOptionsDocument = saved;
                                status.setText(I18n.getFormattedTextAt(BUNDLE,
                                        "LauncherFrame.savedLaunchOptionsFor",
                                        installationDisplayName(record)));
                            }),
                    options -> copyLaunchCommands(record, options, dialog[0]));
            dialog[0].show();
        });
    }

    /** Copies the commands that would start each application with the dialog's unsaved {@code overrides}. */
    private void copyLaunchCommands(InstallationRecord record, LaunchOptions overrides,
                                    LaunchOptionsDialog dialog) {
        List<Product> products = record.products() == null ? List.of() : record.products();
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.preparingLaunchCommands"), () -> {
            StringBuilder commands = new StringBuilder();
            for (Product product : products) {
                List<String> command = services.preview(record, product.key(), overrides);
                commands.append(displayProduct(product.key())).append(':').append(System.lineSeparator());
                commands.append(String.join(" ", command.stream().map(LauncherFrame::quoteArgument).toList()))
                        .append(System.lineSeparator()).append(System.lineSeparator());
            }
            return commands.toString().strip();
        }, text -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.copiedLaunchCommands"));
            dialog.note(I18n.getTextAt(BUNDLE, "LauncherFrame.copiedLaunchCommandsToClipboard"));
        });
    }

    static String quoteArgument(String argument) {
        if (!argument.isEmpty() && argument.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"')) {
            return argument;
        }
        return '"' + argument.replace("\"", "\\\"") + '"';
    }

    /**
     * Keeps the home navigation centered with link groups at its left and right edges, and stacks both groups below the
     * navigation, side by side, when the deck is too narrow for all three on one line.
     */
    private static final class NavigationRow extends JPanel {
        private final java.awt.Container navigation;
        private final java.awt.Container leading;
        private final java.awt.Container trailing;
        private final int gap;
        private boolean stacked;

        NavigationRow(java.awt.Container navigation, java.awt.Container leading, java.awt.Container trailing,
                      int gap) {
            super(null);
            this.navigation = navigation;
            this.leading = leading;
            this.trailing = trailing;
            this.gap = gap;
            setOpaque(false);
            add(leading);
            add(navigation);
            add(trailing);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (stacked != shouldStack(rowWidth())) {
                        revalidate();
                        repaint();
                    }
                }
            });
        }

        /**
         * The home deck is measured at its new width before this row is resized, so follow the parent's inner width
         * (the row always fills it).
         */
        private int rowWidth() {
            java.awt.Container parent = getParent();
            if (parent != null && parent.getWidth() > 0) {
                java.awt.Insets insets = parent.getInsets();
                return parent.getWidth() - insets.left - insets.right;
            }
            return getWidth();
        }

        /** The width each side of the navigation needs, so the navigation stays centered between equal margins. */
        private int side() {
            return Math.max(leading.getPreferredSize().width, trailing.getPreferredSize().width);
        }

        /** Both link groups side by side, as they sit below the navigation when stacked. */
        private Dimension stackedLinks() {
            Dimension left = leading.getPreferredSize();
            Dimension right = trailing.getPreferredSize();
            return new Dimension(left.width + gap + right.width, Math.max(left.height, right.height));
        }

        private boolean shouldStack(int width) {
            if (width <= 0) return false;
            java.awt.Insets insets = getInsets();
            int available = width - insets.left - insets.right;
            return navigation.getPreferredSize().width + 2 * (side() + gap) > available;
        }

        @Override
        public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) return super.getPreferredSize();
            Dimension nav = navigation.getPreferredSize();
            Dimension links = stackedLinks();
            java.awt.Insets insets = getInsets();
            boolean stack = shouldStack(rowWidth());
            int width = stack ? Math.max(nav.width, links.width) : nav.width + 2 * (side() + gap);
            int height = stack ? nav.height + gap + links.height : Math.max(nav.height, links.height);
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            java.awt.Insets insets = getInsets();
            return new Dimension(Math.max(navigation.getPreferredSize().width, stackedLinks().width)
                    + insets.left + insets.right, getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void doLayout() {
            java.awt.Insets insets = getInsets();
            int x = insets.left;
            int y = insets.top;
            int width = Math.max(0, getWidth() - insets.left - insets.right);
            int height = Math.max(0, getHeight() - insets.top - insets.bottom);
            Dimension nav = navigation.getPreferredSize();
            Dimension left = leading.getPreferredSize();
            Dimension right = trailing.getPreferredSize();
            stacked = shouldStack(getWidth());
            if (stacked) {
                int navWidth = Math.min(width, nav.width);
                navigation.setBounds(x + (width - navWidth) / 2, y, navWidth, nav.height);
                Dimension links = stackedLinks();
                int linksX = x + Math.max(0, (width - links.width) / 2);
                int linksY = y + nav.height + gap;
                leading.setBounds(linksX, linksY + (links.height - left.height) / 2, left.width, left.height);
                int rightX = Math.min(linksX + left.width + gap, x + width - right.width);
                trailing.setBounds(rightX, linksY + (links.height - right.height) / 2, right.width, right.height);
            } else {
                navigation.setBounds(x + (width - nav.width) / 2, y + (height - nav.height) / 2,
                        nav.width, nav.height);
                leading.setBounds(x, y + (height - left.height) / 2, left.width, left.height);
                trailing.setBounds(x + width - right.width, y + (height - right.height) / 2,
                        right.width, right.height);
            }
        }
    }

    /** {@code navigation} centered, with the help links on its left and the community links on its right. */
    private JPanel homeNavigationRow(JPanel navigation) {
        JPanel row = new NavigationRow(navigation, linkGroup("helpLinks", helpLinks()),
                linkGroup("communityLinks", communityLinks()), guiScale.space("md"));
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        return row;
    }

    /** Empty Home has no installations to manage, but Settings must stay reachable. */
    private JPanel firstLaunchNavigation() {
        JButton settings = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.settings"), "settingsButton");
        settings.setMnemonic(java.awt.event.KeyEvent.VK_S);
        settings.addActionListener(event -> navigateTo(Page.SETTINGS));
        JPanel navigation = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        navigation.setName("firstLaunchNavigation");
        navigation.setOpaque(false);
        navigation.add(settings);
        JPanel row = homeNavigationRow(navigation);
        row.setName("firstLaunchNavigationRow");
        return row;
    }

    /** "Release notes", when Home has an installation to describe, and the bug report helper. */
    private List<JButton> helpLinks() {
        List<JButton> links = new ArrayList<>();
        if (!homeWhatsNewInstallations().isEmpty()) {
            JButton whatsNew = new LauncherLink(I18n.getTextAt(BUNDLE, "LauncherFrame.releaseNotes"),
                    "whatsNewButton", guiScale);
            whatsNew.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.releaseNotesDescription"));
            whatsNew.addActionListener(event -> showHomeWhatsNew());
            links.add(whatsNew);
        }
        JButton report = new LauncherLink(I18n.getTextAt(BUNDLE, "LauncherFrame.reportBug"),
                "reportBugButton", guiScale);
        report.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.reportBugDescription"));
        report.addActionListener(event -> showBugReport());
        links.add(report);
        return links;
    }

    private List<JButton> communityLinks() {
        JButton discord = new LauncherLink(I18n.getTextAt(BUNDLE, "LauncherFrame.joinDiscord"),
                "openDiscordButton", guiScale);
        discord.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.joinDiscordDescription"));
        discord.addActionListener(event -> openWebsite(
                java.net.URI.create(DISCORD_INVITE_URL), "Discord"));
        JButton online = new LauncherLink(I18n.getTextAt(BUNDLE, "LauncherFrame.playOnline"),
                "playOnlineButton", guiScale);
        online.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.playOnlineDescription"));
        online.addActionListener(event -> openWebsite(
                java.net.URI.create(PLAY_ONLINE_URL), "megamek.games"));
        return List.of(discord, online);
    }

    /** One row of links, vertically centered on the navigation buttons beside it. */
    private JPanel linkGroup(String name, List<JButton> buttons) {
        JPanel links = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER,
                guiScale.space("lg"), 0));
        links.setName(name);
        links.setOpaque(false);
        for (JButton button : buttons) links.add(button);
        JPanel group = new JPanel(new java.awt.GridBagLayout());
        group.setOpaque(false);
        group.add(links);
        return group;
    }

    private void openWebsite(java.net.URI address, String description) {
        run(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.openingWebsite", description), () -> {
            new PlatformOpener().browse(address);
            return null;
        }, ignored -> {});
    }

    private JPanel settingsSection(String id, String titleText) {
        JPanel section = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        section.setName("settings" + id + "Section");
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setOpaque(false);
        section.setBorder(guiScale.padding("lg", "xl"));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel(titleText);
        title.setForeground(FirstLaunchPanel.ACCENT);
        title.setFont(guiScale.font(title.getFont(), "title"));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(title);
        section.add(Box.createVerticalStrut(guiScale.space("md")));
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
                    gap = scale.space("lg");
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
        return services.registry().toAbsolutePath().getParent().resolve(LauncherSelfUpdate.reportFileName());
    }

    private void checkLauncherUpdate(boolean explicit) {
        if (LauncherSelfUpdate.notifyOnly()) {
            checkLauncherRelease(explicit);
            return;
        }
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.checkingLauncherRelease"), () -> services.checkLauncherUpdate(
                LauncherSelfUpdate.consumeReport(launcherUpdateReport(), LauncherSelfUpdate.currentVersion()),
                launcherUpdater::check), checked -> curtain.whenUncovered(() -> {
            if (checked.pending()) {
                setLauncherUpdateStatus(explicit, checked.message());
                if (explicit) LauncherAlertDialog.showMessage(this, guiScale,
                        I18n.getTextAt(BUNDLE, "LauncherFrame.launcherUpdateInProgress"), checked.message());
                return;
            }
            LauncherSelfUpdate.Candidate candidate = checked.candidate();
            if (candidate == null) {
                setLauncherUpdateStatus(explicit,
                        checked.message() == null
                                ? I18n.getTextAt(BUNDLE, "LauncherFrame.launcherUpToDate") : checked.message());
                if (checked.message() != null) LauncherAlertDialog.showMessage(this, guiScale,
                        I18n.getTextAt(BUNDLE, "LauncherFrame.launcherUpdateResult"), checked.message());
                return;
            }
            if (!confirmLauncherUpdate(this, guiScale, candidate, checked.message())) return;
            OperationProgressDialog progress = new OperationProgressDialog(this,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.updatingLauncher"), "launcherUpdateProgressLog",
                    this::showOperationLogs);
            progress.setVisible(true);
            runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.updatingLauncher"), OperationType.LAUNCHER_UPDATE, List.of(), progress, context -> {
                Path installer = launcherUpdater.stage(candidate, context);
                try {
                    context.phase(org.megamek.launcher.operation.OperationPhase.APPLY,
                            "Closing the launcher to start installation. Diagnostics: "
                                    + LauncherSelfUpdate.helperLog(launcherUpdateReport()) + " and "
                                    + LauncherSelfUpdate.installerLog(launcherUpdateReport()));
                    context.enterFinalization("Installer handoff has started.");
                    launcherUpdater.handoff(installer, candidate.version(), candidate.sha256(), launcherUpdateReport());
                    return null;
                } catch (Exception error) {
                    try {
                        launcherUpdater.discard(installer);
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
        }), error -> {
            if (toolsUnavailable(error)) {
                checkLauncherRelease(explicit);
                return;
            }
            if (!explicit) {
                setLauncherUpdateStatus(false,
                        I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.launcherUpdateStatus",
                                SanitizedErrors.text(error.getLocalizedMessage())));
                recordErrorAsync("Automatic launcher update check", error, ignored -> {});
            }
        }, () -> {}, explicit, explicit);
    }

    static boolean toolsUnavailable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof LauncherSelfUpdate.InstallerToolsUnavailableException) return true;
        }
        return false;
    }

    /** Copies that can't update in place link a newer release to its page instead. */
    private void checkLauncherRelease(boolean explicit) {
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.checkingLauncherRelease"), launcherUpdater::checkRelease,
                release -> curtain.whenUncovered(() -> {
            if (release == null) {
                setLauncherUpdateStatus(explicit, I18n.getTextAt(BUNDLE, "LauncherFrame.launcherUpToDate"));
                return;
            }
            setLauncherUpdateStatus(explicit, I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.launcherAvailable",
                    String.valueOf(release.version())));
            if (confirmLauncherDownload(this, guiScale, release)) {
                openWebsite(release.page(), I18n.getTextAt(BUNDLE, "LauncherFrame.launcherReleasePage"));
            }
        }), error -> {
            if (!explicit) {
                setLauncherUpdateStatus(false,
                        I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.launcherUpdateStatus",
                                SanitizedErrors.text(error.getLocalizedMessage())));
                recordErrorAsync("Automatic launcher update check", error, ignored -> {});
            }
        }, () -> {}, explicit, explicit);
    }

    static boolean confirmLauncherDownload(Window owner, GuiScale scale, LauncherSelfUpdate.Release release) {
        return LauncherAlertDialog.showConfirm(owner, scale,
                I18n.getTextAt(BUNDLE, "LauncherFrame.updateMegaMekLauncher"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.launcherDownloadPrompt",
                        String.valueOf(release.version())),
                I18n.getTextAt(BUNDLE, "LauncherFrame.openReleasePage"));
    }

    /**
     * The automatic check races empty Home's version check on the shared status line; it must
     * not replace guidance the player still needs to act on.
     */
    private void setLauncherUpdateStatus(boolean explicit, String text) {
        if (!explicit && isCurrentEmptyFirstLaunch() && firstLaunchMetadataFailure != null
                && !firstLaunchNothingReleased) {
            return;
        }
        status.setText(text);
    }

    static boolean confirmLauncherUpdate(Window owner, GuiScale scale,
                                          LauncherSelfUpdate.Candidate candidate, String notice) {
        return LauncherAlertDialog.showConfirm(owner, scale,
                I18n.getTextAt(BUNDLE, "LauncherFrame.updateMegaMekLauncher"),
                launcherUpdatePrompt(candidate.version(), notice,
                        LauncherSelfUpdate.asksForAdministratorPassword()),
                I18n.getTextAt(BUNDLE, "LauncherFrame.updateNow"));
    }

    static String launcherUpdatePrompt(String version, String notice, boolean asksForPassword) {
        String message = I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.launcherUpdatePrompt",
                String.valueOf(version));
        if (asksForPassword) {
            message += "\n\n" + I18n.getTextAt(BUNDLE, "LauncherFrame.launcherUpdateAdministratorPassword");
        }
        return notice == null ? message : notice + "\n\n" + message;
    }

    private void chooseDefaultJava() {
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.findingJavaRuntimes"), services::javaCandidates,
                this::chooseDefaultJavaFromCandidates);
    }

    private void chooseDefaultJavaFromCandidates(List<Path> candidates) {
        showJavaSelectionDialog(I18n.getTextAt(BUNDLE, "LauncherFrame.changeDefaultJava"),
                "defaultJavaCandidateCombo",
                candidates, selected -> run(I18n.getTextAt(BUNDLE, "LauncherFrame.validatingDefaultJava"),
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
        choices.addItem(I18n.getTextAt(BUNDLE, "LauncherFrame.browseForJava"));
        choices.setName(comboName);
        choices.getAccessibleContext().setAccessibleName(I18n.getTextAt(BUNDLE, "LauncherFrame.javaRuntime"));
        choices.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "LauncherFrame.javaRuntimeDescription"));

        JDialog dialog = new JDialog(this, title, false);
        dialog.setName("javaSelectionDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(guiScale.space("lg"),
                guiScale.space("lg")));
        body.setName("javaSelectionDialogContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(guiScale.padding("xl", "xxl"));
        JLabel heading = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.chooseJava21"));
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(guiScale.font(heading.getFont(), "headline"));
        body.add(heading, BorderLayout.NORTH);
        body.add(choices, BorderLayout.CENTER);

        JButton cancel = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.cancel"), "cancelJavaSelectionButton");
        cancel.addActionListener(event -> dialog.dispose());
        JButton use = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.useSelectedJava"),
                "confirmJavaSelectionButton", true);
        use.addActionListener(event -> {
            Object choice = choices.getSelectedItem();
            dialog.dispose();
            if (choice instanceof Path path) {
                selection.accept(path);
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(I18n.getTextAt(BUNDLE, "LauncherFrame.chooseJavaHome"));
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                selection.accept(chooser.getSelectedFile().toPath());
            }
        });
        DialogFooter footer = new DialogFooter(guiScale).primary(use).cancel(cancel);
        body.add(footer.panel(), BorderLayout.SOUTH);
        dialog.setContentPane(body);
        footer.install(dialog, dialog::dispose);
        packToWidth(dialog, "dialog.lg");
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        choices.requestFocusInWindow();
    }

    void launchDirectly(InstallationRecord record, String productKey) {
        if (!gate.tryEnter()) {
            LauncherAlertDialog.showMessage(this, guiScale, I18n.getTextAt(BUNDLE, "LauncherFrame.pleaseWait"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.anotherOperationRunning"));
            return;
        }
        InstallationRecord capturedRecord = record;
        String capturedProduct = productKey;
        int restoreState = getExtendedState() & ~JFrame.ICONIFIED;
        long launch = ++launchGeneration;
        status.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.running", displayProduct(capturedProduct)));
        setActionsEnabled(false);
        // The window stays up behind the "Launching" card until the application reports that it
        // is on screen (or could not say so), then gets out of its way.
        LaunchObserver observer = () -> SwingUtilities.invokeLater(() -> applicationReady(launch));
        curtain.closeForLaunch(displayProduct(capturedProduct));
        SwingWorker<Integer, Void> launchWorker = new SwingWorker<>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return services.launch(capturedRecord, capturedProduct, observer);
            }

            @Override
            protected void done() {
                if (!gate.isBusy()) setActionsEnabled(true);
                settledLaunch = Math.max(settledLaunch, launch);
                boolean current = launch == launchGeneration;
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
                    // A minimised window opens from the line when the player brings it back.
                    if (current && (getExtendedState() & JFrame.ICONIFIED) == 0) curtain.reveal();
                    return;
                }
                setExtendedState(restoreState);
                setVisible(true);
                toFront();
                if (current) curtain.reveal();
                if (problem instanceof UninstallService.MissingInstallationException missing) {
                    status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.installationFolderNotFound"));
                    removeMissingInstallation(missing.record());
                    return;
                }
                if (problem != null) {
                    showError(LocalizedText.of(BUNDLE, "LauncherFrame.launchingFailed",
                                    displayProduct(capturedProduct)), problem);
                } else {
                    int code = exit == null ? -1 : exit;
                    showError(LocalizedText.of(BUNDLE, "LauncherFrame.gameExitedWithCode",
                                    String.valueOf(code)),
                            new IOException("The selected program returned nonzero "
                                    + "exit code " + code));
                }
                status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.launchFailedDetailsShown"));
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
            settledLaunch = Math.max(settledLaunch, launch);
            curtain.hideCurtain();
            setExtendedState(restoreState);
            setVisible(true);
            toFront();
            showError(LocalizedText.of(BUNDLE, "LauncherFrame.launchingFailed",
                    displayProduct(capturedProduct)), error);
            status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.launchFailedDetailsShown"));
        }
    }

    /**
     * Minimises the launcher once launch {@code launch} is on screen, after its transition has
     * played, unless the launch has already ended or a newer one has started.
     */
    private void applicationReady(long launch) {
        if (launch != launchGeneration || launch <= settledLaunch || !isDisplayable()) return;
        long wait = curtain.untilLaunchShown();
        if (wait > 0) {
            javax.swing.Timer later = new javax.swing.Timer((int) wait,
                    event -> applicationReady(launch));
            later.setRepeats(false);
            later.start();
            return;
        }
        try {
            setExtendedState(getExtendedState() | JFrame.ICONIFIED);
        } catch (RuntimeException error) {
            // The window cannot be minimised here, so give the player the launcher back.
            curtain.reveal();
        }
    }

    void reloadAfterSuccessfulGameExit(String productKey) {
        // An unrelated operation may have acquired the gate after launch started.
        // Its completion owns the UI; attempting reload() here would show a
        // misleading "Please wait" dialog and clear the current page.
        if (gate.isBusy()) return;
        status.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.productExited", displayProduct(productKey)));
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
            showError(LocalizedText.of(BUNDLE, "LauncherFrame.preparingFailed",
                    quickChoiceName(repository, channel)), error);
            resumeFirstLaunchOptions();
            return;
        }
        run(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.checkingChoice", quickChoiceName(repository, channel)),
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
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.preparingInstall"),
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
        String openSettings = I18n.getTextAt(BUNDLE, "LauncherFrame.openSettings");
        String retry = I18n.getTextAt(BUNDLE, "LauncherFrame.retry");
        String[] choices = javaProblem
                ? new String[]{openSettings, retry, LauncherAlertDialog.CANCEL}
                : new String[]{retry, CHANGE_LOCATION, LauncherAlertDialog.CANCEL};
        String answer = LauncherAlertDialog.show(this,
                I18n.getTextAt(BUNDLE, javaProblem
                        ? "LauncherFrame.javaSetupRequired" : "LauncherFrame.couldNotPrepareDownload"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.planFailureMessage",
                        javaProblem
                                ? I18n.getTextAt(BUNDLE, "LauncherFrame.javaNotVerified")
                                : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.couldNotBePrepared",
                                        quickChoiceName(key.repository(), key.channel())),
                        detail),
                guiScale, choices);
        if (javaProblem && openSettings.equals(answer)) {
            navigateTo(Page.SETTINGS);
        } else if (retry.equals(answer)) {
            prepareCapturedNormalInstall(option, target);
        } else if (CHANGE_LOCATION.equals(answer)) {
            Path changed = chooseLocationAfterFailure(this, target, key.repository(),
                    key.channel(), option.version());
            if (changed == null) {
                resumeFirstLaunchOptions();
            } else {
                prepareCapturedNormalInstall(option, changed);
            }
        } else {
            resumeFirstLaunchOptions();
        }
    }

    private void normalPlanFailure(OfficialRepository repository, FollowChannel channel,
                                   Path target, Throwable error) {
        String detail = errorDetail(error);
        boolean javaProblem = detail.toLowerCase(java.util.Locale.ROOT).contains("java");
        String openSettings = I18n.getTextAt(BUNDLE, "LauncherFrame.openSettings");
        String retry = I18n.getTextAt(BUNDLE, "LauncherFrame.retry");
        String[] choices = javaProblem
                ? new String[]{openSettings, retry, LauncherAlertDialog.CANCEL}
                : new String[]{retry, CHANGE_LOCATION, LauncherAlertDialog.CANCEL};
        String answer = LauncherAlertDialog.show(this,
                I18n.getTextAt(BUNDLE, javaProblem
                        ? "LauncherFrame.javaSetupRequired" : "LauncherFrame.couldNotPrepareDownload"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.planFailureMessage",
                        javaProblem
                                ? I18n.getTextAt(BUNDLE, "LauncherFrame.javaNotVerified")
                                : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.couldNotBeChecked",
                                        quickChoiceName(repository, channel)),
                        detail),
                guiScale, choices);
        if (javaProblem && openSettings.equals(answer)) {
            navigateTo(Page.SETTINGS);
        } else if (retry.equals(answer)) {
            prepareNormalInstall(repository, channel, target);
        } else if (CHANGE_LOCATION.equals(answer)) {
            Path changed = chooseLocationAfterFailure(this, target, repository, channel, null);
            if (changed == null) {
                resumeFirstLaunchOptions();
            } else {
                prepareNormalInstall(repository, channel, changed);
            }
        } else {
            resumeFirstLaunchOptions();
        }
    }

    /** The exact-release counterpart of {@link #normalPlanFailure}; Retry reruns the same plan. */
    private void exactPlanFailure(Component owner, OfficialRepository repository,
                                  FollowChannel channel, String tag, String source,
                                  Path target, Throwable error, Runnable retry) {
        Window window = owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner);
        String retryLabel = I18n.getTextAt(BUNDLE, "LauncherFrame.retry");
        String answer = LauncherAlertDialog.show(window == null ? this : window,
                I18n.getTextAt(BUNDLE, "LauncherFrame.couldNotPrepareDownload"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.planFailureMessage",
                        I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.releaseCouldNotBeChecked",
                                displayProduct(repository.key()), tag),
                        errorDetail(error)),
                guiScale, retryLabel, CHANGE_LOCATION, LauncherAlertDialog.CANCEL);
        if (retryLabel.equals(answer)) {
            retry.run();
        } else if (CHANGE_LOCATION.equals(answer)) {
            Path changed = chooseLocationAfterFailure(owner, target, repository, channel, tag);
            if (changed != null) {
                if (window != null && window != this) window.dispose();
                prepareExactNormalInstall(repository, channel, tag, source, changed);
            }
        }
    }

    /** Lets the user pick another folder when the suggested one could not be planned. */
    private Path chooseLocationAfterFailure(Component owner, Path target,
                                            OfficialRepository repository,
                                            FollowChannel channel, String version) {
        try {
            return normalInstallLocationPrompts.choose(owner,
                    target == null ? null : target.getParent(),
                    NormalInstallService.defaultFolderName(repository, channel));
        } catch (IOException error) {
            showError(LocalizedText.of(BUNDLE, "LauncherFrame.preparingInstallLocationFailed"), error);
            return null;
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
                        String typed = source.chosenDestination() == null
                                ? NormalInstallService.defaultFolderName(
                                        plan.repository(), plan.channel())
                                : source.folderName();
                        changed = normalInstallLocationPrompts.choose(source,
                                plan.destination().getParent(), typed);
                    } catch (IOException error) {
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.preparingInstallLocationFailed"),
                                error);
                        return false;
                    }
                    if (changed == null) return false;
                    continuing[0] = true;
                    source.dispose();
                    if (plan.capturedCurrentTarget()) {
                        QuickInstallOption captured = capturedOption(plan);
                        if (captured == null) {
                            showError(LocalizedText.of(BUNDLE,
                                            "LauncherFrame.preparingChangedInstallLocationFailed"),
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
                    Path chosen = source.chosenDestination();
                    if (chosen == null) return false;
                    if (chosen.equals(plan.destination())) {
                        continuing[0] = true;
                        source.dispose();
                        runNormalInstall(plan);
                        return true;
                    }
                    // The recheck reads the registry and the destination, which may be slow
                    // network or removable storage; the quote stays locked until it finishes.
                    new SwingWorker<NormalInstallService.Plan, Void>() {
                        @Override
                        protected NormalInstallService.Plan doInBackground() throws Exception {
                            return services.retargetNormalInstall(plan, chosen);
                        }

                        @Override
                        protected void done() {
                            if (!source.isDisplayable()) return;
                            NormalInstallService.Plan confirmed;
                            try {
                                confirmed = get();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                source.showFolderProblem(I18n.getTextAt(BUNDLE,
                                        "LauncherFrame.installFolderCheckInterrupted"));
                                return;
                            } catch (java.util.concurrent.ExecutionException failure) {
                                source.showFolderProblem(errorDetail(failure.getCause()));
                                return;
                            }
                            continuing[0] = true;
                            source.dispose();
                            runNormalInstall(confirmed);
                        }
                    }.execute();
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
        String operationDescription = I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.installingRelease", release);
        OperationProgressDialog progress = new OperationProgressDialog(this,
                operationDescription, "normalInstallProgressLog", this::showOperationLogs);
        progress.append("Installing the confirmed official " + product + " "
                + plan.channel() + " release; this copy will keep receiving "
                + plan.channel() + " updates...\n");
        JButton repair = button(I18n.getTextAt(BUNDLE, "LauncherFrame.openInstallations"),
                "repairNormalInstallButton");
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
                    progress.setHeading(error instanceof
                            NormalInstallService.PublishedInstallationException
                            ? I18n.getTextAt(BUNDLE, "LauncherFrame.copyRetainedNeedsRepair")
                            : I18n.getTextAt(BUNDLE, "LauncherFrame.installationFailed"));
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

    private String channelChoiceDescription() {
        return showExperimental
                ? I18n.getTextAt(BUNDLE, "LauncherFrame.channelChoiceWithExperimental")
                : I18n.getTextAt(BUNDLE, "LauncherFrame.channelChoiceCoreOnly");
    }

    private final class ReleasePickerDialog {
        private final StyledComboBox<OfficialRepository> product = new StyledComboBox<>(
                new OfficialRepository[]{OfficialRepository.MEKHQ,
                        OfficialRepository.MEGAMEK, OfficialRepository.LAB}, guiScale);
        private final StyledComboBox<FollowChannel> channel = new StyledComboBox<>(
                FollowChannel.offered(showExperimental).toArray(FollowChannel[]::new), guiScale);
        private final DefaultListModel<PickerReleaseChoice> model = new DefaultListModel<>();
        private final JList<PickerReleaseChoice> releases = new JList<>(model);
        private final JButton fetch = homeButton(
                I18n.getTextAt(BUNDLE, "LauncherFrame.fetchReleases"), "fetchReleasesButton");
        private final JButton previous = homeButton(
                I18n.getTextAt(BUNDLE, "LauncherFrame.previousPage"), "previousReleasePageButton");
        private final JButton next = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.nextPage"),
                "nextReleasePageButton");
        private final JButton install = homeButton(
                I18n.getTextAt(BUNDLE, "LauncherFrame.install"), "installReleaseButton", true);
        private final JButton cancel = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.cancel"),
                "cancelReleasePickerButton");
        private final JLabel pageIndicator = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.pageNone"));
        private final JLabel requestStatus = new JLabel(" ");
        private final JDialog dialog;
        private final DialogFooter footer;
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
            product.getAccessibleContext().setAccessibleName(I18n.getTextAt(BUNDLE, "LauncherFrame.product"));
            product.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.productDescription"));
            channel.setName("downloadChannelCombo");
            channel.setSelectedItem(FollowChannel.CORE);
            channel.setToolTipText(channelChoiceDescription());
            channel.getAccessibleContext().setAccessibleName(I18n.getTextAt(BUNDLE, "LauncherFrame.releaseType"));
            channel.getAccessibleContext().setAccessibleDescription(I18n.getFormattedTextAt(BUNDLE,
                    "LauncherFrame.releaseTypeDescription", channelChoiceDescription()));

            releases.setName("releaseList");
            releases.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            releases.setBackground(FirstLaunchPanel.PANEL);
            releases.setForeground(FirstLaunchPanel.TEXT);
            releases.setSelectionBackground(LauncherTheme.SELECTION_BACKGROUND);
            releases.setSelectionForeground(
                    LauncherTheme.TEXT);
            releases.setFixedCellHeight(guiScale.size("control.sm"));
            releases.setFont(guiScale.font(releases.getFont(), "body"));
            releases.setBorder(guiScale.padding("sm", "sm"));
            releases.getAccessibleContext().setAccessibleName(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.availableReleases"));
            releases.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.availableReleasesDescription"));
            releases.setCellRenderer((list, value, index, selected, focused) -> {
                JLabel label = new JLabel(value == null ? "" : value.toString());
                label.setOpaque(true);
                label.setEnabled(value != null && value.assessment().eligible());
                label.setFont(guiScale.font(label.getFont(), "body"));
                label.setBorder(guiScale.padding("xs", "lg"));
                label.setBackground(selected
                        ? LauncherTheme.SELECTION_BACKGROUND : FirstLaunchPanel.PANEL);
                label.setForeground(selected
                        ? LauncherTheme.TEXT
                        : value != null && !value.assessment().eligible()
                        ? FirstLaunchPanel.MUTED : FirstLaunchPanel.TEXT);
                if (value != null && !value.assessment().eligible()) {
                    label.setToolTipText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.unavailableTooltip",
                            SanitizedErrors.text(value.assessment().reason())));
                    label.getAccessibleContext().setAccessibleDescription(
                            I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.unavailableDescription",
                                    SanitizedErrors.text(value.assessment().reason())));
                }
                return label;
            });

            fetch.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.fetchReleasesDescription"));
            previous.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.previousPageDescription"));
            next.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.nextPageDescription"));
            install.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.installReleaseDescription"));
            previous.setEnabled(false);
            next.setEnabled(false);
            install.setEnabled(false);

            JPanel selectors = new JPanel(new java.awt.GridBagLayout());
            selectors.setOpaque(false);
            java.awt.GridBagConstraints selectorCell = new java.awt.GridBagConstraints();
            selectorCell.gridy = 0;
            selectorCell.insets = new java.awt.Insets(0, 0, 0, guiScale.space("md"));
            selectorCell.anchor = java.awt.GridBagConstraints.LINE_START;
            JLabel productLabel = styledPickerLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.productLabel"));
            productLabel.setLabelFor(product);
            selectorCell.gridx = 0;
            selectorCell.weightx = 0;
            selectorCell.fill = java.awt.GridBagConstraints.NONE;
            selectors.add(productLabel, selectorCell);
            selectorCell.gridx = 1;
            selectorCell.weightx = 0.5;
            selectorCell.fill = java.awt.GridBagConstraints.HORIZONTAL;
            selectors.add(product, selectorCell);
            JLabel channelLabel = styledPickerLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.releaseTypeLabel"));
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
            JLabel title = styledPickerLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.installAnotherVersion"));
            title.setName("installAnotherVersionHeading");
            title.setForeground(FirstLaunchPanel.ACCENT);
            title.setFont(guiScale.font(title.getFont(), "headline"));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            selectors.setAlignmentX(Component.LEFT_ALIGNMENT);
            heading.add(title);
            heading.add(Box.createVerticalStrut(guiScale.space("md")));
            JLabel coreInfo = new JLabel(CORE_RELEASE_INFO);
            coreInfo.setName("releaseTypeCoreInfo");
            coreInfo.setForeground(FirstLaunchPanel.TEXT);
            coreInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
            heading.add(coreInfo);
            if (showExperimental) {
                heading.add(Box.createVerticalStrut(guiScale.space("xs")));
                JLabel experimentalInfo = new JLabel(EXPERIMENTAL_RELEASE_INFO);
                experimentalInfo.setName("releaseTypeExperimentalInfo");
                experimentalInfo.setForeground(LauncherTheme.WARNING);
                experimentalInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
                heading.add(experimentalInfo);
            }
            heading.add(Box.createVerticalStrut(guiScale.space("lg")));
            heading.add(selectors);

            JScrollPane scroll = LauncherTheme.scrollPane(releases);
            scroll.setName("releaseListScrollPane");
            scroll.setBorder(BorderFactory.createLineBorder(
                    LauncherTheme.BORDER));
            scroll.getViewport().setBackground(FirstLaunchPanel.PANEL);

            // Paging belongs to the list; the footer holds only the dialog's own choices.
            JPanel paging = new JPanel(new java.awt.FlowLayout(
                    java.awt.FlowLayout.TRAILING, guiScale.space("md"), 0));
            paging.setName("releasePagingActions");
            paging.setOpaque(false);
            paging.add(previous);
            pageIndicator.setName("releasePageIndicator");
            pageIndicator.setForeground(FirstLaunchPanel.TEXT);
            pageIndicator.setFont(guiScale.font(pageIndicator.getFont(), "label"));
            pageIndicator.getAccessibleContext().setAccessibleName(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.releasePage"));
            paging.add(pageIndicator);
            paging.add(next);
            cancel.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.closeWithoutInstalling"));
            footer = new DialogFooter(guiScale).primary(install).cancel(cancel);
            JPanel resultActions = new JPanel(new BorderLayout(0, guiScale.space("xl")));
            resultActions.setOpaque(false);
            resultActions.add(paging, BorderLayout.NORTH);
            JPanel footerPanel = footer.panel();
            footerPanel.setName("releasePickerActions");
            resultActions.add(footerPanel, BorderLayout.SOUTH);
            requestStatus.setName("releasePickerStatus");
            requestStatus.setForeground(FirstLaunchPanel.MUTED);
            requestStatus.setFont(guiScale.font(requestStatus.getFont(), "small"));
            requestStatus.getAccessibleContext().setAccessibleName(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.releasePickerStatus"));
            requestStatus.setPreferredSize(new Dimension(1,
                    requestStatus.getFontMetrics(requestStatus.getFont()).getHeight()));
            JPanel bottom = new JPanel(new BorderLayout(guiScale.space("md"),
                    guiScale.space("xs")));
            bottom.setOpaque(false);
            bottom.add(requestStatus, BorderLayout.NORTH);
            bottom.add(resultActions, BorderLayout.CENTER);

            JPanel body = new JPanel(new BorderLayout(guiScale.space("lg"),
                    guiScale.space("lg")));
            body.setName("releasePickerContent");
            body.setBackground(FirstLaunchPanel.BACKGROUND);
            body.setBorder(guiScale.padding("xxl", "xxl"));
            body.add(heading, BorderLayout.NORTH);
            body.add(scroll, BorderLayout.CENTER);
            body.add(bottom, BorderLayout.SOUTH);

            dialog = new JDialog(LauncherFrame.this,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.installAnotherVersion"), false);
            dialog.setName("releasePickerDialog");
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setContentPane(body);
            Rectangle available = GuiScale.usableBounds(
                    LauncherFrame.this.getGraphicsConfiguration());
            Dimension minimum = new Dimension(guiScale.size("dialog.md"),
                    guiScale.size("dialog.sm"));
            Dimension requested = new Dimension(guiScale.size("dialog.lg"),
                    guiScale.size("dialog.md"));
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
            dialog.getAccessibleContext().setAccessibleDescription(
                    I18n.getTextAt(BUNDLE, "LauncherFrame.releasePickerDescription"));
            cancel.addActionListener(event -> dialog.dispose());
            footer.install(dialog, dialog::dispose);
            for (JButton control : new JButton[]{fetch, previous, next}) {
                DialogFooter.pressOnEnter(control);
            }
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
            install.addActionListener(event -> prepareInstall());
        }

        private JLabel styledPickerLabel(String text) {
            JLabel label = new JLabel(text);
            label.setForeground(FirstLaunchPanel.TEXT);
            label.setFont(guiScale.font(label.getFont(), "label"));
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
            pageIndicator.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.pageNone"));
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
                pageIndicator.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.pageNone"));
                install.setEnabled(false);
            }
            previous.setEnabled(false);
            next.setEnabled(false);
            startTask(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchingReleases"),
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
                        pageIndicator.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.pageNumber",
                                String.valueOf(result.page())));
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

        private void prepareInstall() {
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
            String tag = choice.release().tag();
            AtomicReference<Path> attempted = new AtomicReference<>();
            // Same confirmation as Home: the suggested folder can be changed there.
            startTask(I18n.getTextAt(BUNDLE, "LauncherFrame.revalidatingInstallPlan"),
                    () -> {
                        Path target = suggestedExactDestination(repository, selectedChannel, tag);
                        attempted.set(target);
                        return services.prepareExactNormalInstall(repository, selectedChannel,
                                tag, choice.source(), target);
                    },
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
                    },
                    error -> exactPlanFailure(dialog, repository, selectedChannel, tag,
                            choice.source(), attempted.get(), error, this::prepareInstall));
        }

        private <T> void startTask(String description, Callable<T> operation,
                                   Consumer<T> success) {
            startTask(description, operation, success, null);
        }

        /** {@code failure}, when given, replaces the generic error dialog for task failures. */
        private <T> void startTask(String description, Callable<T> operation,
                                   Consumer<T> success, Consumer<Throwable> failure) {
            if (!gate.tryEnter()) {
                LauncherAlertDialog.showMessage(dialog, guiScale, I18n.getTextAt(BUNDLE, "LauncherFrame.pleaseWait"),
                        I18n.getTextAt(BUNDLE, "LauncherFrame.anotherOperationRunning"));
                return;
            }
            long request = ++generation;
            status.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.runInProgress", description));
            requestStatus.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.runInProgress", description));
            if (description.equals(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchingReleases"))) {
                fetch.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.fetching"));
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
                    fetch.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchReleases"));
                    if (isCancelled() || !dialog.isDisplayable()
                            || request != generation) return;
                    try {
                        success.accept(get());
                    } catch (java.util.concurrent.ExecutionException error) {
                        requestStatus.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.requestFailedRetry"));
                        previous.setEnabled(displayedPage > 1);
                        next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                        if (failure == null) {
                            showError(LocalizedText.of(BUNDLE, "LauncherFrame.runFailed", description),
                                    error.getCause());
                        } else {
                            updateInstallEnabled();
                            failure.accept(error.getCause());
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        requestStatus.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.requestInterruptedRetry"));
                        previous.setEnabled(displayedPage > 1);
                        next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.runInterrupted", description),
                                error);
                    } catch (RuntimeException error) {
                        requestStatus.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.requestFailedRetry"));
                        previous.setEnabled(displayedPage > 1);
                        next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.runFailed", description), error);
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
                fetch.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchReleases"));
                requestStatus.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.requestCouldNotStartRetry"));
                previous.setEnabled(displayedPage > 1);
                next.setEnabled(displayedPage > 0 && displayedMayHaveNext);
                showError(LocalizedText.of(BUNDLE, "LauncherFrame.runFailed", description), error);
            }
        }
    }

    private void openSelectedUpdate(InstallationRecord record, boolean apply) {
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingSelectedInstallation"),
                () -> services.loadInstallation(record.id()), selected -> {
                    if (!selected.preferred().equals(record)) {
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.openingSelectedInstallationFailed"),
                                new IOException("selected installation changed; choose it again"));
                        return;
                    }
                    if (selected.previewEligibility() == null
                            || !selected.previewEligibility().available()) {
                        showError(LocalizedText.of(BUNDLE, "LauncherFrame.updatesUnavailable"),
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
            showError(LocalizedText.of(BUNDLE, "LauncherFrame.openingUpdatePreviewFailed"), e);
            return;
        }
        DefaultListModel<ReleaseChoice> model = new DefaultListModel<>();
        JList<ReleaseChoice> releases = new JList<>(model);
        releases.setName("previewReleaseList");
        JButton fetch = button(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchReleases"), "fetchPreviewReleasesButton");
        JButton next = button(I18n.getTextAt(BUNDLE, "LauncherFrame.nextPage"), "nextPreviewReleasePageButton");
        JButton inspect = homeButton(I18n.getTextAt(BUNDLE, applyWorkflow
                        ? "LauncherFrame.updateEllipsis" : "LauncherFrame.downloadAndPreview"),
                "runPreviewButton", true);
        JButton cancel = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.cancel"), "cancelPreviewReleasesButton");
        next.setEnabled(false);
        inspect.setEnabled(false);
        JLabel pageLabel = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.noNetworkRequestYet"));
        final int[] page = {0};
        final boolean[] more = {false};
        JPanel top = new JPanel();
        top.add(new JLabel(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.officialSource", repository.slug())));
        top.add(fetch);
        JPanel bottom = new JPanel();
        bottom.add(pageLabel);
        bottom.add(next);
        JPanel body = new JPanel(new BorderLayout(guiScale.space("md"), guiScale.space("md")));
        body.add(top, BorderLayout.NORTH);
        body.add(LauncherTheme.scrollPane(releases), BorderLayout.CENTER);
        body.add(bottom, BorderLayout.SOUTH);
        DialogFooter footer = new DialogFooter(guiScale).primary(inspect).cancel(cancel);
        JDialog dialog = dialog(I18n.getTextAt(BUNDLE, "LauncherFrame.chooseUpdate"), body, footer.panel(),
                new Dimension(guiScale.size("dialog.lg"), guiScale.size("dialog.sm")));
        cancel.addActionListener(event -> dialog.dispose());
        footer.install(dialog, dialog::dispose);
        DialogFooter.pressOnEnter(fetch);
        DialogFooter.pressOnEnter(next);
        Consumer<Integer> load = requested -> {
            next.setEnabled(false);
            run(I18n.getTextAt(BUNDLE, "LauncherFrame.fetchingOfficialReleases"),
                    () -> services.releases(repository, requested),
                    result -> {
                        if (!dialog.isDisplayable()) return;
                        model.clear();
                        result.releases().forEach(release -> model.addElement(new ReleaseChoice(
                                release, services.assess(repository, release))));
                        page[0] = result.page();
                        more[0] = result.mayHaveNextPage();
                        next.setEnabled(more[0]);
                        pageLabel.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.pageNumber",
                                String.valueOf(page[0])) + " · " + I18n.getTextAt(BUNDLE,
                                more[0] ? "LauncherFrame.moreMayExist" : "LauncherFrame.lastPage"));
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
                    I18n.getTextAt(BUNDLE, "LauncherFrame.confirmPreviewDownload"),
                    I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.confirmPreviewDownloadMessage",
                            sourceCurrent == null ? sourceReceipt.tag() : sourceCurrent.tag(),
                            sourceCurrent == null ? sourceReceipt.assetName() : sourceCurrent.assetName(),
                            choice.release().tag(), asset.name(),
                            NumberFormat.getIntegerInstance().format(asset.size())),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.download"));
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
                I18n.getTextAt(BUNDLE, "LauncherFrame.downloadingUpdatePreview"), "previewProgressLog",
                this::showOperationLogs);
        progress.append("Preparing read-only preview for " + tag + "...\n");
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.buildingUpdatePreview"), OperationType.UPDATE_PREVIEW,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.previewUpdate(record, receipt, tag, stream, context),
                result -> {
                    progress.dispose();
                    showPreviewResult(result);
                }, error -> {
                    progress.append("\nPREVIEW FAILED: " + errorDetail(error) + "\n");
                    progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.updatePreviewFailed"));
                }, stream::close);
    }

    private void runPreparedUpdate(
            InstallationRecord record, org.megamek.launcher.update.OwnershipReceipt receipt,
            org.megamek.launcher.update.CurrentUpdateState current, String tag,
            String assetName, long assetSize, String assetDigest,
            ChannelUpdateChecker.Result recommended) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.preparingUpdate"), "applyUpdateProgressLog",
                this::showOperationLogs);
        progress.append("Downloading and verifying the exact consented package once for "
                + tag + "...\n");
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.preparingUpdate"), OperationType.UPDATE_APPLY,
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
                            progress.setHeadingIfCurrent(I18n.getTextAt(BUNDLE, "LauncherFrame.preparingUpdate"),
                                    I18n.getTextAt(BUNDLE, "LauncherFrame.applyingVerifiedUpdate")));
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
                    progress.showWarning(I18n.getTextAt(BUNDLE, "LauncherFrame.updateCompleteCleanupWarning"),
                            I18n.getTextAt(BUNDLE, "LauncherFrame.updateCompleteCleanupWarningMessage"));
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
                    progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.updateFailedRecovery"));
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
        run(I18n.getTextAt(BUNDLE, "LauncherFrame.preparingRepair"), () -> services.fileCheckEligible(record),
                checkFiles -> {
            if (!isDisplayable()) return;
            Boolean resetSettings = LauncherAlertDialog.showConfirmWithOption(this, guiScale,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.repair"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.repairPromptIntro")
                            + (checkFiles
                            ? "\n\n" + I18n.getTextAt(BUNDLE, "LauncherFrame.repairPromptCheckFiles")
                            : "")
                            + "\n\n" + I18n.getTextAt(BUNDLE, "LauncherFrame.repairPromptKeep"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.alsoResetSettings"), false,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.repair"));
            if (resetSettings == null) return;
            OperationProgressDialog progress = new OperationProgressDialog(this,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.repairingInstallation"), "repairProgressLog",
                    this::showOperationLogs);
            progress.setVisible(true);
            runOperation(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.repairingNamed", record.name()),
                    OperationType.REPAIR,
                    List.of(Path.of(record.canonicalRoot())), progress,
                    context -> services.quickRepair(record, resetSettings, context), result -> {
                        progress.dispose();
                        // A reload holds the operation gate, so prompt first; a download reloads
                        // when it finishes.
                        if (!showRepairResult(record, result)) reload();
                    }, error -> progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.repairFailed")), () -> {
                    });
        }, error -> showError(LocalizedText.of(BUNDLE, "LauncherFrame.repairUnavailable"), error));
    }

    /** Reports the offline repair and returns whether a download repair was started. */
    private boolean showRepairResult(InstallationRecord record,
                                     LauncherServices.QuickRepairResult result) {
        if (!isDisplayable()) return false;
        List<String> lines = new ArrayList<>();
        InstallationRepairService.Tidy tidy = result.tidy();
        lines.add(I18n.getTextAt(BUNDLE, tidy.clearedCaches().isEmpty()
                ? "LauncherFrame.noUnitCache" : "LauncherFrame.clearedUnitCache"));
        if (tidy.rescanned()) {
            lines.add(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.updatedRecordedVersion",
                    String.valueOf(tidy.previousBuild()), String.valueOf(tidy.record().observedBuild())));
        }
        if (result.settingsReset() != null) {
            lines.add(result.settingsReset().moved().isEmpty()
                    ? I18n.getTextAt(BUNDLE, "LauncherFrame.noSettingsToReset")
                    : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.settingsResetBackup",
                            String.valueOf(result.settingsReset().backup())));
        } else if (result.settingsError() != null) {
            lines.add(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.settingsNotReset",
                    String.valueOf(result.settingsError())));
        }
        LauncherServices.RepairCheck check = result.check();
        if (check != null && check.files().damaged() > 0) {
            InstallationRepairService.FileCheck files = check.files();
            long assetSize = check.source().current().assetSize();
            String size = assetSize > 0 ? BinarySizeFormat.mebibytes(assetSize)
                    : I18n.getTextAt(BUNDLE, "LauncherFrame.unknownSize");
            lines.add(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.restoreFilesPrompt",
                    describeDamage(files), size));
            boolean download = LauncherAlertDialog.showConfirm(this, guiScale,
                    I18n.getTextAt(BUNDLE, "LauncherFrame.restoreGameFiles"),
                    String.join("\n\n", lines), I18n.getTextAt(BUNDLE, "LauncherFrame.downloadAndRestore"));
            if (download) downloadRepair(record, check.source());
            return download;
        }
        if (check != null) {
            lines.add(I18n.getTextAt(BUNDLE, "LauncherFrame.allGameFilesIntact"));
        } else if (result.checkError() != null) {
            lines.add(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.gameFilesNotChecked",
                    String.valueOf(result.checkError())));
        }
        status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.repairComplete"));
        LauncherAlertDialog.showMessage(this, guiScale, I18n.getTextAt(BUNDLE, "LauncherFrame.repairComplete"),
                String.join("\n\n", lines));
        return false;
    }

    private static String describeDamage(InstallationRepairService.FileCheck files) {
        List<String> parts = new ArrayList<>();
        if (!files.missing().isEmpty()) {
            parts.add(I18n.getFormattedTextAt(BUNDLE, files.missing().size() == 1
                    ? "LauncherFrame.gameFileMissing" : "LauncherFrame.gameFilesMissing",
                    String.valueOf(files.missing().size())));
        }
        if (!files.modified().isEmpty()) {
            parts.add(I18n.getFormattedTextAt(BUNDLE, files.modified().size() == 1
                    ? "LauncherFrame.gameFileChanged" : "LauncherFrame.gameFilesChanged",
                    String.valueOf(files.modified().size())));
        }
        return I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.damageSentence",
                String.join(I18n.getTextAt(BUNDLE, "LauncherFrame.damageJoiner"), parts));
    }

    private void downloadRepair(InstallationRecord record, RealUpdateService.Snapshot source) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.repairingInstallation"), "repairProgressLog",
                this::showOperationLogs);
        progress.useRepairProgress();
        progress.startNonCancellable(I18n.getTextAt(BUNDLE, "LauncherFrame.keepOpenUntilRepair"));
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.repairingNamed", record.name()),
                OperationType.UPDATE_APPLY,
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
                                    ? I18n.getTextAt(BUNDLE, "LauncherFrame.repairCompleteNothingFound")
                                    : I18n.getTextAt(BUNDLE, "LauncherFrame.repairCompleteReady"),
                            result.extraLibJars().isEmpty() ? null
                                    : I18n.getTextAt(BUNDLE, "LauncherFrame.addedFilesMayAffectGame"));
                    page = Page.INSTALLATIONS;
                    reload();
                }, error -> {
                    progress.append("\nREPAIR FAILED: " + errorDetail(error)
                            + "\nIf recovery is pending, close all suite games and "
                            + "use Recover interrupted update. Do not retry repair "
                            + "until recovery completes.\n");
                    progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.repairFailedRecovery"));
                    reload();
                }, stream::close);
    }

    private void recoverUpdate(InstallationRecord record) {
        boolean confirmed = LauncherAlertDialog.showConfirm(this, guiScale,
                I18n.getTextAt(BUNDLE, "LauncherFrame.confirmUpdateRecovery"),
                I18n.getTextAt(BUNDLE, "LauncherFrame.confirmUpdateRecoveryMessage"),
                I18n.getTextAt(BUNDLE, "LauncherFrame.recover"));
        if (!confirmed) return;
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.recoveringUpdate"), "recoveryProgressLog",
                this::showOperationLogs);
        progress.append("Recovery is non-cancellable once replay or cleanup begins.\n");
        progress.startNonCancellable(I18n.getTextAt(BUNDLE, "LauncherFrame.recoveryStarting"));
        progress.setVisible(true);
        runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.recoveringUpdate"), OperationType.RECOVERY,
                List.of(Path.of(record.canonicalRoot())), progress,
                context -> services.recoverUpdate(
                        record, RealUpdateService.CONFIRM, context),
                result -> {
                    progress.dispose();
                    LauncherAlertDialog.showMessage(this, guiScale,
                            I18n.getTextAt(BUNDLE, "LauncherFrame.recoveryComplete"), result.outcome());
                    reload();
                }, error -> progress.setHeading(I18n.getTextAt(BUNDLE, "LauncherFrame.updateRecoveryFailed")),
                () -> {
                });
    }

    private void showPreviewResult(UpdatePreviewService.Preview result) {
        StringBuilder text = new StringBuilder();
        text.append(I18n.getTextAt(BUNDLE, "LauncherFrame.previewReadOnly")).append('\n')
                .append(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.previewBaseline",
                        result.baseline().tag(), result.baseline().assetName())).append('\n')
                .append(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.previewTarget",
                        result.targetRelease().tag(), result.targetAsset().name())).append('\n')
                .append(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.previewPolicy",
                        String.valueOf(result.baseline().ownershipPolicyVersion()))).append('\n')
                .append(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.previewSummary",
                        String.valueOf(result.counts()))).append("\n\n")
                .append(I18n.getTextAt(BUNDLE, "LauncherFrame.previewProtectedPaths")).append("\n  ")
                .append(String.join("\n  ", OwnershipPolicy.PROTECTED_PATHS))
                .append("\n\n").append(I18n.getTextAt(BUNDLE, "LauncherFrame.previewBaselineExcluded"))
                .append("\n  ")
                .append(String.join("\n  ", result.baseline().excludedOfficialPaths()))
                .append("\n\n").append(I18n.getTextAt(BUNDLE, "LauncherFrame.previewTargetExcluded"))
                .append("\n  ")
                .append(String.join("\n  ", result.targetExcludedPaths()))
                .append("\n\n").append(I18n.getTextAt(BUNDLE, "LauncherFrame.previewDecisions")).append('\n');
        result.decisions().forEach(decision -> text.append(decision.action()).append('\t')
                .append(decision.path()).append("\t— ").append(decision.reason()).append('\n'));
        try {
            for (String jar : OwnershipPolicy.extraLibJars(
                    Path.of(result.record().canonicalRoot()),
                    result.currentState().officialManifest(), result.targetManifest())) {
                text.append(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.previewExtraJar", jar))
                        .append('\n');
            }
        } catch (IOException e) {
            text.append(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.previewJarsUnreadable",
                    String.valueOf(e.getMessage()))).append('\n');
        }
        JTextArea details = textArea(text.toString());
        details.setName("updatePreviewResults");
        JButton close = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.close"), "closeUpdatePreviewButton", true);
        DialogFooter footer = new DialogFooter(guiScale).primary(close);
        JDialog dialog = dialog(I18n.getTextAt(BUNDLE, "LauncherFrame.readOnlyUpdatePreview"),
                LauncherTheme.scrollPane(details),
                footer.panel(), new Dimension(guiScale.size("dialog.lg"), guiScale.size("dialog.md")));
        close.addActionListener(event -> dialog.dispose());
        footer.install(dialog, dialog::dispose);
        dialog.setVisible(true);
    }

    /** The usual suggested folder; exact releases update later, so the name has no version. */
    private Path suggestedExactDestination(OfficialRepository repository,
                                           FollowChannel channel, String tag)
            throws IOException {
        Path parent = services.normalInstallDestination(repository, channel).getParent();
        if (parent == null) throw new IOException("no install folder could be suggested");
        return NormalInstallService.availableFolder(parent,
                NormalInstallService.defaultFolderName(repository, channel));
    }

    void install(OfficialRepository repository, String tag, Path destination, String name,
                 FollowChannel channel) {
        OperationProgressDialog progress = new OperationProgressDialog(this,
                I18n.getTextAt(BUNDLE, "LauncherFrame.downloadingAndInstalling"), "installProgressLog",
                this::showOperationLogs);
        progress.append("Preparing exact release " + tag + "...\n");
        JButton repair = button(I18n.getTextAt(BUNDLE, "LauncherFrame.openInstallations"),
                "repairPublishedInstallButton");
        repair.setEnabled(false);
        progress.addActionButton(repair);
        progress.setVisible(true);
        PrintStream stream = new PrintStream(new LogOutput(progress.logArea()), true,
                StandardCharsets.UTF_8);
        runOperation(I18n.getTextAt(BUNDLE, "LauncherFrame.downloadingAndInstalling"), OperationType.FRESH_INSTALL,
                List.of(destination), progress,
                context -> services.install(repository, tag, destination, name, channel,
                        stream, context),
                    result -> {
                        progress.dispose();
                        progress.append("\nInstalled and registered at "
                                + result.destination() + "\n");
                        LauncherAlertDialog.showMessage(LauncherFrame.this, guiScale,
                                I18n.getTextAt(BUNDLE, "LauncherFrame.installComplete"),
                                I18n.getTextAt(BUNDLE, "LauncherFrame.installCompleteMessage"));
                        reload();
                    }, error -> {
                        boolean published = Files.exists(destination,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        progress.append("\nINSTALL FAILED: " + errorDetail(error)
                                + (published
                                ? "\nA published copy is retained. Do not download over it; "
                                + "open Installations to register or repair the copy.\n"
                                : "\nNothing was published.\n"));
                        progress.setHeading(published
                                ? I18n.getTextAt(BUNDLE, "LauncherFrame.copyRetainedNeedsRepair")
                                : I18n.getTextAt(BUNDLE, "LauncherFrame.installationFailed"));
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
            LauncherAlertDialog.showMessage(this, guiScale, I18n.getTextAt(BUNDLE, "LauncherFrame.pleaseWait"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.anotherOperationRunning"));
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
                            I18n.getTextAt(BUNDLE, "LauncherFrame.workerFailedBeforeDiagnostics"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result = new OperationExecution<>(null, interrupted, false,
                            I18n.getTextAt(BUNDLE, "LauncherFrame.uiInterruptedCollectingResult"));
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
                            ? I18n.getTextAt(BUNDLE, "LauncherFrame.installationCancelled")
                            : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.operationCancelled", description));
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
                    status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.completedDiagnosticsNotSaved"));
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
            LauncherAlertDialog.showMessage(this, guiScale, I18n.getTextAt(BUNDLE, "LauncherFrame.pleaseWait"),
                    I18n.getTextAt(BUNDLE, "LauncherFrame.anotherOperationRunning"));
            return;
        }
        if (updateStatus) status.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.runInProgress", description));
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
                        status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.installationFolderNotFound"));
                        removeMissingInstallation(missing.record());
                        return;
                    }
                    failure.accept(problem);
                    if (showAutomaticError) {
                        showError(LocalizedText.of(BUNDLE, problem instanceof InterruptedException
                                ? "LauncherFrame.runInterrupted" : "LauncherFrame.runFailed", description), problem);
                        if (updateStatus) {
                            status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.operationFailedDetailsShown"));
                        }
                    } else if (updateStatus) {
                        status.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.operationCouldNotContinue"));
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

    /** Shows {@code title} in the player's language; the diagnostics record keeps it in English. */
    private void showError(LocalizedText title, Throwable error) {
        showError(title, error, true);
    }

    void showOperationFailureDetails(String title, Throwable error) {
        showError(LocalizedText.literal(title), error, false);
    }

    private void showError(LocalizedText title, Throwable error, boolean record) {
        if (error instanceof UninstallService.MissingInstallationException missing) {
            removeMissingInstallation(missing.record());
            return;
        }
        String details = SanitizedErrors.display(error);
        String loggingStatus = I18n.getTextAt(BUNDLE, record ? "LauncherFrame.savingLocalDiagnostics"
                : "LauncherFrame.localDiagnosticsAvailable");
        LauncherErrorDialog errorDialog = LauncherErrorDialog.show(this, title.text(), details,
                loggingStatus, guiScale, () -> copySanitized(details), this::showOperationLogs,
                this::showBugReport);
        if (record) {
            recordErrorAsync(title.english(), error, errorDialog::updateLoggingStatus);
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
                    result.accept(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.localDiagnosticsNotSaved",
                            SanitizedErrors.display(problem.getCause())));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.accept(I18n.getTextAt(BUNDLE, "LauncherFrame.localDiagnosticsInterrupted"));
                }
            }
        }.execute();
    }

    private void showOperationLogs() {
        JTextArea area = textArea(I18n.getTextAt(BUNDLE, "LauncherFrame.loadingOperationLogs"));
        area.setName("operationLogViewer");
        area.setBackground(FirstLaunchPanel.PANEL);
        area.setForeground(FirstLaunchPanel.TEXT);
        area.setCaretColor(FirstLaunchPanel.ACCENT);
        area.setSelectionColor(LauncherTheme.SELECTION_BACKGROUND);
        area.setSelectedTextColor(LauncherTheme.TEXT);
        area.setFont(guiScale.font(new Font(Font.MONOSPACED, Font.PLAIN, 12), "small"));
        JScrollPane scroll = LauncherTheme.scrollPane(area);
        scroll.setName("operationLogScrollPane");
        scroll.setBorder(BorderFactory.createLineBorder(LauncherTheme.BORDER));
        scroll.getViewport().setBackground(FirstLaunchPanel.PANEL);

        JDialog viewer = new JDialog(this, I18n.getTextAt(BUNDLE, "LauncherFrame.localOperationLogs"), false);
        viewer.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(guiScale.space("md"),
                guiScale.space("md")));
        body.setName("operationLogDialogContent");
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(guiScale.padding("xxl", "xxl"));
        JLabel heading = new JLabel(I18n.getTextAt(BUNDLE, "LauncherFrame.localOperationLogs"));
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(guiScale.font(heading.getFont(), "headline"));
        body.add(heading, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);

        JButton copy = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.copySanitizedLog"), "copyOperationLogButton");
        copy.addActionListener(event -> copySanitized(area.getText()));
        JButton close = homeButton(I18n.getTextAt(BUNDLE, "LauncherFrame.close"), "closeOperationLogButton", true);
        close.addActionListener(event -> viewer.dispose());
        DialogFooter footer = new DialogFooter(guiScale).tool(copy).primary(close);
        JPanel actions = footer.panel();
        actions.setName("operationLogActions");
        body.add(actions, BorderLayout.SOUTH);
        viewer.setContentPane(body);
        footer.install(viewer, viewer::dispose);
        viewer.setSize(guiScale.size("dialog.lg"), guiScale.size("dialog.md"));
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
                    area.setText(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.localLogsUnreadable",
                            SanitizedErrors.display(problem.getCause()),
                            String.valueOf(services.operationLogLocation())));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    area.setText(I18n.getTextAt(BUNDLE, "LauncherFrame.readingLogsInterrupted"));
                }
                area.setCaretPosition(0);
            }
        }.execute();
    }

    private static void copySanitized(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(SanitizedErrors.document(text)), null);
    }

    /**
     * Opens What's new for the versions Home's applications run, each its own preferred version,
     * starting where each installation was before its last update.
     */
    void showHomeWhatsNew() {
        Map<OfficialRepository, InstallationRecord> installations = homeWhatsNewInstallations();
        if (installations.isEmpty()) return;
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (InstallationRecord record : installations.values()) distinct.add(record.observedBuild());
        String version = distinct.size() == 1 ? distinct.iterator().next() : null;
        if (whatsNewDialog != null && whatsNewDialog.isDisplayable()) whatsNewDialog.dispose();
        whatsNewDialog = WhatsNewDialog.show(this, guiScale, null, version,
                () -> services.whatsNew(installations),
                link -> openWebsite(link, I18n.getTextAt(BUNDLE, "LauncherFrame.theReleaseNotes")));
    }

    /**
     * The installation each application on Home runs: its preferred one, or else the first one
     * with a version, so an application is still described when nothing is preferred.
     */
    private Map<OfficialRepository, InstallationRecord> homeWhatsNewInstallations() {
        Map<String, InstallationRecord> preferred = state.preferredApplications();
        InstallationRecord fallback = state.registry().installations().stream()
                .filter(record -> record.observedBuild() != null).findFirst().orElse(null);
        Map<OfficialRepository, InstallationRecord> installations = new java.util.EnumMap<>(OfficialRepository.class);
        for (OfficialRepository repository : OfficialRepository.values()) {
            InstallationRecord record = preferred == null ? null : preferred.get(repository.key());
            InstallationRecord chosen = record != null && record.observedBuild() != null ? record : fallback;
            if (chosen != null) installations.put(repository, chosen);
        }
        return installations;
    }

    /** Opens the bug report helper, or brings the open one to the front. */
    void showBugReport() {
        if (bugReportDialog != null && bugReportDialog.isDisplayable()) {
            bugReportDialog.toFront();
            bugReportDialog.requestFocus();
            return;
        }
        bugReportDialog = BugReportDialog.show(this, guiScale, new BugReportDialog.Actions(
                this::saveBugReportArchive,
                () -> openWebsite(BugReport.issueForm(services.bugReportEnvironment()),
                        I18n.getTextAt(BUNDLE, "LauncherFrame.theBugReportForm")),
                () -> openWebsite(java.net.URI.create(DISCORD_INVITE_URL), "Discord")));
    }

    private void saveBugReportArchive() {
        BugReportDialog dialog = bugReportDialog;
        java.awt.Window owner = dialog != null && dialog.isDisplayable() ? dialog : this;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.getTextAt(BUNDLE, "LauncherFrame.saveDiagnosticsZip"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                I18n.getTextAt(BUNDLE, "LauncherFrame.zipArchives"), "zip"));
        chooser.setSelectedFile(new java.io.File(BugReport.archiveFileName(java.time.Instant.now())));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Path selected = chooser.getSelectedFile().toPath();
        Path target = selected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? selected : selected.resolveSibling(selected.getFileName() + ".zip");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !LauncherAlertDialog.showDangerConfirm(owner, guiScale,
                I18n.getTextAt(BUNDLE, "LauncherFrame.replaceFile"),
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.replaceFileMessage",
                        String.valueOf(target.getFileName())),
                I18n.getTextAt(BUNDLE, "LauncherFrame.replace"))) {
            return;
        }
        if (dialog != null) dialog.archiveSaving(true);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                services.writeBugReport(target);
                return null;
            }

            @Override
            protected void done() {
                if (dialog == null) return;
                dialog.archiveSaving(false);
                try {
                    get();
                    dialog.showArchiveStatus(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.savedArchive",
                            String.valueOf(target.getFileName())), false);
                } catch (java.util.concurrent.ExecutionException problem) {
                    dialog.showArchiveStatus(I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.archiveNotSaved",
                            SanitizedErrors.display(problem.getCause())), true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    dialog.showArchiveStatus(I18n.getTextAt(BUNDLE, "LauncherFrame.archiveSaveInterrupted"), true);
                }
            }
        }.execute();
    }

    private JDialog dialog(String title, Component center, Component south, Dimension size) {
        JDialog dialog = new JDialog(this, title, false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(guiScale.space("md"), guiScale.space("md")));
        panel.setBorder(guiScale.padding("xxl", "xxl"));
        JLabel heading = new JLabel(title);
        heading.setName("dialogHeading");
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(guiScale.font(heading.getFont(), "headline"));
        JPanel main = new JPanel(new BorderLayout(0, guiScale.space("lg")));
        main.setOpaque(false);
        main.add(heading, BorderLayout.NORTH);
        main.add(center instanceof JScrollPane ? center : LauncherTheme.scrollPane(center),
                BorderLayout.CENTER);
        panel.add(main, BorderLayout.CENTER);
        if (south != null) panel.add(south, BorderLayout.SOUTH);
        LauncherTheme.styleContent(panel, guiScale);
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

    /**
     * The suggested install parent may not exist yet on a fresh machine. Planning creates such
     * parents with consent, so pre-select it by name in its nearest existing folder rather than
     * silently landing one level up.
     */
    private static JFileChooser installParentChooser(Path preferredParent) {
        JFileChooser chooser = folders(I18n.getTextAt(BUNDLE, "LauncherFrame.chooseInstallParent"),
                preferredParent);
        if (preferredParent == null) return chooser;
        Path existing = chooser.getCurrentDirectory().toPath();
        if (existing.equals(preferredParent)) return chooser;
        Path missing = preferredParent;
        while (missing.getParent() != null && !missing.getParent().equals(existing)) {
            missing = missing.getParent();
        }
        if (existing.equals(missing.getParent())) chooser.setSelectedFile(missing.toFile());
        return chooser;
    }

    private static JButton button(String text, String name) {
        return new FirstLaunchButton(text, name, false, GuiScale.DEFAULT,
                FirstLaunchButton.Size.COMPACT);
    }

    private JButton homeButton(String text, String name) {
        return homeButton(text, name, false);
    }

    /** A dialog-sized button; {@code primary} styles a dialog's main action. */
    private JButton homeButton(String text, String name, boolean primary) {
        JButton button = new FirstLaunchButton(text, name, primary, guiScale,
                FirstLaunchButton.Size.SMALL);
        button.getAccessibleContext().setAccessibleDescription(
                I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.activate", text));
        return button;
    }

    static JTextArea textArea(String text) {
        JTextArea area = new JTextArea(text, 10, 65);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        LauncherTheme.styleContent(area, GuiScale.DEFAULT);
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
        return I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.latestChoice",
                displayProduct(repository.key()), String.valueOf(channel));
    }

    static Path safeSubfolder(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(I18n.getTextAt(BUNDLE, "LauncherFrame.enterSubfolderName"));
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(I18n.getTextAt(BUNDLE, "LauncherFrame.subfolderNameInvalid"));
        }
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        boolean windowsReserved = upper.matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"
                + "(\\..*)?");
        if (path.isAbsolute() || path.getNameCount() != 1 || value.equals(".")
                || value.equals("..") || value.contains("/") || value.contains("\\")
                || value.contains(":") || value.endsWith(".") || value.endsWith(" ")
                || windowsReserved) {
            throw new IllegalArgumentException(I18n.getTextAt(BUNDLE, "LauncherFrame.subfolderPlainName"));
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
        /**
         * Asks for the parent of a new install folder, starting in {@code suggestedParent}, and
         * returns the folder named {@code folderName} inside it (numbered when that name is
         * taken); returns {@code null} when cancelled. The quote lets the player rename it.
         */
        Path choose(Component parent, Path suggestedParent, String folderName)
                throws IOException;
    }

    private static final class SwingNormalInstallLocationPrompts
            implements NormalInstallLocationPrompts {
        @Override
        public Path choose(Component parent, Path suggestedParent, String folderName)
                throws IOException {
            JFileChooser chooser = installParentChooser(suggestedParent);
            if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
            return NormalInstallService.availableFolder(
                    chooser.getSelectedFile().toPath(), folderName);
        }
    }

    private static final class SwingExistingImportPrompts implements ExistingImportPrompts {
        @Override
        public Path chooseFolder(Component parent) {
            return chooseFolder(parent, null);
        }

        @Override
        public Path chooseFolder(Component parent, Path preferredDirectory) {
            JFileChooser chooser = folders(I18n.getTextAt(BUNDLE, "LauncherFrame.chooseExtractedFolder"),
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
            String size = asset == null ? I18n.getTextAt(BUNDLE, "LauncherFrame.noSupportedAsset")
                    : I18n.getFormattedTextAt(BUNDLE, "LauncherFrame.bytes",
                            NumberFormat.getIntegerInstance().format(asset.size()));
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
                    : version + " — " + I18n.getTextAt(BUNDLE, "LauncherFrame.unavailableForVerification");
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
            String dated = release.published()
                    .map(published -> label + " — " + VersionDisplay.publishedDate(published,
                            java.time.ZoneId.systemDefault(), java.util.Locale.getDefault()))
                    .orElse(label);
            if (!assessment.eligible()) {
                return dated + " — " + I18n.getTextAt(BUNDLE, "LauncherFrame.unavailable");
            }
            ReleaseCatalog.Asset asset = assessment.asset();
            return dated + " — "
                    + NormalInstallConfirmationDialog.formatBinaryBytes(asset.size());
        }
    }

    private record HomeLoad(LauncherServices.HomeState state, Throwable error,
                            java.awt.image.BufferedImage artwork, Throwable artworkError) {
    }

    private record SettingsLoad(LauncherServices.SettingsView view,
                                LaunchOptionsStore.Document launchOptions,
                                Throwable launchOptionsError) {
    }

    private record CheckOnOpenSave(ChannelPreferenceStore.ReadResult authoritative,
                                   Throwable failure) {
    }

    private static final class ResponsiveHomeActions extends JPanel {
        private static final int MAX_CELL = 380;
        private static final int MAX_COLUMNS = 4;
        private final GuiScale scale;
        private final int gap;
        private int columns = MAX_COLUMNS;

        private ResponsiveHomeActions(GuiScale scale) {
            this.scale = scale;
            gap = scale.space("md");
            setLayout(new java.awt.GridLayout(0, columns, gap, gap));
        }

        /**
         * Columns shrink to the widest control's natural width before wrapping. Four controls wrap
         * as a 2x2 grid instead of leaving one alone on a second row.
         */
        private int columnsForWidth(int width, int count) {
            if (width <= 0) return 1;
            int cell = 0;
            for (Component child : getComponents()) {
                cell = Math.max(cell, child.getPreferredSize().width);
            }
            for (int candidate = Math.min(MAX_COLUMNS, count); candidate > 1; candidate--) {
                if (count == 4 && candidate == 3) continue;
                if (candidate * cell + (candidate - 1) * gap <= width) return candidate;
            }
            return 1;
        }

        private int availableWidth() {
            Container parent = getParent();
            if (parent == null) return getWidth();
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
        public Dimension getMinimumSize() {
            return layoutSize(true);
        }

        @Override
        public Dimension getPreferredSize() {
            return layoutSize(false);
        }

        private Dimension layoutSize(boolean minimum) {
            int count = getComponentCount();
            if (count == 0) return new Dimension();
            int width = 0;
            int height = 0;
            for (Component child : getComponents()) {
                Dimension size = minimum ? child.getMinimumSize() : child.getPreferredSize();
                width = Math.max(width, size.width);
                height = Math.max(height, size.height);
            }
            int usedColumns = columnsForWidth(availableWidth(), count);
            int rows = (count + usedColumns - 1) / usedColumns;
            return new Dimension(width * usedColumns + gap * (usedColumns - 1),
                    height * rows + gap * (rows - 1));
        }

        @Override
        public Dimension getMaximumSize() {
            // Each launch button stops growing at a comfortable width; the deck centres the group
            int count = Math.max(1, getComponentCount());
            int used = columnsForWidth(availableWidth(), count);
            int widest = 0;
            for (Component child : getComponents()) {
                widest = Math.max(widest, child.getPreferredSize().width);
            }
            int cell = Math.max(scale.scaleForGUI(MAX_CELL), widest);
            return new Dimension(cell * used + gap * (used - 1), getPreferredSize().height);
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
