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

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;

/**
 * Guides a player through reporting a launcher problem in two steps: save the sanitized
 * diagnostics, then open the GitHub issue form with the environment already filled in. Problems
 * inside the games are pointed at their own Report a Bug helpers instead.
 *
 * <p>Non-modal, like {@link LauncherErrorDialog}, so the player can keep the launcher and the
 * browser side by side while writing the report.</p>
 */
final class BugReportDialog extends JDialog {
    static final String TITLE = "Report a bug";
    private static final String DISMISS_ACTION = "dismissBugReport";

    /** Callbacks for the dialog's actions; the launcher frame owns choosers, workers and browsing. */
    record Actions(Runnable saveArchive, Runnable openIssueForm, Runnable openDiscord) {
        Actions {
            Objects.requireNonNull(saveArchive, "saveArchive");
            Objects.requireNonNull(openIssueForm, "openIssueForm");
            Objects.requireNonNull(openDiscord, "openDiscord");
        }
    }

    /** The parts of the content the dialog updates after it is shown. */
    record Content(JPanel panel, JButton saveArchive, JButton openIssueForm, JButton close,
                   JLabel archiveStatus) {}

    private final Content content;
    private final int statusWidth;

    private BugReportDialog(Window owner, GuiScale scale, Actions actions) {
        super(owner, TITLE, ModalityType.MODELESS);
        statusWidth = textWidth(scale);
        setName("bugReportDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(TITLE);
        content = content(scale, actions, this::dispose);
        setContentPane(content.panel());
        getRootPane().setDefaultButton(content.openIssueForm());
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), DISMISS_ACTION);
        getRootPane().getActionMap().put(DISMISS_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dispose();
            }
        });

        pack();
        Dimension fitted = fitToScreen();
        setMinimumSize(new Dimension(Math.min(scale.size("dialog.sm"), fitted.width), fitted.height));
        setLocationRelativeTo(owner);
    }

    /** Sizes the dialog to its content but no larger than the usable screen, and keeps it on it. */
    private Dimension fitToScreen() {
        Rectangle available = GuiScale.usableBounds(getGraphicsConfiguration());
        Dimension fitted = GuiScale.fitWindow(getSize(), available);
        setSize(fitted);
        int x = Math.max(available.x, Math.min(getX(), available.x + available.width - fitted.width));
        int y = Math.max(available.y, Math.min(getY(), available.y + available.height - fitted.height));
        setLocation(x, y);
        return fitted;
    }

    static BugReportDialog show(Window owner, GuiScale scale, Actions actions) {
        BugReportDialog dialog = new BugReportDialog(owner, scale, actions);
        dialog.setVisible(true);
        return dialog;
    }

    /** Disables saving while an archive is being written and reports the outcome below the step. */
    void archiveSaving(boolean saving) {
        if (!isDisplayable()) return;
        content.saveArchive().setEnabled(!saving);
        if (saving) showArchiveStatus("Saving diagnostics…", false);
    }

    void showArchiveStatus(String message, boolean problem) {
        if (!isDisplayable()) return;
        JLabel status = content.archiveStatus();
        status.setText("<html><body style='width:" + statusWidth + "px'>" + escape(message)
                + "</body></html>");
        status.getAccessibleContext().setAccessibleName(message);
        status.setForeground(problem ? LauncherTheme.WARNING : FirstLaunchPanel.MUTED);
        status.setVisible(true);
        pack();
        fitToScreen();
    }

    static Content content(GuiScale scale, Actions actions, Runnable close) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        int textWidth = textWidth(scale);

        JLabel heading = new JLabel(TITLE);
        heading.setName("bugReportHeading");
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(scale.font(base, "headline"));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel intro = wrapped("Thanks for helping us fix the launcher. For problems inside "
                        + "MegaMek, MegaMekLab or MekHQ, use Help, then Report a Bug, in that "
                        + "program instead.", "bugReportIntro", scale.font(base, "body"),
                FirstLaunchPanel.TEXT, scale.size("dialog.md") - scale.space("xxl") * 2);

        JButton save = new FirstLaunchButton("Save diagnostics ZIP…", "saveBugReportArchiveButton",
                false, scale, FirstLaunchButton.Size.SMALL);
        save.getAccessibleContext().setAccessibleDescription(
                "Saves the launcher's sanitized logs and system details as one ZIP file");
        save.addActionListener(event -> actions.saveArchive().run());
        // Enabled only by archiveSaving, so the frame's busy handling must not snapshot or restore it.
        save.putClientProperty(OperationProgressDialog.OPERATION_CONTROL, Boolean.TRUE);
        JButton open = new FirstLaunchButton("Open bug report form", "openBugReportFormButton",
                true, scale, FirstLaunchButton.Size.SMALL);
        open.getAccessibleContext().setAccessibleDescription(
                "Opens the launcher's GitHub bug report form in your browser");
        open.addActionListener(event -> actions.openIssueForm().run());
        Dimension shared = new Dimension(
                Math.max(save.getPreferredSize().width, open.getPreferredSize().width),
                Math.max(save.getPreferredSize().height, open.getPreferredSize().height));
        for (JButton button : new JButton[]{save, open}) {
            button.setPreferredSize(shared);
            button.setMinimumSize(shared);
            button.setMaximumSize(shared);
        }

        JLabel status = new JLabel(" ");
        status.setName("bugReportArchiveStatus");
        status.setFont(scale.font(base, "small"));
        status.setForeground(FirstLaunchPanel.MUTED);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setVisible(false);

        JPanel saveStep = step(scale, base, textWidth, "bugReportSaveStep",
                "1. Save the diagnostics",
                "One ZIP with the launcher's sanitized logs and system details. Review it before "
                        + "sharing; it can contain local folder names.", save, status);
        JPanel formStep = step(scale, base, textWidth, "bugReportFormStep",
                "2. Describe the problem on GitHub",
                "Opens the bug form with your launcher version, system and Java already filled "
                        + "in. Attach the ZIP there.", open, null);

        JPanel fields = new JPanel();
        fields.setName("bugReportFields");
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(heading);
        fields.add(Box.createVerticalStrut(scale.space("md")));
        fields.add(intro);
        fields.add(Box.createVerticalStrut(scale.space("xl")));
        fields.add(saveStep);
        fields.add(Box.createVerticalStrut(scale.space("lg")));
        fields.add(formStep);

        JButton discord = new LauncherLink("Ask on Discord", "bugReportDiscordButton", scale);
        discord.getAccessibleContext().setAccessibleDescription(
                "Opens the MegaMek community Discord invitation in your browser");
        discord.addActionListener(event -> actions.openDiscord().run());
        JButton closeButton = new FirstLaunchButton("Close", "closeBugReportButton", false, scale,
                FirstLaunchButton.Size.SMALL);
        closeButton.addActionListener(event -> close.run());

        JPanel footer = new JPanel();
        footer.setName("bugReportActions");
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.X_AXIS));
        footer.add(discord);
        footer.add(Box.createHorizontalGlue());
        footer.add(closeButton);

        JPanel panel = new JPanel(new BorderLayout(0, scale.space("xl")));
        panel.setName("bugReportContent");
        panel.setBackground(FirstLaunchPanel.BACKGROUND);
        panel.setBorder(scale.padding("xxl", "xxl"));
        panel.add(fields, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return new Content(panel, save, open, closeButton, status);
    }

    /** A numbered step: its title and explanation on the left, its action vertically centered on the right. */
    private static JPanel step(GuiScale scale, Font base, int textWidth, String name, String title,
                               String explanation, JButton action, JLabel status) {
        JLabel heading = new JLabel(title);
        heading.setName(name + "Title");
        heading.setFont(scale.font(base, "title"));
        heading.setForeground(FirstLaunchPanel.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(heading);
        text.add(Box.createVerticalStrut(scale.space("xs")));
        text.add(wrapped(explanation, name + "Explanation", scale.font(base, "small"),
                FirstLaunchPanel.MUTED, textWidth));
        if (status != null) {
            text.add(Box.createVerticalStrut(scale.space("xs")));
            text.add(status);
        }

        JPanel button = new JPanel(new GridBagLayout());
        button.setOpaque(false);
        button.add(action);

        JPanel row = new JPanel(new BorderLayout(scale.space("xl"), 0));
        row.setName(name);
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(text, BorderLayout.CENTER);
        row.add(button, BorderLayout.EAST);
        return row;
    }

    private static int textWidth(GuiScale scale) {
        return scale.size("dialog.sm") - scale.space("xxl");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JLabel wrapped(String text, String name, Font font, Color color, int widthPixels) {
        JLabel label = new JLabel("<html><body style='width:" + widthPixels + "px'>"
                + escape(text) + "</body></html>");
        label.setName(name);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.getAccessibleContext().setAccessibleName(text);
        return label;
    }
}
