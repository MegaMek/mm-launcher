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

import org.megamek.launcher.internationalization.I18n;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.Objects;

/**
 * Themed replacement for raw {@link javax.swing.JOptionPane} confirm/message/option dialogs, so
 * these surfaces match the design-token background / accent heading / {@link FirstLaunchButton} styling
 * used everywhere else in the launcher. Its buttons follow the launcher's {@link DialogFooter} rule:
 * centred and equally wide, the main action first (styled primary and the default button, or styled
 * danger and never the default), any other choices next, and Cancel last. Escape dismisses it.
 */
final class LauncherAlertDialog extends JDialog {
    private static final String BUNDLE = "org.megamek.launcher.gui.dialogs";
    static final String CANCEL = I18n.getTextAt(BUNDLE, "LauncherAlertDialog.cancel");

    private final GuiScale scale;
    private String result;

    private LauncherAlertDialog(Window owner, String title, String message, GuiScale scale,
                                JComponent option, boolean danger, String... buttons) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.scale = scale;
        setName("launcherAlertDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(title);
        LauncherTheme.useLauncherTitle(this);
        getAccessibleContext().setAccessibleDescription(message);

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);

        JLabel heading = new JLabel(title);
        heading.setName("launcherAlertHeading");
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(scale.font(base, "headline"));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel body = wrappedLabel(message, "launcherAlertMessage",
                scale.font(base, "body"), FirstLaunchPanel.TEXT,
                scale.size("dialog.sm"));

        JPanel fields = new JPanel();
        fields.setName("launcherAlertFields");
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(heading);
        fields.add(Box.createVerticalStrut(scale.space("lg")));
        fields.add(body);
        if (option != null) {
            option.setAlignmentX(Component.LEFT_ALIGNMENT);
            fields.add(Box.createVerticalStrut(scale.space("lg")));
            fields.add(option);
        }

        DialogFooter footer = new DialogFooter(scale);
        for (int i = 0; i < buttons.length; i++) {
            String label = buttons[i];
            boolean main = i == 0;
            String name = "launcherAlertButton" + i;
            JButton button = main && danger
                    ? FirstLaunchButton.danger(label, name, scale, FirstLaunchButton.Size.SMALL)
                    : new FirstLaunchButton(label, name, main, scale, FirstLaunchButton.Size.SMALL);
            button.addActionListener(event -> {
                result = label;
                dispose();
            });
            if (main) {
                if (danger) footer.danger(button); else footer.primary(button);
            } else if (i == buttons.length - 1 && CANCEL.equals(label)) {
                footer.cancel(button);
            } else {
                footer.secondary(button);
            }
        }
        JPanel actions = footer.panel();
        actions.setName("launcherAlertActions");

        JPanel content = new JPanel(new BorderLayout(0, scale.space("xl")));
        content.setName("launcherAlertContent");
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        content.setBorder(scale.padding("xxl", "xxl"));
        content.add(fields, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        footer.install(this, () -> {
            result = null;
            dispose();
        });

        pack();
        Rectangle available = GuiScale.usableBounds(getGraphicsConfiguration());
        Dimension fitted = GuiScale.fitWindow(getSize(), available);
        setMinimumSize(new Dimension(Math.min(scale.size("dialog.sm"), fitted.width),
                fitted.height));
        setSize(fitted);
        setLocationRelativeTo(owner);
    }

    /** Shows a single-button informational alert and blocks until it is dismissed. */
    static void showMessage(Window owner, GuiScale scale, String title, String message) {
        show(owner, title, message, scale, I18n.getTextAt(BUNDLE, "LauncherAlertDialog.ok"));
    }

    /**
     * Shows a two-button confirmation ({@code confirmText} as the primary main action, then
     * Cancel) and returns whether the user chose {@code confirmText}.
     */
    static boolean showConfirm(Window owner, GuiScale scale, String title, String message,
                                String confirmText) {
        return confirmText.equals(show(owner, title, message, scale, confirmText, CANCEL));
    }

    /**
     * Like {@link #showConfirm} for a destructive action: {@code confirmText} is styled danger,
     * focus starts on Cancel, and Enter never chooses the destructive action unless it has focus.
     */
    static boolean showDangerConfirm(Window owner, GuiScale scale, String title, String message,
                                     String confirmText) {
        LauncherAlertDialog dialog = new LauncherAlertDialog(owner, title, message, scale, null,
                true, confirmText, CANCEL);
        dialog.setVisible(true);
        return confirmText.equals(dialog.result);
    }

    /**
     * Like {@link #showConfirm} with one extra check box below the message. Returns {@code null}
     * when the user did not confirm, otherwise whether the check box was selected.
     */
    static Boolean showConfirmWithOption(Window owner, GuiScale scale, String title,
                                         String message, String optionText,
                                         boolean optionSelected, String confirmText) {
        return showConfirmWithOption(owner, scale, title, message, optionText, optionSelected,
                confirmText, false);
    }

    /** {@link #showConfirmWithOption} whose confirmation is optionally a destructive action. */
    static Boolean showConfirmWithOption(Window owner, GuiScale scale, String title,
                                         String message, String optionText,
                                         boolean optionSelected, String confirmText,
                                         boolean danger) {
        JCheckBox option = new MapCheckBox(optionText, optionSelected, scale);
        option.setName("launcherAlertOption");
        option.setForeground(FirstLaunchPanel.TEXT);
        LauncherAlertDialog dialog = new LauncherAlertDialog(owner, title, message, scale,
                option, danger, confirmText, CANCEL);
        dialog.setVisible(true);
        return confirmText.equals(dialog.result) ? option.isSelected() : null;
    }

    /**
     * Shows a dialog with the given buttons in the order supplied: the first is the primary main
     * action and the default button, and a last {@link #CANCEL} entry is the cancel action.
     * Returns the label of the button chosen, or {@code null} if the dialog was dismissed without
     * choosing one (Escape key or window close).
     */
    static String show(Window owner, String title, String message, GuiScale scale,
                       String... buttons) {
        Objects.requireNonNull(buttons, "buttons");
        if (buttons.length == 0) throw new IllegalArgumentException("At least one button is required");
        LauncherAlertDialog dialog = new LauncherAlertDialog(owner, title, message, scale, null,
                false, buttons);
        dialog.setVisible(true);
        return dialog.result;
    }
    private static JLabel wrappedLabel(String text, String name, Font font, java.awt.Color color,
                                       int widthPixels) {
        JLabel label = new JLabel("<html><body style='width:" + widthPixels + "px'>"
                + escapeHtml(text) + "</body></html>");
        label.setName(name);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.getAccessibleContext().setAccessibleName(text);
        return label;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n\n", "<br><br>").replace("\n", "<br>");
    }
}
