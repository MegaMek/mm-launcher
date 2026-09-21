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
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

/**
 * Styled, in-place validated editor for the one new subfolder created below a chosen parent.
 */
final class SubfolderDialog extends JDialog {
    private static final String CANCEL_ACTION = "cancelSubfolder";
    private final GuiScale scale;
    private final JTextField folderName = new JTextField(30);
    private final JLabel error = new JLabel(" ");
    private Path result;

    SubfolderDialog(Window owner, String suggestion, GuiScale scale) {
        super(owner, "Choose install folder", Dialog.ModalityType.APPLICATION_MODAL);
        this.scale = scale;
        setName("subfolderDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName("Choose install folder");
        getAccessibleContext().setAccessibleDescription(
                "Enter one safe new folder name inside the selected parent folder.");

        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        JLabel heading = new JLabel("New install folder");
        heading.setName("subfolderHeading");
        heading.setForeground(FirstLaunchPanel.GOLD);
        heading.setFont(scale.font(base, Font.BOLD, 22f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel prompt = new JLabel("Folder name");
        prompt.setName("subfolderNameLabel");
        prompt.setForeground(FirstLaunchPanel.TEXT);
        prompt.setFont(scale.font(base, Font.BOLD, 13f));
        prompt.setLabelFor(folderName);
        prompt.setAlignmentX(Component.LEFT_ALIGNMENT);

        folderName.setName("subfolderNameField");
        folderName.setText(suggestion == null ? "" : suggestion);
        folderName.selectAll();
        folderName.setFont(scale.font(base, Font.PLAIN, 14f));
        folderName.setBackground(NormalInstallConfirmationDialog.FIELD_BACKGROUND);
        folderName.setForeground(FirstLaunchPanel.TEXT);
        folderName.setCaretColor(FirstLaunchPanel.TEXT);
        folderName.setSelectionColor(HomeLaunchSplitButton.POPUP_SELECTION);
        folderName.setSelectedTextColor(HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND);
        folderName.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new java.awt.Color(90, 119, 120)),
                BorderFactory.createEmptyBorder(scale.scaleForGUI(7),
                        scale.scaleForGUI(9), scale.scaleForGUI(7),
                        scale.scaleForGUI(9))));
        folderName.getAccessibleContext().setAccessibleName("Folder name");
        folderName.getAccessibleContext().setAccessibleDescription(
                "Name of the new subfolder. It must not be a path.");

        error.setName("subfolderValidationError");
        error.setForeground(new java.awt.Color(255, 176, 163));
        error.setFont(scale.font(base, Font.PLAIN, 12f));
        error.setAlignmentX(Component.LEFT_ALIGNMENT);
        error.getAccessibleContext().setAccessibleName("Folder name validation");

        JButton cancel = button("Cancel", "cancelSubfolderButton", false);
        JButton continueButton = button("Continue", "continueSubfolderButton", true);
        continueButton.setMnemonic(KeyEvent.VK_C);
        cancel.addActionListener(event -> cancel());
        continueButton.addActionListener(event -> accept());

        JPanel fields = new JPanel();
        fields.setName("subfolderFields");
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.add(heading);
        fields.add(Box.createVerticalStrut(scale.scaleForGUI(15)));
        fields.add(prompt);
        fields.add(Box.createVerticalStrut(scale.scaleForGUI(6)));
        folderName.setAlignmentX(Component.LEFT_ALIGNMENT);
        folderName.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                folderName.getPreferredSize().height));
        fields.add(folderName);
        fields.add(Box.createVerticalStrut(scale.scaleForGUI(5)));
        fields.add(error);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(Box.createHorizontalGlue());
        actions.add(cancel);
        actions.add(Box.createHorizontalStrut(scale.scaleForGUI(10)));
        actions.add(continueButton);

        JPanel content = new JPanel(new BorderLayout(0, scale.scaleForGUI(14)));
        content.setName("subfolderDialogContent");
        content.setBackground(FirstLaunchPanel.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(20),
                scale.scaleForGUI(22), scale.scaleForGUI(18), scale.scaleForGUI(22)));
        content.add(fields, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(continueButton);
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
                SwingUtilities.invokeLater(() -> {
                    folderName.requestFocusInWindow();
                    folderName.selectAll();
                });
            }
        });

        pack();
        Rectangle available = GuiScale.usableBounds(getGraphicsConfiguration());
        Dimension fitted = GuiScale.fitWindow(scale.scaleForGUI(520, 245), available);
        setMinimumSize(new Dimension(Math.min(scale.scaleForGUI(400), fitted.width),
                Math.min(scale.scaleForGUI(220), fitted.height)));
        setSize(fitted);
        setLocationRelativeTo(owner);
        Rectangle placed = getBounds();
        setLocation(Math.max(available.x, Math.min(placed.x,
                        available.x + available.width - placed.width)),
                Math.max(available.y, Math.min(placed.y,
                        available.y + available.height - placed.height)));
    }

    static Path show(Component parent, String suggestion, GuiScale scale) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        SubfolderDialog dialog = new SubfolderDialog(owner, suggestion, scale);
        dialog.setVisible(true);
        return dialog.result;
    }

    Path result() {
        return result;
    }

    private void accept() {
        try {
            result = LauncherFrame.safeSubfolder(folderName.getText());
            dispose();
        } catch (IllegalArgumentException invalid) {
            error.setText(invalid.getMessage());
            error.setToolTipText(invalid.getMessage());
            getToolkit().beep();
            folderName.requestFocusInWindow();
            folderName.selectAll();
        }
    }

    private void cancel() {
        result = null;
        dispose();
    }

    private JButton button(String text, String name, boolean primary) {
        return new FirstLaunchButton(text, name, primary, scale,
                FirstLaunchButton.Size.SMALL);
    }
}
