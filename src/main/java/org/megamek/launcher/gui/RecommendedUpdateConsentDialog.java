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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
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
import java.util.Objects;

/** Styled consent for the complete captured recommended-update operation. */
final class RecommendedUpdateConsentDialog extends JDialog {
    private static final String CANCEL_ACTION = "cancelRecommendedUpdate";

    private final GuiScale scale;
    private final JButton update;
    private boolean approved;

    RecommendedUpdateConsentDialog(Frame owner, String application, String currentVersion,
                                   String newVersion, long downloadBytes, GuiScale scale) {
        super(owner, "Update application", true);
        this.scale = Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(newVersion, "newVersion");

        setName("recommendedUpdateConsentDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(getTitle());
        getAccessibleContext().setAccessibleDescription(
                "Confirm downloading, verifying, planning, and applying the displayed update.");

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);

        JPanel summary = new JPanel();
        summary.setName("recommendedUpdateConsentSummary");
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        summary.setBackground(FirstLaunchPanel.PANEL);
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        scale.scaleForGUI(1), 0, 0, 0, FirstLaunchSplitButton.POPUP_BORDER),
                BorderFactory.createEmptyBorder(scale.scaleForGUI(17),
                        scale.scaleForGUI(18), scale.scaleForGUI(17),
                        scale.scaleForGUI(18))));

        JLabel heading = label("Update this application?", "recommendedUpdateHeading",
                scale.font(base, Font.BOLD, 23f), FirstLaunchPanel.TEXT);
        JLabel operation = label("This will download, verify, plan, and apply the update.",
                "recommendedUpdateOperation",
                scale.font(base, Font.PLAIN, 14f), FirstLaunchPanel.TEXT);
        JLabel closeApplications = label(
                "Close MegaMek, MekHQ, and MegaMekLab before continuing.",
                "recommendedUpdateCloseApplications",
                scale.font(base, Font.PLAIN, 14f), FirstLaunchPanel.TEXT);
        JLabel product = label("Application: " + application,
                "recommendedUpdateApplication",
                scale.font(base, Font.BOLD, 14f), FirstLaunchPanel.TEXT);
        JLabel current = label("Current version: " + displayVersion(currentVersion),
                "recommendedUpdateCurrentVersion",
                scale.font(base, Font.PLAIN, 14f), FirstLaunchPanel.TEXT);
        JLabel target = label("New version: " + displayVersion(newVersion),
                "recommendedUpdateNewVersion",
                scale.font(base, Font.PLAIN, 14f), FirstLaunchPanel.TEXT);
        JLabel size = label("Download size: " + BinarySizeFormat.mebibytes(downloadBytes),
                "recommendedUpdateDownloadSize",
                scale.font(base, Font.PLAIN, 14f), FirstLaunchPanel.TEXT);

        summary.add(heading);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(13)));
        summary.add(operation);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(6)));
        summary.add(closeApplications);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(13)));
        summary.add(product);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(6)));
        summary.add(current);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(6)));
        summary.add(target);
        summary.add(Box.createVerticalStrut(scale.scaleForGUI(6)));
        summary.add(size);

        JButton cancel = button("Cancel", "cancelRecommendedUpdateButton", false);
        update = button("Update", "updateRecommendedUpdateButton", true);
        update.setMnemonic(KeyEvent.VK_U);
        cancel.getAccessibleContext().setAccessibleDescription(
                "Close without downloading or applying the update.");
        update.getAccessibleContext().setAccessibleDescription(
                "Download, verify, plan, and apply the displayed update.");

        JPanel actions = new JPanel();
        actions.setName("recommendedUpdateConsentActions");
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(Box.createHorizontalGlue());
        actions.add(cancel);
        actions.add(Box.createHorizontalStrut(scale.scaleForGUI(10)));
        actions.add(update);

        JPanel content = new JPanel(new BorderLayout(0, scale.scaleForGUI(14)));
        content.setName("recommendedUpdateConsentContent");
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(20),
                scale.scaleForGUI(22), scale.scaleForGUI(20), scale.scaleForGUI(22)));
        content.add(summary, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        cancel.addActionListener(event -> cancel());
        update.addActionListener(event -> approve());
        getRootPane().setDefaultButton(update);
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
                SwingUtilities.invokeLater(update::requestFocusInWindow);
            }
        });

        pack();
        resizeAndClamp(scale.scaleForGUI(560, 370));
    }

    static boolean confirm(Frame owner, String application, String currentVersion,
                           String newVersion, long downloadBytes, GuiScale scale) {
        RecommendedUpdateConsentDialog dialog = new RecommendedUpdateConsentDialog(
                owner, application, currentVersion, newVersion, downloadBytes, scale);
        dialog.setVisible(true);
        return dialog.approved;
    }

    private JLabel label(String text, String name, Font font, java.awt.Color color) {
        JLabel label = new JLabel(text);
        label.setName(name);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.getAccessibleContext().setAccessibleName(text);
        return label;
    }

    private JButton button(String text, String name, boolean primary) {
        return new FirstLaunchButton(
                text, name, primary, scale, FirstLaunchButton.Size.SMALL);
    }

    private void approve() {
        if (!isDisplayable()) return;
        approved = true;
        dispose();
    }

    private void cancel() {
        if (!isDisplayable()) return;
        approved = false;
        dispose();
    }

    private void resizeAndClamp(Dimension requested) {
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
        setLocationRelativeTo(getOwner());
        if (available != null) {
            int x = Math.max(available.x,
                    Math.min(getX(), available.x + available.width - fitted.width));
            int y = Math.max(available.y,
                    Math.min(getY(), available.y + available.height - fitted.height));
            setBounds(x, y, fitted.width, fitted.height);
        }
    }

    private static String displayVersion(String value) {
        return value.length() > 1 && (value.charAt(0) == 'v' || value.charAt(0) == 'V')
                ? value.substring(1) : value;
    }
}
