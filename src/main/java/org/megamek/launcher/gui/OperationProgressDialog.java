package org.megamek.launcher.gui;

import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationProgressListener;
import org.megamek.launcher.operation.ProgressUnit;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.NumberFormat;
import java.util.concurrent.atomic.AtomicReference;

final class OperationProgressDialog extends JDialog implements OperationProgressListener {
    static final String OPERATION_CONTROL = "mmLauncherOperationControl";
    private static final int UPDATE_INTERVAL_MS = 150;
    private final JLabel phase = new JLabel("Starting operation...");
    private final JLabel detail = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea log;
    private final JButton cancel = control("Cancel", "operationCancelButton");
    private final JButton viewLogs = control("View logs", "operationViewLogsButton");
    private final JButton copy = control("Copy sanitized details", "copyOperationDetailsButton");
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    private final AtomicReference<OperationProgress> pending = new AtomicReference<>();
    private final Timer updateTimer;
    private OperationContext context;
    private boolean finished;

    OperationProgressDialog(LauncherFrame owner, String title, String logName,
                            Runnable showLogs) {
        super(owner, title, false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        phase.setName("operationPhaseLabel");
        detail.setName("operationProgressDetail");
        progress.setName("operationProgressBar");
        progress.setStringPainted(true);
        progress.setIndeterminate(true);
        progress.setString("Starting...");
        log = LauncherFrame.textArea("");
        log.setName(logName);

        JPanel summary = new JPanel();
        summary.setLayout(new javax.swing.BoxLayout(summary,
                javax.swing.BoxLayout.Y_AXIS));
        summary.add(phase);
        summary.add(javax.swing.Box.createVerticalStrut(5));
        summary.add(progress);
        summary.add(javax.swing.Box.createVerticalStrut(5));
        summary.add(detail);

        actions.add(viewLogs);
        actions.add(copy);
        actions.add(cancel);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(summary, BorderLayout.NORTH);
        panel.add(new JScrollPane(log), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        setContentPane(panel);
        setSize(new Dimension(760, 440));
        setLocationRelativeTo(owner);

        cancel.addActionListener(event -> requestCancellation());
        viewLogs.addActionListener(event -> showLogs.run());
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(SanitizedErrors.document(log.getText())), null));
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
        LauncherFrame.appendBounded(log, text);
    }

    void markFinished() {
        finished = true;
        flush();
        updateTimer.stop();
        cancel.setText("Close");
        cancel.setEnabled(true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    }

    void addActionButton(JButton button) {
        button.putClientProperty(OPERATION_CONTROL, Boolean.TRUE);
        actions.add(button, 0);
        actions.revalidate();
    }

    @Override
    public void onProgress(OperationProgress event) {
        pending.set(event);
        if (event.outcome() != org.megamek.launcher.operation.OperationOutcome.RUNNING) {
            SwingUtilities.invokeLater(this::flush);
        }
    }

    private void requestCancellation() {
        if (finished) {
            dispose();
            return;
        }
        if (context == null) return;
        OperationContext.CancellationRequest request = context.requestCancellation();
        if (request.accepted()) {
            cancel.setEnabled(false);
            cancel.setText("Cancelling...");
            detail.setText(request.reason());
            if (request.closeFailure() != null) {
                append("\nCancellation resource-close warning: "
                        + SanitizedErrors.display(request.closeFailure()) + "\n");
            }
            return;
        }
        cancel.setEnabled(false);
        detail.setText(request.reason());
        JOptionPane.showMessageDialog(this, request.reason(),
                "Cancellation unavailable", JOptionPane.INFORMATION_MESSAGE);
    }

    private void flush() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::flush);
            return;
        }
        OperationProgress event = pending.getAndSet(null);
        if (event == null || !isDisplayable()) return;
        phase.setText(event.phase().displayName());
        detail.setText(event.detail().isBlank() ? " " : event.detail());
        if (event.determinate()) {
            progress.setIndeterminate(false);
            int value = (int) Math.min(100,
                    Math.round(100.0 * event.completed() / event.total()));
            progress.setValue(value);
            if (event.unit() == ProgressUnit.BYTES) {
                progress.setString(NumberFormat.getIntegerInstance().format(event.completed())
                        + " / " + NumberFormat.getIntegerInstance().format(event.total())
                        + " bytes");
            } else if (event.unit() == ProgressUnit.FILES) {
                progress.setString(NumberFormat.getIntegerInstance().format(event.completed())
                        + " / " + NumberFormat.getIntegerInstance().format(event.total())
                        + " files");
            } else {
                progress.setString(value + "%");
            }
        } else {
            progress.setIndeterminate(event.outcome()
                    == org.megamek.launcher.operation.OperationOutcome.RUNNING);
            progress.setString(event.outcome()
                    == org.megamek.launcher.operation.OperationOutcome.RUNNING
                    ? "Working..." : event.outcome().name());
        }
        cancel.setEnabled(event.cancellationAllowed());
        cancel.setToolTipText(event.cancellationAllowed()
                ? "Cancel before installation finalization begins"
                : event.cancellationReason());
        if (!event.cancellationAllowed() && event.cancellationReason() != null
                && !event.cancellationReason().isBlank()) {
            detail.setText(event.cancellationReason());
        }
    }

    private static JButton control(String text, String name) {
        JButton button = new JButton(text);
        button.setName(name);
        button.putClientProperty(OPERATION_CONTROL, Boolean.TRUE);
        button.getAccessibleContext().setAccessibleName(text);
        return button;
    }
}
