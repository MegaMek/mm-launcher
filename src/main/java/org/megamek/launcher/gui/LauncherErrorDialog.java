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

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.Objects;

/**
 * Themed replacement for the raw {@link javax.swing.JOptionPane}-backed error details dialog,
 * matching the dark background / gold heading / {@link FirstLaunchButton} styling used
 * elsewhere in the launcher. Stays non-modal, like the dialog it replaces, so the main window
 * remains usable while diagnostics are being saved in the background.
 */
final class LauncherErrorDialog extends JDialog {
    private final JLabel logging;

    private LauncherErrorDialog(Window owner, String title, String details, String loggingStatus,
                                GuiScale scale, Runnable copyAction, Runnable viewLogsAction) {
        super(owner, title, ModalityType.MODELESS);
        setName("launcherErrorDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(title);
        getAccessibleContext().setAccessibleDescription(details);

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);

        JLabel heading = new JLabel(title);
        heading.setName("launcherErrorHeading");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(scale.font(base, Font.BOLD, 20f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea area = LauncherFrame.textArea(details);
        area.setName("errorDetails");
        area.setFont(scale.font(base, Font.PLAIN, 13f));
        area.setBackground(NormalInstallConfirmationDialog.FIELD_BACKGROUND);
        area.setForeground(FirstLaunchPanel.TEXT);
        area.setCaretColor(FirstLaunchPanel.TEXT);
        area.setSelectionColor(HomeLaunchSplitButton.POPUP_SELECTION);
        area.setSelectedTextColor(HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND);
        area.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(7),
                scale.scaleForGUI(9), scale.scaleForGUI(7), scale.scaleForGUI(9)));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(90, 119, 120)));
        scroll.setPreferredSize(scale.scaleForGUI(640, 220));
        scroll.getViewport().setBackground(NormalInstallConfirmationDialog.FIELD_BACKGROUND);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);

        logging = new JLabel(loggingStatus);
        logging.setName("errorLoggingStatus");
        logging.setForeground(FirstLaunchPanel.MUTED);
        logging.setFont(scale.font(base, Font.PLAIN, 12f));
        logging.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel fields = new JPanel();
        fields.setName("launcherErrorFields");
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(heading);
        fields.add(Box.createVerticalStrut(scale.scaleForGUI(10)));
        fields.add(logging);
        fields.add(Box.createVerticalStrut(scale.scaleForGUI(10)));
        fields.add(scroll);

        JButton copy = new FirstLaunchButton("Copy sanitized details", "copyErrorDetailsButton",
                false, scale, FirstLaunchButton.Size.SMALL);
        copy.addActionListener(event -> copyAction.run());
        JButton logs = new FirstLaunchButton("View logs", "viewOperationLogsButton",
                false, scale, FirstLaunchButton.Size.SMALL);
        logs.addActionListener(event -> viewLogsAction.run());
        JButton close = new FirstLaunchButton("Close", "closeErrorDialogButton",
                true, scale, FirstLaunchButton.Size.SMALL);
        close.addActionListener(event -> dispose());

        JPanel actions = new JPanel();
        actions.setName("launcherErrorActions");
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(copy);
        actions.add(Box.createHorizontalStrut(scale.scaleForGUI(10)));
        actions.add(logs);
        actions.add(Box.createHorizontalGlue());
        actions.add(close);

        JPanel content = new JPanel(new BorderLayout(0, scale.scaleForGUI(18)));
        content.setName("launcherErrorContent");
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(20),
                scale.scaleForGUI(22), scale.scaleForGUI(18), scale.scaleForGUI(22)));
        content.add(fields, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(close);

        pack();
        Rectangle available = GuiScale.usableBounds(getGraphicsConfiguration());
        Dimension fitted = GuiScale.fitWindow(getSize(), available);
        setMinimumSize(new Dimension(Math.min(scale.scaleForGUI(520), fitted.width),
                Math.min(scale.scaleForGUI(340), fitted.height)));
        setSize(fitted);
        setLocationRelativeTo(owner);
    }

    /** Shows the themed, non-modal error details dialog and returns it so its logging status
     * label can be updated once local diagnostics finish recording. */
    static LauncherErrorDialog show(Window owner, String title, String details,
                                    String loggingStatus, GuiScale scale, Runnable copyAction,
                                    Runnable viewLogsAction) {
        Objects.requireNonNull(copyAction, "copyAction");
        Objects.requireNonNull(viewLogsAction, "viewLogsAction");
        LauncherErrorDialog dialog = new LauncherErrorDialog(owner, title, details, loggingStatus,
                scale, copyAction, viewLogsAction);
        dialog.setVisible(true);
        return dialog;
    }

    void updateLoggingStatus(String message) {
        if (isDisplayable()) logging.setText(message);
    }
}
