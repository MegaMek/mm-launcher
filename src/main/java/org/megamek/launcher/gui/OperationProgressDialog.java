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
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationOutcome;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationProgressListener;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.update.ImportedCopyAdoptionService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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
    static final Color BACKGROUND = new Color(16, 31, 34);
    static final Color PANEL = new Color(23, 46, 49);
    static final Color GOLD = new Color(226, 196, 125);
    static final Color TEXT = new Color(239, 246, 240);
    static final Color MUTED = new Color(174, 194, 189);
    static final Color TRACK = new Color(41, 69, 72);
    private static final int UPDATE_INTERVAL_MS = 150;
    private static final int DETAIL_LIMIT = 90;

    private final GuiScale scale = GuiScale.DEFAULT;
    private final JLabel phase = new JLabel("Getting ready");
    private final JLabel detail = new JLabel("Preparing the operation…");
    private final JLabel loggingWarning = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final Component beforeProgress =
            Box.createVerticalStrut(scale.scaleForGUI(14));
    private final Component afterProgress =
            Box.createVerticalStrut(scale.scaleForGUI(12));
    private final JTextArea log;
    private final JButton cancel = control("Cancel", "operationCancelButton");
    private final JButton viewDetails =
            control("View details", "operationViewDetailsButton");
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT,
            scale.scaleForGUI(8), 0));
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

    OperationProgressDialog(LauncherFrame owner, String title, String logName,
                            Runnable showLogs) {
        super(owner, title, false);
        this.owner = owner;
        this.showLogs = showLogs;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        phase.setName("operationPhaseLabel");
        phase.setForeground(GOLD);
        phase.setFont(scale.font(phase.getFont(), Font.BOLD, 22f));
        phase.setAlignmentX(LEFT_ALIGNMENT);

        detail.setName("operationProgressDetail");
        detail.setForeground(MUTED);
        detail.setFont(scale.font(detail.getFont(), Font.PLAIN, 13f));
        detail.setAlignmentX(LEFT_ALIGNMENT);

        loggingWarning.setName("operationLoggingWarning");
        loggingWarning.setForeground(GOLD);
        loggingWarning.setFont(scale.font(loggingWarning.getFont(), Font.PLAIN, 12f));
        loggingWarning.setAlignmentX(LEFT_ALIGNMENT);
        loggingWarning.setVisible(false);

        progress.setName("operationProgressBar");
        progress.setForeground(GOLD);
        progress.setBackground(TRACK);
        progress.setBorder(BorderFactory.createLineBorder(new Color(91, 124, 122)));
        progress.setStringPainted(true);
        progress.setIndeterminate(true);
        progress.setString("Starting…");
        progress.setPreferredSize(scale.scaleForGUI(500, 22));
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, scale.scaleForGUI(22)));
        progress.setAlignmentX(LEFT_ALIGNMENT);
        progress.setUI(new BasicProgressBarUI() {
            @Override protected Color getSelectionBackground() {
                return BACKGROUND;
            }

            @Override protected Color getSelectionForeground() {
                return TEXT;
            }
        });

        log = LauncherFrame.textArea("");
        log.setName(logName);

        JPanel summary = new JPanel();
        summary.setName("operationSummary");
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        summary.setOpaque(false);
        summary.setBorder(BorderFactory.createEmptyBorder());
        summary.add(phase);
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
        actions.add(viewDetails);
        actions.add(cancel);

        JPanel panel = new JPanel(new BorderLayout(0, scale.scaleForGUI(14)));
        panel.setName("operationProgressContent");
        panel.setBackground(BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(18),
                scale.scaleForGUI(18), scale.scaleForGUI(16), scale.scaleForGUI(18)));
        panel.add(summary, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        setContentPane(panel);
        setSize(scale.scaleForGUI(560, 220));
        setMinimumSize(scale.scaleForGUI(480, 205));
        setLocationRelativeTo(owner);

        cancel.addActionListener(event -> requestCancellation());
        viewDetails.addActionListener(event -> {
            if (failure == null) {
                this.showLogs.run();
            } else {
                owner.showOperationFailureDetails(getTitle() + " details", failure);
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (finished) {
                    dispose();
                } else {
                    requestCancellation();
                }
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
        showWarning("Repair complete", message);
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

    void setTitleIfCurrent(String current, String next) {
        if (isDisplayable() && !finished && current.equals(getTitle())) {
            setTitle(next);
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
        phase.setText(getTitle() == null || getTitle().isBlank()
                ? "Operation failed" : getTitle());
        detail.setBorder(BorderFactory.createEmptyBorder(
                scale.scaleForGUI(8), 0, 0, 0));
        String summary = failureSummary != null
                ? failureSummary
                : simpleAdoptionResolution
                ? ImportedCopyAdoptionService.AUTOMATIC_MATCH_UNAVAILABLE
                : null;
        detail.setText(summary == null ? conciseFailure(problem) : "<html>" + summary + "</html>");
        loggingWarning.setVisible(false);
        if (!expectedFailure && logWarning != null && !logWarning.isBlank()) {
            loggingWarning.setText(
                    "Local diagnostics could not be saved. The original failure is unchanged.");
            loggingWarning.setBorder(BorderFactory.createEmptyBorder(
                    scale.scaleForGUI(8), 0, 0, 0));
            loggingWarning.setVisible(true);
        }
        for (JButton button : contextualActions) {
            button.setVisible(button.isEnabled());
        }
        viewDetails.setVisible(!expectedFailure && !simpleAdoptionResolution);
        cancel.setText("Close");
        cancel.getAccessibleContext().setAccessibleName("Close");
        cancel.setEnabled(true);
        cancel.setVisible(true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        actions.revalidate();
        getContentPane().revalidate();
        getContentPane().repaint();
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
        setTitle(heading);
        progress.setVisible(false);
        beforeProgress.setVisible(false);
        afterProgress.setVisible(false);
        phase.setText(heading);
        detail.setBorder(BorderFactory.createEmptyBorder(
                scale.scaleForGUI(8), 0, 0, 0));
        detail.setText(concise(message));
        loggingWarning.setVisible(false);
        for (JButton button : contextualActions) {
            button.setVisible(false);
        }
        viewDetails.setText("View logs");
        viewDetails.getAccessibleContext().setAccessibleName("View logs");
        viewDetails.getAccessibleContext().setAccessibleDescription(
                "Open local operation logs.");
        viewDetails.setVisible(true);
        cancel.setText("Close");
        cancel.getAccessibleContext().setAccessibleName("Close");
        cancel.setEnabled(true);
        cancel.setVisible(true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        actions.revalidate();
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    void addActionButton(JButton button) {
        button.putClientProperty(OPERATION_CONTROL, Boolean.TRUE);
        styleButton(button);
        contextualActions.add(button);
        button.setVisible(false);
        button.addPropertyChangeListener("enabled", event ->
                button.setVisible(failureShown && button.isEnabled()));
        actions.add(button, 0);
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
            detail.setText("Cancelling safely…");
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
                progress.setString(value + "%");
            } else if (event.unit() == ProgressUnit.FILES) {
                progress.setString(NumberFormat.getIntegerInstance().format(event.completed())
                        + " / " + NumberFormat.getIntegerInstance().format(event.total())
                        + " files");
            } else {
                progress.setString(value + "%");
            }
        } else {
            progress.setStringPainted(true);
            progress.setIndeterminate(true);
            progress.setString("Working…");
        }
        progress.getAccessibleContext().setAccessibleDescription(repairProgress
                && event.phase() != OperationPhase.DOWNLOAD
                ? repairPhaseName(event) : accessibleProgress(event));
        boolean cancellationAllowed = event.cancellationAllowed()
                && !nonCancellableFromStart;
        cancel.setEnabled(cancellationAllowed);
        cancel.setVisible(cancellationAllowed);
        cancel.setToolTipText(cancellationAllowed
                ? "Cancel at the next safe checkpoint" : null);
        detail.setText(repairProgress
                ? event.phase() == OperationPhase.DOWNLOAD
                        && event.unit() == ProgressUnit.BYTES && event.total() > 0
                        ? progressDetail(event)
                        : "Please keep the launcher open until repair finishes."
                : cancellationAllowed ? progressDetail(event) : nonCancellableDetail(event));
    }

    private static String repairPhaseName(OperationProgress event) {
        return switch (event.phase()) {
            case METADATA, DOWNLOAD -> "Downloading installation files";
            case VERIFY, EXTRACT, PLAN, PREPARE_INSTALL -> "Preparing installation files";
            case APPLY -> "Repairing installation";
            default -> "Finishing up";
        };
    }

    private static String phaseName(OperationProgress event) {
        if (event.operationType() == OperationType.ADOPT_EXISTING) {
            if (event.phase() == OperationPhase.PREPARE_INSTALL) {
                return "Final local check";
            }
            if (event.phase() == OperationPhase.APPLY) {
                return "Enabling managed updates";
            }
        }
        return event.phase().displayName();
    }

    private String nonCancellableDetail(OperationProgress event) {
        if (event.operationType() == OperationType.ADOPT_EXISTING) {
            return event.phase() == OperationPhase.APPLY
                    ? "Publishing managed-update metadata — do not close the launcher."
                    : "Final checks complete; metadata publication is starting — "
                    + "do not close the launcher.";
        }
        return finishingMessage(event.operationType(), event.cancellationReason());
    }

    private String progressDetail(OperationProgress event) {
        if (event.phase() == OperationPhase.DOWNLOAD
                && event.unit() == ProgressUnit.BYTES
                && event.completed() >= 0 && event.total() > 0) {
            return "Downloaded " + humanSize(event.completed())
                    + " of " + humanSize(event.total());
        }
        if (event.phase() == OperationPhase.EXTRACT
                && event.unit() == ProgressUnit.FILES && event.completed() >= 0) {
            return NumberFormat.getIntegerInstance().format(event.completed())
                    + " files processed";
        }
        String supplied = safeEventDetail(event.detail());
        return supplied == null ? phaseDetail(event.phase(), event.operationType()) : supplied;
    }

    private static String accessibleProgress(OperationProgress event) {
        if (event.phase() == OperationPhase.DOWNLOAD
                && event.unit() == ProgressUnit.BYTES && event.total() > 0) {
            long percentage = Math.min(100,
                    Math.round(100.0 * event.completed() / event.total()));
            return "Download " + percentage + " percent. Downloaded "
                    + humanSize(event.completed()) + " of " + humanSize(event.total()) + ".";
        }
        if (event.phase() == OperationPhase.EXTRACT
                && event.unit() == ProgressUnit.FILES && event.completed() >= 0) {
            return NumberFormat.getIntegerInstance().format(event.completed())
                    + " files processed.";
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
                    ? "Checking the selected installation…"
                    : type == OperationType.ADOPT_EXISTING
                    ? "Checking existing installation…"
                    : type == OperationType.UNINSTALL
                    || type == OperationType.UNINSTALL_RECOVERY
                    ? "Checking uninstall recovery state…"
                    : "Checking release information…";
            case DOWNLOAD -> "Downloading the verified package…";
            case VERIFY -> type == OperationType.IMPORT_EXISTING
                    ? "Checking installation compatibility…"
                    : type == OperationType.ADOPT_EXISTING
                    ? "Comparing the existing files safely…"
                    : "Checking the downloaded package…";
            case EXTRACT -> "Processing application files…";
            case PLAN -> type == OperationType.ADOPT_EXISTING
                    ? "Checking official package…"
                    : "Reviewing the planned changes…";
            case AWAIT_CONSENT -> "Waiting for confirmation…";
            case PREPARE_INSTALL -> type == OperationType.IMPORT_EXISTING
                    ? "Registering the selected installation…"
                    : type == OperationType.ADOPT_EXISTING
                    ? "Confirming installation has not changed…"
                    : "Preparing the installation…";
            case APPLY -> type == OperationType.ADOPT_EXISTING
                    ? "Publishing managed-update metadata…"
                    : "Applying the verified update…";
            case UNINSTALL -> "Removing verified official files…";
            case RECOVER -> "Repairing the interrupted update…";
            case CLEANUP -> "Cleaning up temporary files…";
            case FINAL -> "Completing the operation…";
        };
    }

    private String finishingMessage(OperationType type, String reason) {
        if (reason != null && reason.toLowerCase(java.util.Locale.ROOT)
                .contains("accepted")) {
            return "Cancelling safely…";
        }
        String operation = switch (type) {
            case FRESH_INSTALL -> "installation";
            case IMPORT_EXISTING -> "import";
            case ADOPT_EXISTING -> "verification";
            case UPDATE_PREVIEW -> "preview";
            case UPDATE_APPLY -> "update";
            case RECOVERY -> "recovery";
            case UNINSTALL -> "uninstall";
            case UNINSTALL_RECOVERY -> "uninstall recovery";
            case GUI_ERROR -> "operation";
        };
        return "Finishing " + operation + " — do not close the launcher.";
    }

    private String conciseFailure(Throwable problem) {
        if (problem == null) return "The operation could not be completed.";
        String message = problem.getMessage();
        if (message == null || message.isBlank()) {
            return "The operation could not be completed.";
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
                "Operation control: " + text);
        return button;
    }

    private void styleButton(JButton button) {
        if (button instanceof FirstLaunchButton) return;
        button.setBackground(PANEL);
        button.setForeground(TEXT);
        button.setFocusPainted(true);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GOLD),
                BorderFactory.createEmptyBorder(scale.scaleForGUI(6),
                        scale.scaleForGUI(12), scale.scaleForGUI(6),
                        scale.scaleForGUI(12))));
    }
}
