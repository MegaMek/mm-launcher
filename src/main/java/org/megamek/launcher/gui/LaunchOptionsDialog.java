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

import org.megamek.launcher.launch.LaunchOptions;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.Objects;
import java.util.function.Consumer;

/** Edits one installation's {@link LaunchOptions} overrides in a {@link LaunchOptionsForm}. */
final class LaunchOptionsDialog {
    private final JDialog dialog;
    private final LaunchOptionsForm form;
    private final JLabel message = new JLabel(" ");

    LaunchOptionsDialog(JFrame owner, GuiScale scale, String title, String heading, String explanation,
                        LaunchOptions inherited, LaunchOptions current, String inheritLabel,
                        Consumer<LaunchOptions> onSave, Consumer<LaunchOptions> onCopyCommands) {
        Objects.requireNonNull(scale, "scale");
        dialog = new JDialog(owner, title, false);
        LauncherTheme.useLauncherTitle(dialog);
        dialog.setName("launchOptionsDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel body = new JPanel(new BorderLayout(scale.space("lg"), scale.space("lg")));
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(scale.padding("xxl", "xxl"));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        JLabel headingLabel = new JLabel(heading);
        headingLabel.setForeground(FirstLaunchPanel.ACCENT);
        headingLabel.setFont(scale.font(headingLabel.getFont(), "headline"));
        // Resetting edits the fields and still needs Save, so it sits with the form rather than the footer.
        JButton restore = new LauncherLink("Reset all to launcher defaults",
                "restoreLaunchOptionsDefaultsButton", scale);
        restore.getAccessibleContext().setAccessibleDescription(
                "Set every option to follow the launcher default. Save to keep the change.");
        JPanel headingRow = new JPanel(new BorderLayout(scale.space("lg"), 0));
        headingRow.setOpaque(false);
        headingRow.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        headingRow.add(headingLabel, BorderLayout.CENTER);
        headingRow.add(restore, BorderLayout.EAST);
        top.add(headingRow);
        top.add(Box.createVerticalStrut(scale.space("xs")));
        JLabel explanationLabel = new JLabel(explanation);
        explanationLabel.setForeground(FirstLaunchPanel.MUTED);
        explanationLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        top.add(explanationLabel);
        body.add(top, BorderLayout.NORTH);

        form = new LaunchOptionsForm(scale, inherited, current, inheritLabel, () -> message.setText(" "));
        restore.addActionListener(event -> form.restoreDefaults());
        form.setBorder(BorderFactory.createEmptyBorder(scale.space("md"), 0, 0, 0));
        JScrollPane scroll = LauncherTheme.scrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setName("launchOptionsScrollPane");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setBackground(FirstLaunchPanel.BACKGROUND);
        body.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, scale.space("md")));
        south.setOpaque(false);
        message.setName("launchOptionsMessage");
        message.setForeground(LauncherTheme.WARNING);
        south.add(message, BorderLayout.NORTH);
        JButton cancel = new FirstLaunchButton("Cancel", "cancelLaunchOptionsButton", false, scale,
                FirstLaunchButton.Size.SMALL);
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = new FirstLaunchButton("Save", "saveLaunchOptionsButton", true, scale,
                FirstLaunchButton.Size.SMALL);
        save.addActionListener(event -> {
            LaunchOptions options = collect();
            if (options == null) return;
            dialog.dispose();
            onSave.accept(options);
        });
        DialogFooter footer = new DialogFooter(scale);
        if (onCopyCommands != null) {
            JButton copy = new LauncherLink("Copy launch commands", "copyLaunchCommandsButton", scale);
            copy.getAccessibleContext().setAccessibleDescription(
                    "Copy the commands that start each application with the options shown, for troubleshooting");
            copy.addActionListener(event -> {
                LaunchOptions options = collect();
                if (options != null) onCopyCommands.accept(options);
            });
            footer.tool(copy);
        }
        footer.primary(save).cancel(cancel);
        JPanel actions = footer.panel();
        actions.setName("launchOptionsActions");
        south.add(actions, BorderLayout.CENTER);
        body.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(body);
        footer.install(dialog, dialog::dispose);
        // Start on the first option rather than the reset link above the form.
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent event) {
                java.awt.Component first = dialog.getFocusTraversalPolicy().getComponentAfter(dialog, restore);
                if (first != null) first.requestFocusInWindow();
            }
        });
        dialog.pack();
        Rectangle usable = (owner != null ? owner.getGraphicsConfiguration()
                : dialog.getGraphicsConfiguration()).getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
        int maxHeight = usable.height - screenInsets.top - screenInsets.bottom;
        int maxWidth = usable.width - screenInsets.left - screenInsets.right;
        dialog.setSize(Math.min(Math.max(scale.size("dialog.lg"), dialog.getWidth()), maxWidth),
                Math.min(Math.max(scale.size("dialog.sm"), dialog.getHeight()), maxHeight));
        dialog.setMinimumSize(new Dimension(scale.size("dialog.md"), scale.size("dialog.sm")));
        dialog.setLocationRelativeTo(owner);
    }

    private LaunchOptions collect() {
        try {
            return form.collect();
        } catch (IllegalArgumentException invalid) {
            message.setForeground(LauncherTheme.WARNING);
            message.setText(invalid.getMessage());
            return null;
        }
    }

    /** Shows a confirmation under the form until the next edit. */
    void note(String text) {
        message.setForeground(FirstLaunchPanel.MUTED);
        message.setText(text);
    }

    JDialog dialog() {
        return dialog;
    }

    LaunchOptionsForm form() {
        return form;
    }

    void show() {
        dialog.setVisible(true);
    }
}