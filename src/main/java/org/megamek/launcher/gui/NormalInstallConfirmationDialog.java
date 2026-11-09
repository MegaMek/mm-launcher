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

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

    private final GuiScale scale;
    private final JButton changeLocation;
    private final JButton install;
    private final JButton cancel;
    private final TransitionAction changeAction;
    private final TransitionAction installAction;
    private final Path parentFolder;
    private final JTextField folderName = new JTextField(34);
    private final JLabel folderProblem = new JLabel(" ");
    private JTextField destination;
    private boolean folderUsable = true;
    private boolean transitionInProgress;

    NormalInstallConfirmationDialog(Frame owner, NormalInstallService.Plan plan,
                                    String product, List<String> programs, GuiScale scale,
                                    TransitionAction changeAction,
                                    TransitionAction installAction) {
        super(owner, releaseLabel(plan, product), false);
        this.scale = java.util.Objects.requireNonNull(scale, "scale");
        this.changeAction = java.util.Objects.requireNonNull(changeAction, "changeAction");
        this.installAction = java.util.Objects.requireNonNull(installAction, "installAction");
        this.parentFolder = plan.destination().getParent();

        setName("normalInstallConfirmationDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(getTitle());
        LauncherTheme.useLauncherTitle(this);
        getAccessibleContext().setAccessibleDescription(
                "Review the validated version, included programs, download size, and "
                        + "destination before installing.");

        changeLocation = styledButton("Change location", "changeNormalLocationButton", false);
        changeLocation.getAccessibleContext().setAccessibleDescription(
                "Choose a different parent folder for the install folder, then review a fresh "
                        + "plan.");
        install = styledButton("Install", "confirmNormalInstallButton", true);
        install.setMnemonic(KeyEvent.VK_I);
        install.getAccessibleContext().setAccessibleDescription(
                "Install exactly the displayed validated plan.");
        cancel = styledButton("Cancel", "cancelNormalInstallButton", false);
        cancel.getAccessibleContext().setAccessibleDescription(
                "Close this quote without downloading or installing.");

        JPanel summary = summary(plan, product, programs);

        DialogFooter footer = new DialogFooter(scale).primary(install).cancel(cancel);
        JPanel actions = footer.panel();
        actions.setName("normalInstallConfirmationActions");

        JPanel content = new JPanel(new BorderLayout(0, scale.space("xl")));
        content.setName("normalInstallConfirmationContent");
        content.setBackground(BACKGROUND);
        content.setBorder(scale.padding("xxl", "xxl"));
        content.add(summary, BorderLayout.NORTH);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        changeLocation.addActionListener(event -> attemptTransition(changeAction));
        install.addActionListener(event -> attemptTransition(installAction));
        cancel.addActionListener(event -> cancel());
        footer.install(this, this::cancel);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                SwingUtilities.invokeLater(install::requestFocusInWindow);
            }
        });
        pack();
        resizeAndClamp(new Dimension(Math.max(getWidth(), scale.size("dialog.md")), getHeight()), true);
    }

    private JPanel summary(NormalInstallService.Plan plan, String product,
                           List<String> programs) {
        JPanel summary = new JPanel();
        summary.setName("normalInstallSummary");
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        summary.setBackground(PANEL);
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(scale.size("border"), 0, 0, 0, ACCENT),
                scale.padding("xl", "xl")));

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        JLabel heading = label(releaseLabel(plan, product),
                "normalInstallHeading", scale.font(base, "headline"), TEXT);
        JLabel includes = label("Includes: " + String.join(", ", programs),
                "normalInstallIncludes", scale.font(base, "body"), TEXT);
        JLabel size = label("Download: " + formatBinaryBytes(plan.asset().size()),
                "normalInstallDownloadSize", scale.font(base, "body"), TEXT);
        JLabel destinationLabel = label("Install location", "normalInstallDestinationLabel",
                scale.font(base, "label"), MUTED);

        String destinationText = plan.destination().toString();
        destination = new JTextField(destinationText, 34);
        destination.setName("normalInstallDestination");
        destination.setEditable(false);
        // Long paths scroll; keep the folder being created visible.
        destination.setCaretPosition(destinationText.length());
        destination.setFont(scale.font(base, "body"));
        destination.setBackground(FIELD_BACKGROUND);
        destination.setForeground(TEXT);
        destination.setCaretColor(TEXT);
        destination.setToolTipText(destinationText);
        destination.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LauncherTheme.BORDER),
                scale.padding("sm", "md")));
        destination.getAccessibleContext().setAccessibleName("Install location");
        destination.getAccessibleContext().setAccessibleDescription(
                "Full selected install location. Read only and selectable for copying.");

        JPanel destinationRow = new JPanel(new BorderLayout(scale.space("md"), 0));
        destinationRow.setName("normalInstallDestinationRow");
        destinationRow.setOpaque(false);
        destinationRow.add(destination, BorderLayout.CENTER);
        destinationRow.add(changeLocation, BorderLayout.EAST);

        JLabel folderNameLabel = label("Folder name", "normalInstallFolderNameLabel",
                scale.font(base, "label"), MUTED);
        folderNameLabel.setLabelFor(folderName);
        Path createdFolder = plan.destination().getFileName();
        folderName.setName("normalInstallFolderName");
        folderName.setText(createdFolder == null ? "" : createdFolder.toString());
        folderName.setFont(scale.font(base, "body"));
        folderName.setBackground(FIELD_BACKGROUND);
        folderName.setForeground(TEXT);
        folderName.setCaretColor(TEXT);
        folderName.setSelectionColor(LauncherTheme.SELECTION_BACKGROUND);
        folderName.setSelectedTextColor(LauncherTheme.TEXT);
        folderName.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LauncherTheme.BORDER),
                scale.padding("sm", "md")));
        folderName.getAccessibleContext().setAccessibleName("Folder name");
        folderName.getAccessibleContext().setAccessibleDescription(
                "Name of the new folder the install is created in. It must not be a path.");
        folderName.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { folderNameChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { folderNameChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { folderNameChanged(); }
        });

        folderProblem.setName("normalInstallFolderNameProblem");
        folderProblem.setFont(scale.font(base, "small"));
        folderProblem.setForeground(LauncherTheme.DANGER);
        folderProblem.getAccessibleContext().setAccessibleName("Folder name problem");

        summary.add(heading);
        summary.add(Box.createVerticalStrut(scale.space("lg")));
        summary.add(includes);
        summary.add(Box.createVerticalStrut(scale.space("xs")));
        summary.add(size);
        summary.add(Box.createVerticalStrut(scale.space("xl")));
        summary.add(destinationLabel);
        summary.add(Box.createVerticalStrut(scale.space("xs")));
        summary.add(destinationRow);
        summary.add(Box.createVerticalStrut(scale.space("lg")));
        summary.add(folderNameLabel);
        summary.add(Box.createVerticalStrut(scale.space("xs")));
        folderName.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                folderName.getPreferredSize().height));
        summary.add(folderName);
        summary.add(Box.createVerticalStrut(scale.space("xs")));
        folderProblem.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                folderProblem.getPreferredSize().height));
        summary.add(folderProblem);
        alignLeft(summary);
        return summary;
    }

    /** The folder name as typed, without surrounding whitespace. */
    String folderName() {
        return folderName.getText().strip();
    }

    /** The full install folder for the typed name, or {@code null} while the name is unsafe. */
    Path chosenDestination() {
        try {
            return parentFolder.resolve(LauncherFrame.safeSubfolder(folderName()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Shows why the chosen folder cannot be used and lets the player edit it again. */
    void showFolderProblem(String problem) {
        if (!isDisplayable()) return;
        folderProblem.setText(problem == null || problem.isBlank() ? " " : problem);
        folderProblem.setToolTipText(problem);
        transitionInProgress = false;
        setActionsEnabled(true);
        folderName.requestFocusInWindow();
    }

    private void folderNameChanged() {
        String problem = null;
        Path chosen = null;
        try {
            chosen = parentFolder.resolve(LauncherFrame.safeSubfolder(folderName()));
            if (Files.exists(chosen, LinkOption.NOFOLLOW_LINKS)) {
                problem = "A folder with this name already exists here; choose another name.";
            }
        } catch (IllegalArgumentException invalid) {
            problem = invalid.getMessage();
        }
        if (chosen != null) {
            String text = chosen.toString();
            destination.setText(text);
            destination.setToolTipText(text);
            destination.setCaretPosition(text.length());
        }
        folderProblem.setText(problem == null ? " " : problem);
        folderProblem.setToolTipText(problem);
        folderUsable = problem == null;
        if (!transitionInProgress) install.setEnabled(folderUsable);
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
        install.setEnabled(enabled && folderUsable);
        cancel.setEnabled(enabled);
        folderName.setEditable(enabled);
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
