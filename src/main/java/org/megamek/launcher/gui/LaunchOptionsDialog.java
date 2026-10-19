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
import java.awt.FlowLayout;
import java.awt.Font;
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
                        Consumer<LaunchOptions> onSave) {
        Objects.requireNonNull(scale, "scale");
        dialog = new JDialog(owner, title, false);
        dialog.setName("launchOptionsDialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel body = new JPanel(new BorderLayout(scale.scaleForGUI(12), scale.scaleForGUI(12)));
        body.setBackground(FirstLaunchPanel.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(20), scale.scaleForGUI(22),
                scale.scaleForGUI(18), scale.scaleForGUI(22)));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        JLabel headingLabel = new JLabel(heading);
        headingLabel.setForeground(FirstLaunchPanel.ACCENT);
        headingLabel.setFont(scale.font(headingLabel.getFont(), Font.BOLD, 20f));
        top.add(headingLabel);
        top.add(Box.createVerticalStrut(scale.scaleForGUI(6)));
        JLabel explanationLabel = new JLabel(explanation);
        explanationLabel.setForeground(FirstLaunchPanel.MUTED);
        top.add(explanationLabel);
        body.add(top, BorderLayout.NORTH);

        form = new LaunchOptionsForm(scale, inherited, current, inheritLabel, () -> message.setText(" "));
        form.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(8), 0, 0, 0));
        JScrollPane scroll = LauncherTheme.scrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setName("launchOptionsScrollPane");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setBackground(FirstLaunchPanel.BACKGROUND);
        body.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, scale.scaleForGUI(8)));
        south.setOpaque(false);
        message.setName("launchOptionsMessage");
        message.setForeground(LauncherTheme.SELECTED);
        south.add(message, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, scale.scaleForGUI(8), 0));
        actions.setOpaque(false);
        JButton cancel = new FirstLaunchButton("Cancel", "cancelLaunchOptionsButton", false, scale,
                FirstLaunchButton.Size.SMALL);
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = new FirstLaunchButton("Save", "saveLaunchOptionsButton", false, scale,
                FirstLaunchButton.Size.SMALL);
        save.addActionListener(event -> {
            LaunchOptions options;
            try {
                options = form.collect();
            } catch (IllegalArgumentException invalid) {
                message.setText(invalid.getMessage());
                return;
            }
            dialog.dispose();
            onSave.accept(options);
        });
        actions.add(cancel);
        actions.add(save);
        south.add(actions, BorderLayout.EAST);
        body.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(body);
        dialog.getRootPane().setDefaultButton(save);
        dialog.pack();
        Rectangle usable = (owner != null ? owner.getGraphicsConfiguration()
                : dialog.getGraphicsConfiguration()).getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
        int maxHeight = usable.height - screenInsets.top - screenInsets.bottom;
        int maxWidth = usable.width - screenInsets.left - screenInsets.right;
        dialog.setSize(Math.min(Math.max(scale.scaleForGUI(760), dialog.getWidth()), maxWidth),
                Math.min(Math.max(scale.scaleForGUI(420), dialog.getHeight()), maxHeight));
        dialog.setMinimumSize(scale.scaleForGUI(560, 420));
        dialog.setLocationRelativeTo(owner);
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