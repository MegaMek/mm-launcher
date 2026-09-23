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
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;

/**
 * Themed replacement for raw {@link javax.swing.JOptionPane} confirm/message/option dialogs, so
 * these surfaces match the dark background / gold heading / {@link FirstLaunchButton} styling
 * used everywhere else in the launcher. Buttons are laid out left to right with the least
 * committal action (typically "Cancel") on the left and the recommended action rightmost,
 * styled primary, and bound as the default button — the same ordering convention already used
 * by {@link SubfolderDialog} and {@link NormalInstallConfirmationDialog}.
 */
final class LauncherAlertDialog extends JDialog {
    private static final String DISMISS_ACTION = "dismissLauncherAlert";

    private final GuiScale scale;
    private String result;

    private LauncherAlertDialog(Window owner, String title, String message, GuiScale scale,
                                String... buttons) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.scale = scale;
        setName("launcherAlertDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(title);
        getAccessibleContext().setAccessibleDescription(message);

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);

        JLabel heading = new JLabel(title);
        heading.setName("launcherAlertHeading");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(scale.font(base, Font.BOLD, 20f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel body = wrappedLabel(message, "launcherAlertMessage",
                scale.font(base, Font.PLAIN, 14f), FirstLaunchPanel.TEXT,
                scale.scaleForGUI(420));

        JPanel fields = new JPanel();
        fields.setName("launcherAlertFields");
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(heading);
        fields.add(Box.createVerticalStrut(scale.scaleForGUI(14)));
        fields.add(body);

        JPanel actions = new JPanel();
        actions.setName("launcherAlertActions");
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(Box.createHorizontalGlue());
        JButton defaultButton = null;
        for (int i = 0; i < buttons.length; i++) {
            boolean primary = i == buttons.length - 1;
            String label = buttons[i];
            JButton button = new FirstLaunchButton(label, "launcherAlertButton" + i, primary,
                    scale, FirstLaunchButton.Size.SMALL);
            button.addActionListener(event -> {
                result = label;
                dispose();
            });
            if (i > 0) actions.add(Box.createHorizontalStrut(scale.scaleForGUI(10)));
            actions.add(button);
            if (primary) defaultButton = button;
        }

        JPanel content = new JPanel(new BorderLayout(0, scale.scaleForGUI(18)));
        content.setName("launcherAlertContent");
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(20),
                scale.scaleForGUI(22), scale.scaleForGUI(18), scale.scaleForGUI(22)));
        content.add(fields, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        if (defaultButton != null) getRootPane().setDefaultButton(defaultButton);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), DISMISS_ACTION);
        getRootPane().getActionMap().put(DISMISS_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                result = null;
                dispose();
            }
        });

        pack();
        Rectangle available = GuiScale.usableBounds(getGraphicsConfiguration());
        Dimension fitted = GuiScale.fitWindow(getSize(), available);
        setMinimumSize(new Dimension(Math.min(scale.scaleForGUI(360), fitted.width),
                Math.min(scale.scaleForGUI(160), fitted.height)));
        setSize(fitted);
        setLocationRelativeTo(owner);
    }

    /** Shows a single-button informational alert and blocks until it is dismissed. */
    static void showMessage(Window owner, GuiScale scale, String title, String message) {
        show(owner, title, message, scale, "OK");
    }

    /**
     * Shows a two-button confirmation ("Cancel" plus {@code confirmText}, the latter styled
     * primary and rightmost) and returns whether the user chose {@code confirmText}.
     */
    static boolean showConfirm(Window owner, GuiScale scale, String title, String message,
                                String confirmText) {
        return confirmText.equals(show(owner, title, message, scale, "Cancel", confirmText));
    }

    /**
     * Shows a dialog with the given buttons, ordered left to right exactly as supplied (the
     * last entry is styled primary, rightmost, and bound as the default button). Returns the
     * label of the button chosen, or {@code null} if the dialog was dismissed without choosing
     * one (Escape key or window close).
     */
    static String show(Window owner, String title, String message, GuiScale scale,
                       String... buttons) {
        Objects.requireNonNull(buttons, "buttons");
        if (buttons.length == 0) throw new IllegalArgumentException("At least one button is required");
        LauncherAlertDialog dialog = new LauncherAlertDialog(owner, title, message, scale, buttons);
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
