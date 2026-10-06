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

import org.megamek.launcher.onboarding.NormalInstallService;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Focused consent surface for an immutable normal-install plan. It deliberately owns only
 * presentation and one-shot UI transitions; planning, destination selection, and installation
 * remain with {@link LauncherFrame}.
 */
final class NormalInstallConfirmationDialog extends JDialog {
    static final Color BACKGROUND = FirstLaunchPanel.BACKGROUND;
    static final Color PANEL = FirstLaunchPanel.PANEL;
    static final Color FIELD_BACKGROUND = LauncherTheme.INPUT_BACKGROUND;
    static final Color TEXT = FirstLaunchPanel.TEXT;
    static final Color MUTED = FirstLaunchPanel.MUTED;
    static final Color ACCENT = LauncherTheme.ACCENT;

    private static final String CANCEL_ACTION = "cancelNormalInstallConfirmation";
    private final GuiScale scale;
    private final JButton changeLocation;
    private final JButton install;
    private final JButton cancel;
    private final TransitionAction changeAction;
    private final TransitionAction installAction;
    private boolean transitionInProgress;

    NormalInstallConfirmationDialog(Frame owner, NormalInstallService.Plan plan,
                                    String product, List<String> programs, GuiScale scale,
                                    TransitionAction changeAction,
                                    TransitionAction installAction) {
        super(owner, releaseLabel(plan, product), false);
        this.scale = java.util.Objects.requireNonNull(scale, "scale");
        this.changeAction = java.util.Objects.requireNonNull(changeAction, "changeAction");
        this.installAction = java.util.Objects.requireNonNull(installAction, "installAction");

        setName("normalInstallConfirmationDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(getTitle());
        getAccessibleContext().setAccessibleDescription(
                "Review the validated version, included programs, download size, and "
                        + "destination before installing.");

        changeLocation = styledButton("Change location", "changeNormalLocationButton", false);
        changeLocation.getAccessibleContext().setAccessibleDescription(
                "Choose an existing parent and a new subfolder, then review a fresh plan.");
        install = styledButton("Install", "confirmNormalInstallButton", true);
        install.setMnemonic(KeyEvent.VK_I);
        install.getAccessibleContext().setAccessibleDescription(
                "Install exactly the displayed validated plan.");
        cancel = styledButton("Cancel", "cancelNormalInstallButton", false);
        cancel.getAccessibleContext().setAccessibleDescription(
                "Close this quote without downloading or installing.");

        JPanel summary = summary(plan, product, programs);

        JPanel actions = new JPanel();
        actions.setName("normalInstallConfirmationActions");
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(Box.createHorizontalGlue());
        actions.add(cancel);
        actions.add(Box.createHorizontalStrut(scale.scaleForGUI(10)));
        actions.add(install);

        JPanel content = new JPanel(new BorderLayout(0, scale.scaleForGUI(12)));
        content.setName("normalInstallConfirmationContent");
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(20),
                scale.scaleForGUI(22), scale.scaleForGUI(20), scale.scaleForGUI(22)));
        content.add(summary, BorderLayout.NORTH);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        changeLocation.addActionListener(event -> attemptTransition(changeAction));
        install.addActionListener(event -> attemptTransition(installAction));
        cancel.addActionListener(event -> cancel());
        getRootPane().setDefaultButton(install);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CANCEL_ACTION);
        getRootPane().getActionMap().put(CANCEL_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                cancel();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                SwingUtilities.invokeLater(install::requestFocusInWindow);
            }
        });
        pack();
        resizeAndClamp(scale.scaleForGUI(680, 360), true);
    }

    private JPanel summary(NormalInstallService.Plan plan, String product,
                           List<String> programs) {
        JPanel summary = new JPanel();
        summary.setName("normalInstallSummary");
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        summary.setBackground(PANEL);
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(scale.scaleForGUI(1), 0, 0, 0, ACCENT),
                BorderFactory.createEmptyBorder(scale.scaleForGUI(17),
                        scale.scaleForGUI(18), scale.scaleForGUI(17),
                        scale.scaleForGUI(18))));

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        JLabel heading = label(releaseLabel(plan, product),
                "normalInstallHeading", scale.font(base, Font.BOLD, 23f), TEXT);
        JLabel includes = label("Includes: " + String.join(", ", programs),
                "normalInstallIncludes", scale.font(base, Font.PLAIN, 14f), TEXT);
        JLabel size = label("Download: " + formatBinaryBytes(plan.asset().size()),
                "normalInstallDownloadSize", scale.font(base, Font.PLAIN, 14f), TEXT);
        JLabel destinationLabel = label("Install location", "normalInstallDestinationLabel",
                scale.font(base, Font.BOLD, 13f), MUTED);

        String destinationText = plan.destination().toString();
        JTextArea destination = new JTextArea(destinationText, 3, 34);
        destination.setName("normalInstallDestination");
        destination.setEditable(false);
        destination.setLineWrap(true);
        destination.setWrapStyleWord(false);
        destination.setCaretPosition(0);
        destination.setFont(scale.font(base, Font.PLAIN, 13f));
        destination.setBackground(FIELD_BACKGROUND);
        destination.setForeground(TEXT);
        destination.setCaretColor(TEXT);
        destination.setToolTipText(destinationText);
        destination.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LauncherTheme.BORDER),
                BorderFactory.createEmptyBorder(scale.scaleForGUI(7),
                        scale.scaleForGUI(9), scale.scaleForGUI(7),
                        scale.scaleForGUI(9))));
        destination.getAccessibleContext().setAccessibleName("Install location");
        destination.getAccessibleContext().setAccessibleDescription(
                "Full selected install location. Read only and selectable for copying.");

        JPanel destinationRow = new JPanel(new BorderLayout(scale.scaleForGUI(12), 0));
        destinationRow.setName("normalInstallDestinationRow");
        destinationRow.setOpaque(false);
        destinationRow.add(destination, BorderLayout.CENTER);
        destinationRow.add(changeLocation, BorderLayout.EAST);

        summary.add(heading);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(10)));
        summary.add(includes);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(4)));
        summary.add(size);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(14)));
        summary.add(destinationLabel);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(5)));
        summary.add(destinationRow);
        alignLeft(summary);
        return summary;
    }

    private static String releaseLabel(NormalInstallService.Plan plan, String product) {
        return plan.currentChannelTarget()
                ? VersionDisplay.installLatest(
                product, plan.channel().toString(), plan.version())
                : "Install " + VersionDisplay.programChannelVersion(
                product, plan.channel().toString(), plan.version());
    }

    private JLabel label(String text, String name, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setName(name);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.getAccessibleContext().setAccessibleName(text);
        return label;
    }

    private JButton styledButton(String text, String name, boolean primary) {
        return new FirstLaunchButton(text, name, primary, scale, FirstLaunchButton.Size.SMALL);
    }

    private static void alignLeft(JPanel panel) {
        for (Component component : panel.getComponents()) {
            if (component instanceof JComponent swingComponent) {
                swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
        }
    }

    private void attemptTransition(TransitionAction action) {
        if (transitionInProgress || !isDisplayable()) return;
        transitionInProgress = true;
        setActionsEnabled(false);
        boolean completed = false;
        try {
            completed = action.perform(this);
        } finally {
            if (!completed && isDisplayable()) {
                transitionInProgress = false;
                setActionsEnabled(true);
            }
        }
    }

    private void cancel() {
        if (transitionInProgress || !isDisplayable()) return;
        transitionInProgress = true;
        setActionsEnabled(false);
        dispose();
    }

    private void setActionsEnabled(boolean enabled) {
        changeLocation.setEnabled(enabled);
        install.setEnabled(enabled);
        cancel.setEnabled(enabled);
    }

    private void resizeAndClamp(Dimension requested, boolean centerOnOwner) {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null && getOwner() != null) {
            configuration = getOwner().getGraphicsConfiguration();
        }
        Dimension fitted = requested;
        Rectangle available = null;
        if (configuration != null) {
            available = GuiScale.usableBounds(configuration);
            fitted = GuiScale.fitWindow(requested, available);
        }
        setSize(fitted);
        if (centerOnOwner) setLocationRelativeTo(getOwner());
        if (available != null) {
            int x = Math.max(available.x,
                    Math.min(getX(), available.x + available.width - fitted.width));
            int y = Math.max(available.y,
                    Math.min(getY(), available.y + available.height - fitted.height));
            setBounds(x, y, fitted.width, fitted.height);
        }
    }

    static String formatBinaryBytes(long bytes) {
        return BinarySizeFormat.humanReadable(bytes);
    }

    @FunctionalInterface
    interface TransitionAction {
        boolean perform(NormalInstallConfirmationDialog dialog);
    }
}
