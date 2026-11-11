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

import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.internationalization.I18n;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationOutcome;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationProgressListener;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.update.ImportedCopyAdoptionService;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compact operation presentation. Detailed backend output remains bounded in {@link #logArea()}
 * for legacy streams, but is deliberately not part of the visible component hierarchy.
 */
final class OperationProgressDialog extends JDialog implements OperationProgressListener {
    static final String OPERATION_CONTROL = "mmLauncherOperationControl";
    private static final String BUNDLE = "org.megamek.launcher.gui.dialogs";
    static final Color BACKGROUND = LauncherTheme.BACKGROUND;
    static final Color PANEL = LauncherTheme.PANEL;
    static final Color ACCENT = LauncherTheme.ACCENT;
    static final Color TEXT = LauncherTheme.TEXT;
    static final Color MUTED = LauncherTheme.MUTED;
    static final Color TRACK = LauncherTheme.CONTROL_BACKGROUND;
    private static final int UPDATE_INTERVAL_MS = 150;
    private static final int DETAIL_LIMIT = 90;

    private final GuiScale scale = GuiScale.DEFAULT;
    private final JLabel operationName = new JLabel();
    private final JLabel phase = new JLabel(I18n.getTextAt(BUNDLE, "OperationProgressDialog.gettingReady"));
    private final JLabel detail =
            new JLabel(I18n.getTextAt(BUNDLE, "OperationProgressDialog.preparingOperation"));
    private final JLabel loggingWarning = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final Component beforeProgress =
            Box.createVerticalStrut(scale.space("lg"));
    private final Component afterProgress =
            Box.createVerticalStrut(scale.space("md"));
    private final JTextArea log;
    private final JButton cancel =
            control(I18n.getTextAt(BUNDLE, "OperationProgressDialog.cancel"), "operationCancelButton");
    private final JButton viewDetails = control(
            I18n.getTextAt(BUNDLE, "OperationProgressDialog.viewDetails"), "operationViewDetailsButton");
    private final List<JComponent> footerTools = new ArrayList<>();
    private final List<JButton> footerActions = new ArrayList<>();
    private final JPanel actions = new JPanel(new DialogFooter.FooterLayout(footerTools, footerActions,
            scale));
    private final JPanel headerControls = new JPanel(new GridBagLayout());
    private final List<JButton> contextualActions = new ArrayList<>();
    private final AtomicReference<OperationProgress> pending = new AtomicReference<>();
    private final Timer updateTimer;
    private final LauncherFrame owner;
    private final Runnable showLogs;
    private OperationContext context;
    private Throwable failure;
    private String failureSummary;
    private boolean expectedFailure;
    private boolean finished;
    private boolean failureShown;
    private boolean nonCancellableFromStart;
    private boolean repairProgress;
    private String heading;

    OperationProgressDialog(LauncherFrame owner, String title, String logName,
                            Runnable showLogs) {
        super(owner, title, false);
        this.owner = owner;
        this.showLogs = showLogs;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        operationName.setName("operationNameLabel");
        operationName.setForeground(MUTED);
        operationName.setFont(scale.font(operationName.getFont(), "label"));
        operationName.setAlignmentX(LEFT_ALIGNMENT);
        setHeading(title);

        phase.setName("operationPhaseLabel");
        phase.setForeground(ACCENT);
        phase.setFont(scale.font(phase.getFont(), "headline"));
        phase.setAlignmentX(LEFT_ALIGNMENT);

        detail.setName("operationProgressDetail");
        detail.setForeground(MUTED);
        detail.setFont(scale.font(detail.getFont(), "body"));
        detail.setAlignmentX(LEFT_ALIGNMENT);

        loggingWarning.setName("operationLoggingWarning");
        loggingWarning.setForeground(LauncherTheme.WARNING);
        loggingWarning.setFont(scale.font(loggingWarning.getFont(), "small"));
        loggingWarning.setAlignmentX(LEFT_ALIGNMENT);
        loggingWarning.setVisible(false);

        progress.setName("operationProgressBar");
        progress.setForeground(ACCENT);
        progress.setBackground(TRACK);
        progress.setBorder(BorderFactory.createLineBorder(LauncherTheme.BORDER));
        progress.setStringPainted(true);
        progress.setFont(scale.font(progress.getFont(), "label"));
        progress.setIndeterminate(true);
        progress.setString(I18n.getTextAt(BUNDLE, "OperationProgressDialog.starting"));
        progress.setAlignmentX(LEFT_ALIGNMENT);
        progress.setUI(new BasicProgressBarUI() {
            @Override protected Color getSelectionBackground() {
                return TEXT;
            }

            @Override protected Color getSelectionForeground() {
                return BACKGROUND;
            }
        });
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, progress.getPreferredSize().height));

        log = LauncherFrame.textArea("");
        log.setName(logName);

        JPanel summary = new JPanel();
        summary.setName("operationSummary");
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        summary.setOpaque(false);
        summary.setBorder(BorderFactory.createEmptyBorder());
        // While the operation runs, Cancel sits in the header beside the phase; the footer appears
        // only once there is a result to close, retry, or inspect.
        headerControls.setName("operationHeaderControls");
        headerControls.setOpaque(false);
        headerControls.add(cancel);
        JPanel header = new JPanel(new BorderLayout(scale.space("lg"), 0));
        header.setName("operationHeader");
        header.setOpaque(false);
        header.setAlignmentX(LEFT_ALIGNMENT);
        header.add(phase, BorderLayout.CENTER);
        header.add(headerControls, BorderLayout.LINE_END);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        summary.add(operationName);
        summary.add(Box.createVerticalStrut(scale.space("xs")));
        summary.add(header);
        summary.add(beforeProgress);
        summary.add(progress);
        summary.add(afterProgress);
        summary.add(detail);
        summary.add(loggingWarning);

        styleButton(cancel);
        styleButton(viewDetails);
        viewDetails.setVisible(false);
        actions.setName("operationActions");
        actions.setBackground(BACKGROUND);
        // View details is a helper at the footer's leading end; Close always ends the row.
        footerTools.add(viewDetails);
        actions.add(viewDetails);
        actions.setVisible(false);

        JPanel panel = new JPanel(new BorderLayout(0, scale.space("xl")));
        panel.setName("operationProgressContent");
        panel.setBackground(BACKGROUND);
        panel.setBorder(scale.padding("xxl", "xxl"));
        panel.add(summary, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        setContentPane(panel);
        pack();
        setSize(Math.max(getWidth(), scale.size("dialog.md")), getHeight());
        setMinimumSize(new Dimension(Math.min(getWidth(), scale.size("dialog.sm")), getHeight()));
        setLocationRelativeTo(owner);

        cancel.addActionListener(event -> requestCancellation());
        DialogFooter.pressOnEnter(cancel);
        DialogFooter.pressOnEnter(viewDetails);
        viewDetails.addActionListener(event -> {
            if (failure == null) {
                this.showLogs.run();
            } else {
                owner.showOperationFailureDetails(I18n.getFormattedTextAt(BUNDLE,
                        "OperationProgressDialog.failureDetailsTitle", heading), failure);
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeRequested();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "operationProgress.escape");
        getRootPane().getActionMap().put("operationProgress.escape", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                closeRequested();
            }
        });
        updateTimer = new Timer(UPDATE_INTERVAL_MS, event -> flush());
        updateTimer.setRepeats(true);
        updateTimer.start();
    }

    void bind(OperationContext operationContext) {
        if (context != null) throw new IllegalStateException("progress dialog is already bound");
        context = operationContext;
    }

    JTextArea logArea() {
        return log;
    }

    void append(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            LauncherFrame.appendBounded(log, text);
        } else {
            SwingUtilities.invokeLater(() -> LauncherFrame.appendBounded(log, text));
        }
    }

    void startNonCancellable(String message) {
        nonCancellableFromStart = true;
        cancel.setEnabled(false);
        cancel.setVisible(false);
        detail.setText(concise(message));
    }

    void useRepairProgress() {
        repairProgress = true;
    }

    boolean isRepairProgress() {
        return repairProgress;
    }

    void showRepairComplete(String message, String caveat) {
        showWarning(I18n.getTextAt(BUNDLE, "OperationProgressDialog.repairComplete"), message);
        if (caveat != null) {
            loggingWarning.setText(caveat);
            loggingWarning.setVisible(true);
        }
    }

    void setExpectedFailureSummary(String message) {
        setFailureSummary(message);
        expectedFailure = true;
    }

    void setFailureSummary(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("failure summary must not be blank");
        }
        failureSummary = message;
    }

    /** The operation's name: shown above the current step, then as the result's heading. */
    String heading() {
        return heading;
    }

    void setHeading(String next) {
        heading = next;
        operationName.setText(next);
        setTitle(next);
        getAccessibleContext().setAccessibleName(next);
    }

    void setHeadingIfCurrent(String current, String next) {
        if (isDisplayable() && !finished && current.equals(heading)) {
            setHeading(next);
        }
    }

    void showFailure(Throwable problem) {
        showFailure(problem, null);
    }

    void showFailure(Throwable problem, String logWarning) {
        failure = problem;
        boolean simpleAdoptionResolution =
                problem instanceof ImportedCopyAdoptionService.CandidateResolutionException;
        failureShown = true;
        finished = true;
        pending.set(null);
        updateTimer.stop();
        progress.setVisible(false);
        beforeProgress.setVisible(false);
        afterProgress.setVisible(false);
        operationName.setVisible(false);
        phase.setText(heading == null || heading.isBlank()
                ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.operationFailed") : heading);
        detail.setBorder(BorderFactory.createEmptyBorder(
                scale.space("md"), 0, 0, 0));
        String summary = failureSummary != null
                ? failureSummary
                : simpleAdoptionResolution
                ? ImportedCopyAdoptionService.AUTOMATIC_MATCH_UNAVAILABLE
                : null;
        detail.setText(summary == null ? conciseFailure(problem) : "<html>" + summary + "</html>");
        loggingWarning.setVisible(false);
        if (!expectedFailure && logWarning != null && !logWarning.isBlank()) {
            loggingWarning.setText(
                    I18n.getTextAt(BUNDLE, "OperationProgressDialog.diagnosticsNotSaved"));
            loggingWarning.setBorder(BorderFactory.createEmptyBorder(
                    scale.space("md"), 0, 0, 0));
            loggingWarning.setVisible(true);
        }
        for (JButton button : contextualActions) {
            button.setVisible(button.isEnabled());
        }
        viewDetails.setVisible(!expectedFailure && !simpleAdoptionResolution);
        showResultFooter();
    }

    void showCancelled() {
        finished = true;
        pending.set(null);
        updateTimer.stop();
        dispose();
    }

    void showWarning(String heading, String message) {
        if (heading == null || heading.isBlank()) {
            throw new IllegalArgumentException("warning heading must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("warning message must not be blank");
        }
        failure = null;
        finished = true;
        pending.set(null);
        updateTimer.stop();
        setHeading(heading);
        operationName.setVisible(false);
        progress.setVisible(false);
        beforeProgress.setVisible(false);
        afterProgress.setVisible(false);
        phase.setText(heading);
        detail.setBorder(BorderFactory.createEmptyBorder(
                scale.space("md"), 0, 0, 0));
        detail.setText(concise(message));
        loggingWarning.setVisible(false);
        for (JButton button : contextualActions) {
            button.setVisible(false);
        }
        String viewLogs = I18n.getTextAt(BUNDLE, "OperationProgressDialog.viewLogs");
        viewDetails.setText(viewLogs);
        viewDetails.getAccessibleContext().setAccessibleName(viewLogs);
        viewDetails.getAccessibleContext().setAccessibleDescription(
                I18n.getTextAt(BUNDLE, "OperationProgressDialog.viewLogsDescription"));
        viewDetails.setVisible(true);
        showResultFooter();
    }

    /** Turns Cancel into Close at the end of the footer, which appears now that there is a result. */
    private void showResultFooter() {
        String close = I18n.getTextAt(BUNDLE, "OperationProgressDialog.close");
        cancel.setText(close);
        cancel.getAccessibleContext().setAccessibleName(close);
        cancel.getAccessibleContext().setAccessibleDescription(
                I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.controlDescription", close));
        cancel.setToolTipText(null);
        cancel.setEnabled(true);
        cancel.setVisible(true);
        if (cancel.getParent() != actions) {
            headerControls.remove(cancel);
            headerControls.setVisible(false);
            footerActions.add(cancel);
            actions.add(cancel);
        }
        actions.setVisible(true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        updateDefaultButton();
        actions.revalidate();
        getContentPane().revalidate();
        getContentPane().repaint();
        int needed = getPreferredSize().height;
        if (getHeight() < needed) setSize(getWidth(), needed);
        validate();
        DialogFooter.keepFooterVisible(this, actions);
    }

    /** Enter presses the result's first action, such as Open Installations, otherwise Close. */
    private void updateDefaultButton() {
        JButton main = cancel;
        for (JButton button : footerActions) {
            if (button != cancel && button.isVisible() && button.isEnabled()) {
                main = button;
                break;
            }
        }
        getRootPane().setDefaultButton(main);
    }

    void addActionButton(JButton button) {
        button.putClientProperty(OPERATION_CONTROL, Boolean.TRUE);
        styleButton(button);
        contextualActions.add(button);
        button.setVisible(false);
        DialogFooter.pressOnEnter(button);
        button.addPropertyChangeListener("enabled", event -> {
            button.setVisible(failureShown && button.isEnabled());
            if (failureShown) updateDefaultButton();
        });
        // Contextual actions such as Retry come before Close, in the order they were added.
        footerActions.add(footerActions.contains(cancel) ? footerActions.size() - 1
                : footerActions.size(), button);
        actions.add(button);
        actions.revalidate();
    }

    @Override
    public void onProgress(OperationProgress event) {
        // OperationLogStore still receives terminal typed progress. The dialog waits for the
        // authoritative outcome handler so it never flashes Finished/100% before success closes
        // or a compact failure state is ready.
        if (event.outcome() != OperationOutcome.RUNNING) return;
        pending.set(event);
    }

    @Override
    public void dispose() {
        if (updateTimer != null) updateTimer.stop();
        super.dispose();
    }

    private void closeRequested() {
        if (finished) {
            dispose();
        } else {
            requestCancellation();
        }
    }

    private void requestCancellation() {
        if (finished) {
            dispose();
            return;
        }
        if (context == null) return;
        if (nonCancellableFromStart) {
            cancel.setEnabled(false);
            cancel.setVisible(false);
            detail.setText(finishingMessage(context.type(), null));
            return;
        }
        OperationContext.CancellationRequest request = context.requestCancellation();
        cancel.setEnabled(false);
        cancel.setVisible(false);
        if (request.accepted()) {
            detail.setText(I18n.getTextAt(BUNDLE, "OperationProgressDialog.cancellingSafely"));
            if (request.closeFailure() != null) {
                append("\nCancellation resource-close warning: "
                        + SanitizedErrors.display(request.closeFailure()) + "\n");
            }
            return;
        }
        OperationProgress latest = context.latest();
        detail.setText(latest != null && latest.outcome() == OperationOutcome.RUNNING
                ? nonCancellableDetail(latest)
                : finishingMessage(context.type(), request.reason()));
    }

    void flush() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::flush);
            return;
        }
        OperationProgress event = pending.getAndSet(null);
        if (event == null || !isDisplayable() || finished) return;
        phase.setText(repairProgress ? repairPhaseName(event) : phaseName(event));
        boolean extraction = event.phase() == OperationPhase.EXTRACT
                || repairProgress && event.phase() != OperationPhase.DOWNLOAD;
        if (extraction) {
            progress.setIndeterminate(true);
            progress.setStringPainted(false);
            progress.setString(null);
        } else if (event.determinate()) {
            progress.setStringPainted(true);
            progress.setIndeterminate(false);
            int value = (int) Math.min(100,
                    Math.round(100.0 * event.completed() / event.total()));
            progress.setValue(value);
            if (event.unit() == ProgressUnit.BYTES) {
                progress.setString(I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.percent",
                        String.valueOf(value)));
            } else if (event.unit() == ProgressUnit.FILES) {
                progress.setString(I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.filesCount",
                        NumberFormat.getIntegerInstance().format(event.completed()),
                        NumberFormat.getIntegerInstance().format(event.total())));
            } else {
                progress.setString(I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.percent",
                        String.valueOf(value)));
            }
        } else {
            progress.setStringPainted(true);
            progress.setIndeterminate(true);
            progress.setString(I18n.getTextAt(BUNDLE, "OperationProgressDialog.working"));
        }
        progress.getAccessibleContext().setAccessibleDescription(repairProgress
                && event.phase() != OperationPhase.DOWNLOAD
                ? repairPhaseName(event) : accessibleProgress(event));
        boolean cancellationAllowed = event.cancellationAllowed()
                && !nonCancellableFromStart;
        cancel.setEnabled(cancellationAllowed);
        cancel.setVisible(cancellationAllowed);
        cancel.setToolTipText(cancellationAllowed
                ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.cancelTooltip") : null);
        detail.setText(repairProgress
                ? event.phase() == OperationPhase.DOWNLOAD
                        && event.unit() == ProgressUnit.BYTES && event.total() > 0
                        ? progressDetail(event)
                        : I18n.getTextAt(BUNDLE, "OperationProgressDialog.keepOpenForRepair")
                : cancellationAllowed ? progressDetail(event) : nonCancellableDetail(event));
    }

    private static String repairPhaseName(OperationProgress event) {
        return switch (event.phase()) {
            case METADATA, DOWNLOAD -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.repairDownloading");
            case VERIFY, EXTRACT, PLAN, PREPARE_INSTALL ->
                    I18n.getTextAt(BUNDLE, "OperationProgressDialog.repairPreparing");
            case APPLY -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.repairApplying");
            default -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.repairFinishing");
        };
    }

    private static String phaseName(OperationProgress event) {
        if (event.operationType() == OperationType.UNINSTALL
                && event.phase() == OperationPhase.PLAN) {
            return I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingInstallationFiles");
        }
        if (event.operationType() == OperationType.ADOPT_EXISTING) {
            if (event.phase() == OperationPhase.PREPARE_INSTALL) {
                return I18n.getTextAt(BUNDLE, "OperationProgressDialog.finalLocalCheck");
            }
            if (event.phase() == OperationPhase.APPLY) {
                return I18n.getTextAt(BUNDLE, "OperationProgressDialog.enablingManagedUpdates");
            }
        }
        return event.phase().displayName();
    }

    private String nonCancellableDetail(OperationProgress event) {
        if (event.operationType() == OperationType.ADOPT_EXISTING) {
            return event.phase() == OperationPhase.APPLY
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.publishingMetadataDoNotClose")
                    : I18n.getTextAt(BUNDLE, "OperationProgressDialog.finalChecksCompleteDoNotClose");
        }
        return finishingMessage(event.operationType(), event.cancellationReason());
    }

    private String progressDetail(OperationProgress event) {
        if (event.phase() == OperationPhase.DOWNLOAD
                && event.unit() == ProgressUnit.BYTES
                && event.completed() >= 0 && event.total() > 0) {
            return I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.downloadedOf",
                    humanSize(event.completed()), humanSize(event.total()));
        }
        if (event.phase() == OperationPhase.EXTRACT
                && event.unit() == ProgressUnit.FILES && event.completed() >= 0) {
            return I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.filesProcessed",
                    NumberFormat.getIntegerInstance().format(event.completed()));
        }
        String supplied = safeEventDetail(event.displayDetail());
        return supplied == null ? phaseDetail(event.phase(), event.operationType()) : supplied;
    }

    private static String accessibleProgress(OperationProgress event) {
        if (event.phase() == OperationPhase.DOWNLOAD
                && event.unit() == ProgressUnit.BYTES && event.total() > 0) {
            long percentage = Math.min(100,
                    Math.round(100.0 * event.completed() / event.total()));
            return I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.accessibleDownload",
                    String.valueOf(percentage), humanSize(event.completed()), humanSize(event.total()));
        }
        if (event.phase() == OperationPhase.EXTRACT
                && event.unit() == ProgressUnit.FILES && event.completed() >= 0) {
            return I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.accessibleFilesProcessed",
                    NumberFormat.getIntegerInstance().format(event.completed()));
        }
        return phaseName(event);
    }

    static String humanSize(long bytes) {
        if (bytes < 1_000) {
            return NumberFormat.getIntegerInstance().format(Math.max(0, bytes)) + " B";
        }
        final String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1_000.0;
            unit++;
        } while (value >= 1_000 && unit < units.length - 1);
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(value < 10 ? 1 : 0);
        format.setMinimumFractionDigits(0);
        return format.format(value) + " " + units[unit];
    }

    private String safeEventDetail(String supplied) {
        if (supplied == null || supplied.isBlank()) return null;
        String singleLine = supplied.replaceAll("\\s+", " ").trim();
        String lower = singleLine.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("adoption detail:")) return null;
        boolean pathOrAddress = singleLine.contains("\\") || singleLine.contains("/")
                || lower.contains("://") || lower.startsWith("file:")
                || lower.matches("^[a-z]:.*");
        boolean digest = lower.contains("sha-256") || lower.contains("sha256")
                || lower.contains("digest")
                || lower.matches(".*\\b[0-9a-f]{32,}\\b.*");
        if (pathOrAddress || digest) return null;
        return concise(singleLine);
    }

    private String phaseDetail(OperationPhase current, OperationType type) {
        return switch (current) {
            case METADATA -> type == OperationType.IMPORT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingSelectedInstallation")
                    : type == OperationType.ADOPT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingExistingInstallation")
                    : type == OperationType.UNINSTALL
                    || type == OperationType.UNINSTALL_RECOVERY
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingUninstallRecovery")
                    : I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingReleaseInformation");
            case DOWNLOAD -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.downloadingPackage");
            case VERIFY -> type == OperationType.IMPORT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingCompatibility")
                    : type == OperationType.ADOPT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.comparingExistingFiles")
                    : I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingDownloadedPackage");
            case EXTRACT -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.processingFiles");
            case PLAN -> type == OperationType.ADOPT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.checkingOfficialPackage")
                    : I18n.getTextAt(BUNDLE, "OperationProgressDialog.reviewingPlannedChanges");
            case AWAIT_CONSENT -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.waitingForConfirmation");
            case PREPARE_INSTALL -> type == OperationType.IMPORT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.registeringSelectedInstallation")
                    : type == OperationType.ADOPT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.confirmingInstallationUnchanged")
                    : I18n.getTextAt(BUNDLE, "OperationProgressDialog.preparingInstallation");
            case APPLY -> type == OperationType.ADOPT_EXISTING
                    ? I18n.getTextAt(BUNDLE, "OperationProgressDialog.publishingMetadata")
                    : I18n.getTextAt(BUNDLE, "OperationProgressDialog.applyingUpdate");
            case UNINSTALL -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.removingOfficialFiles");
            case RECOVER -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.repairingInterruptedUpdate");
            case CLEANUP -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.cleaningUp");
            case FINAL -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.completingOperation");
        };
    }

    private String finishingMessage(OperationType type, String reason) {
        if (reason != null && reason.toLowerCase(java.util.Locale.ROOT)
                .contains("accepted")) {
            return I18n.getTextAt(BUNDLE, "OperationProgressDialog.cancellingSafely");
        }
        return switch (type) {
            case FRESH_INSTALL -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingInstallation");
            case IMPORT_EXISTING -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingImport");
            case ADOPT_EXISTING -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingVerification");
            case UPDATE_PREVIEW -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingPreview");
            case UPDATE_APPLY -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingUpdate");
            case LAUNCHER_UPDATE -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingLauncherUpdate");
            case RECOVERY -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingRecovery");
            case UNINSTALL -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingUninstall");
            case UNINSTALL_RECOVERY ->
                    I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingUninstallRecovery");
            case REPAIR -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingRepair");
            case GUI_ERROR -> I18n.getTextAt(BUNDLE, "OperationProgressDialog.finishingOperation");
        };
    }

    private String conciseFailure(Throwable problem) {
        if (problem == null) return I18n.getTextAt(BUNDLE, "OperationProgressDialog.couldNotComplete");
        String message = problem.getLocalizedMessage();
        if (message == null || message.isBlank()) {
            return I18n.getTextAt(BUNDLE, "OperationProgressDialog.couldNotComplete");
        }
        return concise(SanitizedErrors.text(message));
    }

    private String concise(String value) {
        if (value == null || value.isBlank()) return " ";
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= DETAIL_LIMIT ? singleLine
                : singleLine.substring(0, DETAIL_LIMIT - 1) + "…";
    }

    private JButton control(String text, String name) {
        JButton button = new FirstLaunchButton(text, name, false, scale,
                FirstLaunchButton.Size.SMALL);
        button.putClientProperty(OPERATION_CONTROL, Boolean.TRUE);
        button.getAccessibleContext().setAccessibleName(text);
        button.getAccessibleContext().setAccessibleDescription(
                I18n.getFormattedTextAt(BUNDLE, "OperationProgressDialog.controlDescription", text));
        return button;
    }

    private void styleButton(JButton button) {
        if (button instanceof FirstLaunchButton) return;
        button.setBackground(PANEL);
        button.setForeground(TEXT);
        button.setFocusPainted(true);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT),
                scale.padding("sm", "lg")));
    }
}
