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
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Edits one layer of {@link LaunchOptions}. Each field can follow the layer below it (built-in defaults for the
 * launcher-wide layer, launcher-wide options for an installation) or carry its own value.
 */
final class LaunchOptionsDialog {
    private final JDialog dialog;
    private final GuiScale scale;
    private final LaunchOptions inherited;
    private final JLabel message = new JLabel(" ");
    private final List<Row> rows = new ArrayList<>();
    private final Row megamekHeap;
    private final Row mekhqHeap;
    private final Row labHeap;
    private final Row renderer;
    private final Row extraArguments;
    private final Row userDirectory;
    private final JTextField userDirField;

    /**
     * @param inherited     fully resolved options that apply when a field follows the lower layer
     * @param current       this layer's own values; {@code null} fields follow {@code inherited}
     * @param inheritLabel  checkbox text, e.g. "Use launcher default"
     */
    LaunchOptionsDialog(JFrame owner, GuiScale scale, String title, String heading, String explanation,
                        LaunchOptions inherited, LaunchOptions current, String inheritLabel,
                        Consumer<LaunchOptions> onSave) {
        this.scale = Objects.requireNonNull(scale, "scale");
        this.inherited = Objects.requireNonNull(inherited, "inherited");
        Objects.requireNonNull(current, "current");
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
        top.add(javax.swing.Box.createVerticalStrut(scale.scaleForGUI(6)));
        top.add(mutedLabel(explanation));
        body.add(top, BorderLayout.NORTH);

        JPanel fields = new FieldsPanel(scale);
        fields.setBackground(FirstLaunchPanel.BACKGROUND);
        megamekHeap = heapRow(fields, "MegaMek memory", "megamekHeap",
                current.megamekHeapMb(), inherited.megamekHeapMb(), inheritLabel);
        mekhqHeap = heapRow(fields, "MekHQ memory", "mekhqHeap",
                current.mekhqHeapMb(), inherited.mekhqHeapMb(), inheritLabel);
        labHeap = heapRow(fields, "MegaMekLab memory", "labHeap",
                current.labHeapMb(), inherited.labHeapMb(), inheritLabel);

        StyledComboBox<LaunchOptions.Renderer> rendererBox =
                new StyledComboBox<>(LaunchOptions.Renderer.values(), scale);
        rendererBox.setName("launchOptionsRenderer");
        rendererBox.setSelectedItem(current.renderer() != null ? current.renderer() : inherited.renderer());
        renderer = row(fields, "Graphics rendering", "renderer", rendererBox,
                current.renderer() == null, inheritLabel + " (" + inherited.renderer().label() + ")",
                () -> rendererBox.setSelectedItem(inherited.renderer()),
                "Try “Disable Direct3D” or “OpenGL” if maps flicker or draw slowly.");

        JTextField argumentsField = textField(current.extraJvmArguments() != null
                ? current.extraJvmArguments() : inherited.extraJvmArguments(), "launchOptionsJvmArguments");
        argumentsField.getAccessibleContext().setAccessibleName("Extra Java arguments");
        extraArguments = row(fields, "Extra Java arguments", "jvmArguments", argumentsField,
                current.extraJvmArguments() == null,
                inheritLabel + " (" + describeText(inherited.extraJvmArguments()) + ")",
                () -> argumentsField.setText(inherited.extraJvmArguments()),
                "Advanced. For example -Dsun.java2d.uiScale=1.5. Memory is set above.");

        JTextField userDirField = textField(current.userDirectory() != null
                ? current.userDirectory() : inherited.userDirectory(), "launchOptionsUserDirectory");
        this.userDirField = userDirField;
        userDirField.getAccessibleContext().setAccessibleName("User directory");
        JPanel userDirEditor = new JPanel(new BorderLayout(0, scale.scaleForGUI(6)));
        userDirEditor.setOpaque(false);
        userDirEditor.add(userDirField, BorderLayout.CENTER);
        JPanel userDirButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        userDirButtons.setOpaque(false);
        JButton browse = smallButton("Browse…", "launchOptionsBrowseUserDirectory");
        browse.addActionListener(event -> chooseUserDirectory(userDirField));
        JButton clear = smallButton("Clear", "launchOptionsClearUserDirectory");
        clear.addActionListener(event -> userDirField.setText(""));
        userDirButtons.add(browse);
        userDirButtons.add(javax.swing.Box.createHorizontalStrut(scale.scaleForGUI(8)));
        userDirButtons.add(clear);
        userDirEditor.add(userDirButtons, BorderLayout.SOUTH);
        userDirectory = row(fields, "User directory", "userDirectory", userDirEditor,
                current.userDirectory() == null,
                inheritLabel + " (" + describeText(inherited.userDirectory()) + ")",
                () -> userDirField.setText(inherited.userDirectory()),
                "Shared folder for custom units, portraits, and camos. "
                        + "Needs a MegaMek release that supports it.");
        userDirectory.extraEditors = List.of(userDirField, browse, clear);
        userDirectory.refresh();

        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = rows.size() * 3;
        filler.weighty = 1;
        fields.add(javax.swing.Box.createVerticalGlue(), filler);
        JScrollPane scroll = LauncherTheme.scrollPane(fields, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
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
        JButton cancel = smallButton("Cancel", "cancelLaunchOptionsButton");
        cancel.addActionListener(event -> dialog.dispose());
        JButton save = smallButton("Save", "saveLaunchOptionsButton");
        save.addActionListener(event -> {
            LaunchOptions options;
            try {
                options = collect();
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
        java.awt.Insets screenInsets = java.awt.Toolkit.getDefaultToolkit()
                .getScreenInsets(dialog.getGraphicsConfiguration());
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

    void show() {
        dialog.setVisible(true);
    }

    /** Returns this layer's values, validated against the inherited layer. */
    LaunchOptions collect() {
        LaunchOptions options = new LaunchOptions(
                heapValue(megamekHeap), heapValue(mekhqHeap), heapValue(labHeap),
                renderer.followsDefault() ? null
                        : (LaunchOptions.Renderer) ((StyledComboBox<?>) renderer.editor).getSelectedItem(),
                extraArguments.followsDefault() ? null : ((JTextField) extraArguments.editor).getText().strip(),
                userDirectory.followsDefault() ? null : userDirField.getText().strip());
        options.over(inherited).validate();
        return options;
    }

    private static Integer heapValue(Row row) {
        if (row.followsDefault()) return null;
        Object selected = ((StyledComboBox<?>) row.editor).getSelectedItem();
        return selected instanceof HeapChoice choice ? choice.megabytes() : null;
    }

    private Row heapRow(JPanel fields, String label, String name, Integer own, Integer inheritedValue,
                        String inheritLabel) {
        int start = clampHeap(own != null ? own : inheritedValue);
        int inheritedHeap = clampHeap(inheritedValue);
        StyledComboBox<HeapChoice> box = new StyledComboBox<>(HeapChoice.options(start, inheritedHeap), scale);
        box.setName("launchOptions" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
        box.getAccessibleContext().setAccessibleName(label);
        box.setSelectedItem(new HeapChoice(start));
        box.setPreferredSize(new Dimension(scale.scaleForGUI(160), box.getPreferredSize().height));
        JPanel editor = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        editor.setOpaque(false);
        editor.add(box);
        Row row = row(fields, label, name, editor, own == null,
                inheritLabel + " (" + new HeapChoice(inheritedHeap) + ")",
                () -> box.setSelectedItem(new HeapChoice(inheritedHeap)), null);
        row.editor = box;
        row.extraEditors = List.of(box);
        row.refresh();
        return row;
    }

    /** A maximum-memory choice; common sizes plus any existing value that is not one of them. */
    record HeapChoice(int megabytes) {
        private static final int[] PRESETS = {512, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384,
                24576, 32768, 49152, 65536};

        static HeapChoice[] options(int... required) {
            java.util.TreeSet<Integer> values = new java.util.TreeSet<>();
            for (int preset : PRESETS) values.add(preset);
            for (int value : required) values.add(value);
            return values.stream().map(HeapChoice::new).toArray(HeapChoice[]::new);
        }

        @Override
        public String toString() {
            return megabytes % 1024 == 0 ? (megabytes / 1024) + " GB (" + megabytes + " MB)" : megabytes + " MB";
        }
    }

    /** Fills the scroll pane's width so rows wrap to the dialog instead of scrolling sideways. */
    private static final class FieldsPanel extends JPanel implements Scrollable {
        private final GuiScale scale;

        FieldsPanel(GuiScale scale) {
            super(new GridBagLayout());
            this.scale = scale;
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return scale.scaleForGUI(18);
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(scale.scaleForGUI(18), visible.height - scale.scaleForGUI(18));
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    private static int clampHeap(Integer value) {
        int heap = value == null ? LaunchOptions.MIN_HEAP_MB : value;
        return Math.max(LaunchOptions.MIN_HEAP_MB, Math.min(LaunchOptions.MAX_HEAP_MB, heap));
    }

    private Row row(JPanel fields, String label, String name, JComponent editor, boolean followsDefault,
                    String inheritText, Runnable resetToInherited, String hintText) {
        int index = rows.size();
        JLabel title = new JLabel(label);
        title.setForeground(FirstLaunchPanel.TEXT);
        title.setFont(scale.font(title.getFont(), Font.BOLD, 13f));
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = index * 3;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(scale.scaleForGUI(12), 0, 0, scale.scaleForGUI(12));
        fields.add(title, labelConstraints);

        JCheckBox inherit = new MapCheckBox(inheritText, followsDefault, scale);
        inherit.setName("launchOptions" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Inherit");
        GridBagConstraints inheritConstraints = new GridBagConstraints();
        inheritConstraints.gridx = 1;
        inheritConstraints.gridy = index * 3;
        inheritConstraints.anchor = GridBagConstraints.WEST;
        inheritConstraints.weightx = 1;
        inheritConstraints.fill = GridBagConstraints.HORIZONTAL;
        inheritConstraints.insets = new Insets(scale.scaleForGUI(12), -inherit.getInsets().left, 0, 0);
        fields.add(inherit, inheritConstraints);

        GridBagConstraints editorConstraints = new GridBagConstraints();
        editorConstraints.gridx = 1;
        editorConstraints.gridy = index * 3 + 1;
        editorConstraints.anchor = GridBagConstraints.WEST;
        editorConstraints.fill = GridBagConstraints.HORIZONTAL;
        editorConstraints.weightx = 1;
        editorConstraints.insets = new Insets(scale.scaleForGUI(4), 0, 0, 0);
        fields.add(editor, editorConstraints);
        if (hintText != null) {
            JLabel hint = mutedLabel(hintText);
            hint.setName("launchOptions" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Hint");
            GridBagConstraints hintConstraints = new GridBagConstraints();
            hintConstraints.gridx = 1;
            hintConstraints.gridy = index * 3 + 2;
            hintConstraints.anchor = GridBagConstraints.WEST;
            hintConstraints.insets = new Insets(scale.scaleForGUI(3), 0, 0, 0);
            fields.add(hint, hintConstraints);
        }

        Row row = new Row(inherit, editor, resetToInherited);
        inherit.addActionListener(event -> {
            if (inherit.isSelected()) resetToInherited.run();
            row.refresh();
            message.setText(" ");
        });
        rows.add(row);
        row.refresh();
        return row;
    }

    private JLabel mutedLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(FirstLaunchPanel.MUTED);
        return label;
    }

    private JTextField textField(String text, String name) {
        JTextField field = new JTextField(text == null ? "" : text);
        field.setName(name);
        field.setForeground(FirstLaunchPanel.TEXT);
        field.setBackground(LauncherTheme.INPUT_BACKGROUND);
        field.setDisabledTextColor(FirstLaunchPanel.MUTED);
        field.setCaretColor(FirstLaunchPanel.TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LauncherTheme.BORDER),
                BorderFactory.createEmptyBorder(scale.scaleForGUI(6), scale.scaleForGUI(8),
                        scale.scaleForGUI(6), scale.scaleForGUI(8))));
        field.setFont(scale.font(field.getFont(), Font.PLAIN, 14f));
        return field;
    }

    private JButton smallButton(String text, String name) {
        JButton button = new FirstLaunchButton(text, name, false, scale, FirstLaunchButton.Size.SMALL);
        button.getAccessibleContext().setAccessibleDescription("Activate " + text + ".");
        return button;
    }

    private void chooseUserDirectory(JTextField field) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose user directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        String existing = field.getText().strip();
        if (!existing.isEmpty()) {
            File start = new File(existing);
            if (start.isDirectory()) chooser.setCurrentDirectory(start);
        }
        if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            Path chosen = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            field.setText(chosen.toString());
        }
    }

    static String describeText(String value) {
        return value == null || value.isEmpty() ? "none" : value;
    }

    private static final class Row {
        final JCheckBox inherit;
        JComponent editor;
        List<? extends Component> extraEditors = List.of();
        final Runnable resetToInherited;

        Row(JCheckBox inherit, JComponent editor, Runnable resetToInherited) {
            this.inherit = inherit;
            this.editor = editor;
            this.resetToInherited = resetToInherited;
        }

        boolean followsDefault() {
            return inherit.isSelected();
        }

        void refresh() {
            boolean editable = !inherit.isSelected();
            editor.setEnabled(editable);
            for (Component component : extraEditors) component.setEnabled(editable);
        }
    }
}
