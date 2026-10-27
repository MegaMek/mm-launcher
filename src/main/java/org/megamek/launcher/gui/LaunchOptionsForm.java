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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Fields for one layer of {@link LaunchOptions}. Each field can follow the layer below it (built-in defaults for the
 * launcher-wide layer, launcher-wide options for an installation) or carry its own value. Used inline on the
 * Settings page and inside {@link LaunchOptionsDialog}.
 */
final class LaunchOptionsForm extends JPanel implements Scrollable {
    private final GuiScale scale;
    private final LaunchOptions inherited;
    private final Runnable onChange;
    private final List<Row> rows = new ArrayList<>();
    private final Row megamekHeap;
    private final Row mekhqHeap;
    private final Row labHeap;
    private final Row renderer;
    private final Row extraArguments;
    private final Row userDirectory;
    private final JTextField argumentsField;
    private final JTextField userDirField;

    /**
     * @param inherited    fully resolved options that apply when a field follows the lower layer
     * @param current      this layer's own values; {@code null} fields follow {@code inherited}
     * @param inheritLabel checkbox text, e.g. "Use launcher default"
     * @param onChange     called on the EDT after any field changes
     */
    LaunchOptionsForm(GuiScale scale, LaunchOptions inherited, LaunchOptions current, String inheritLabel,
                      Runnable onChange) {
        super(new GridBagLayout());
        this.scale = Objects.requireNonNull(scale, "scale");
        this.inherited = Objects.requireNonNull(inherited, "inherited");
        this.onChange = onChange == null ? () -> { } : onChange;
        Objects.requireNonNull(current, "current");
        setName("launchOptionsForm");
        setOpaque(false);

        megamekHeap = heapRow("MegaMek memory", "megamekHeap",
                current.megamekHeapMb(), inherited.megamekHeapMb(), inheritLabel);
        mekhqHeap = heapRow("MekHQ memory", "mekhqHeap",
                current.mekhqHeapMb(), inherited.mekhqHeapMb(), inheritLabel);
        labHeap = heapRow("MegaMekLab memory", "labHeap",
                current.labHeapMb(), inherited.labHeapMb(), inheritLabel);

        StyledComboBox<LaunchOptions.Renderer> rendererBox =
                new StyledComboBox<>(LaunchOptions.Renderer.values(), scale);
        rendererBox.setName("launchOptionsRenderer");
        rendererBox.getAccessibleContext().setAccessibleName("Graphics rendering");
        rendererBox.setSelectedItem(current.renderer() != null ? current.renderer() : inherited.renderer());
        rendererBox.addActionListener(event -> this.onChange.run());
        renderer = row("Graphics rendering", "renderer", rendererBox,
                current.renderer() == null, inheritLabel + " (" + inherited.renderer().label() + ")",
                () -> rendererBox.setSelectedItem(inherited.renderer()),
                "Try Disable Direct3D or OpenGL if maps flicker.");

        argumentsField = textField(current.extraJvmArguments() != null
                ? current.extraJvmArguments() : inherited.extraJvmArguments(), "launchOptionsJvmArguments");
        argumentsField.getAccessibleContext().setAccessibleName("Extra Java arguments");
        extraArguments = row("Extra Java arguments", "jvmArguments", argumentsField,
                current.extraJvmArguments() == null,
                inheritLabel + " (" + describeText(inherited.extraJvmArguments()) + ")",
                () -> argumentsField.setText(inherited.extraJvmArguments()),
                "Advanced. For example -Dsun.java2d.uiScale=1.5");

        userDirField = textField(current.userDirectory() != null
                ? current.userDirectory() : inherited.userDirectory(), "launchOptionsUserDirectory");
        userDirField.getAccessibleContext().setAccessibleName("User directory");
        JPanel userDirEditor = new JPanel(new BorderLayout(0, scale.space("sm")));
        userDirEditor.setOpaque(false);
        userDirEditor.add(userDirField, BorderLayout.CENTER);
        JPanel userDirButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        userDirButtons.setOpaque(false);
        JButton browse = smallButton("Browse…", "launchOptionsBrowseUserDirectory");
        browse.addActionListener(event -> chooseUserDirectory());
        JButton clear = smallButton("Clear", "launchOptionsClearUserDirectory");
        clear.addActionListener(event -> userDirField.setText(""));
        userDirButtons.add(browse);
        userDirButtons.add(Box.createHorizontalStrut(scale.space("md")));
        userDirButtons.add(clear);
        userDirEditor.add(userDirButtons, BorderLayout.SOUTH);
        userDirectory = row("User directory", "userDirectory", userDirEditor,
                current.userDirectory() == null,
                inheritLabel + " (" + describeText(inherited.userDirectory()) + ")",
                () -> userDirField.setText(inherited.userDirectory()),
                "Shared units and camos. Needs MegaMek 0.51.01 or later.");
        userDirectory.extraEditors = List.of(userDirField, browse, clear);
        userDirectory.refresh();
    }

    /** This layer's values as entered, without validation. */
    LaunchOptions values() {
        return new LaunchOptions(
                heapValue(megamekHeap), heapValue(mekhqHeap), heapValue(labHeap),
                renderer.followsDefault() ? null
                        : (LaunchOptions.Renderer) ((StyledComboBox<?>) renderer.editor).getSelectedItem(),
                extraArguments.followsDefault() ? null : argumentsField.getText().strip(),
                userDirectory.followsDefault() ? null : userDirField.getText().strip());
    }

    /**
     * Returns this layer's values, validated against the inherited layer.
     *
     * @throws IllegalArgumentException with a user-facing message when a value is unusable
     */
    LaunchOptions collect() {
        LaunchOptions options = values();
        options.over(inherited).validate();
        return options;
    }

    private static Integer heapValue(Row row) {
        if (row.followsDefault()) return null;
        Object selected = ((StyledComboBox<?>) row.editor).getSelectedItem();
        return selected instanceof HeapChoice choice ? choice.megabytes() : null;
    }

    private Row heapRow(String label, String name, Integer own, Integer inheritedValue, String inheritLabel) {
        int start = clampHeap(own != null ? own : inheritedValue);
        int inheritedHeap = clampHeap(inheritedValue);
        StyledComboBox<HeapChoice> box = new StyledComboBox<>(HeapChoice.options(start, inheritedHeap), scale);
        box.setName("launchOptions" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
        box.getAccessibleContext().setAccessibleName(label);
        box.setSelectedItem(new HeapChoice(start));
        box.setPreferredSize(new Dimension(scale.scaleForGUI(160), box.getPreferredSize().height));
        box.addActionListener(event -> onChange.run());
        JPanel editor = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        editor.setOpaque(false);
        editor.add(box);
        // The dropdown sits beside the checkbox and already shows the inherited size when following it.
        Row row = row(label, name, editor, own == null, inheritLabel,
                () -> box.setSelectedItem(new HeapChoice(inheritedHeap)), null, true);
        row.editor = box;
        row.extraEditors = List.of(box);
        row.refresh();
        return row;
    }

    private static int clampHeap(Integer value) {
        int heap = value == null ? LaunchOptions.MIN_HEAP_MB : value;
        return Math.max(LaunchOptions.MIN_HEAP_MB, Math.min(LaunchOptions.MAX_HEAP_MB, heap));
    }

    private Row row(String label, String name, JComponent editor, boolean followsDefault,
                    String inheritText, Runnable resetToInherited, String hintText) {
        return row(label, name, editor, followsDefault, inheritText, resetToInherited, hintText, false);
    }

    private Row row(String label, String name, JComponent editor, boolean followsDefault,
                    String inheritText, Runnable resetToInherited, String hintText, boolean inline) {
        int index = rows.size();
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        JLabel title = new JLabel(label);
        title.setForeground(FirstLaunchPanel.TEXT);
        title.setFont(scale.font(title.getFont(), "label"));
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = index * 3;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(index == 0 ? 0 : scale.space("lg"), 0, 0, scale.space("lg"));
        add(title, labelConstraints);

        JCheckBox inherit = new MapCheckBox(inheritText, followsDefault, scale);
        inherit.setName("launchOptions" + capitalized + "Inherit");
        GridBagConstraints inheritConstraints = new GridBagConstraints();
        inheritConstraints.gridx = 1;
        inheritConstraints.gridy = index * 3;
        inheritConstraints.anchor = GridBagConstraints.WEST;
        if (inline) {
            inheritConstraints.gridwidth = 1;
        } else {
            inheritConstraints.gridwidth = GridBagConstraints.REMAINDER;
            inheritConstraints.weightx = 1;
            inheritConstraints.fill = GridBagConstraints.HORIZONTAL;
        }
        inheritConstraints.insets = new Insets(index == 0 ? 0 : scale.space("lg"),
                -inherit.getInsets().left, 0, 0);
        add(inherit, inheritConstraints);

        GridBagConstraints editorConstraints = new GridBagConstraints();
        editorConstraints.anchor = GridBagConstraints.WEST;
        editorConstraints.fill = GridBagConstraints.HORIZONTAL;
        editorConstraints.weightx = 1;
        if (inline) {
            editorConstraints.gridx = 2;
            editorConstraints.gridy = index * 3;
            editorConstraints.insets = new Insets(index == 0 ? 0 : scale.space("lg"),
                    scale.space("md"), 0, 0);
        } else {
            editorConstraints.gridx = 1;
            editorConstraints.gridy = index * 3 + 1;
            editorConstraints.gridwidth = GridBagConstraints.REMAINDER;
            editorConstraints.insets = new Insets(scale.space("xs"), 0, 0, 0);
        }
        add(editor, editorConstraints);
        if (hintText != null) {
            JLabel hint = new JLabel(hintText);
            hint.setForeground(FirstLaunchPanel.MUTED);
            hint.setName("launchOptions" + capitalized + "Hint");
            GridBagConstraints hintConstraints = new GridBagConstraints();
            hintConstraints.gridx = 1;
            hintConstraints.gridy = index * 3 + 2;
            hintConstraints.gridwidth = GridBagConstraints.REMAINDER;
            hintConstraints.anchor = GridBagConstraints.WEST;
            hintConstraints.insets = new Insets(scale.space("xs"), 0, 0, 0);
            add(hint, hintConstraints);
        }

        Row row = new Row(inherit, editor);
        inherit.addActionListener(event -> {
            if (inherit.isSelected()) resetToInherited.run();
            row.refresh();
            onChange.run();
        });
        rows.add(row);
        row.refresh();
        return row;
    }

    private JTextField textField(String text, String name) {
        // At least control.md high, so the field lines up with the buttons beside and below it
        JTextField field = new JTextField(text == null ? "" : text) {
            @Override
            public Dimension getPreferredSize() {
                Dimension preferred = super.getPreferredSize();
                return new Dimension(preferred.width,
                        Math.max(preferred.height, scale.size("control.md")));
            }
        };
        field.setName(name);
        field.setForeground(FirstLaunchPanel.TEXT);
        field.setBackground(LauncherTheme.INPUT_BACKGROUND);
        field.setDisabledTextColor(FirstLaunchPanel.MUTED);
        field.setCaretColor(FirstLaunchPanel.TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LauncherTheme.BORDER),
                scale.padding("sm", "md")));
        field.setFont(scale.font(field.getFont(), "body"));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { onChange.run(); }
            @Override public void removeUpdate(DocumentEvent event) { onChange.run(); }
            @Override public void changedUpdate(DocumentEvent event) { onChange.run(); }
        });
        return field;
    }

    private JButton smallButton(String text, String name) {
        JButton button = new FirstLaunchButton(text, name, false, scale, FirstLaunchButton.Size.SMALL);
        button.getAccessibleContext().setAccessibleDescription("Activate " + text + ".");
        return button;
    }

    private void chooseUserDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose user directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        String existing = userDirField.getText().strip();
        if (!existing.isEmpty()) {
            File start = new File(existing);
            if (start.isDirectory()) chooser.setCurrentDirectory(start);
        }
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(this)) == JFileChooser.APPROVE_OPTION) {
            Path chosen = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            userDirField.setText(chosen.toString());
        }
    }

    static String describeText(String value) {
        return value == null || value.isEmpty() ? "none" : value;
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return LauncherTheme.scrollLine(this, scale);
    }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        int line = LauncherTheme.scrollLine(this, scale);
        return Math.max(line, visible.height - line);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
    }

    /** A maximum-memory choice; common sizes plus any existing value that is not one of them. */
    record HeapChoice(int megabytes) {
        private static final int[] PRESETS = {512, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384,
                24576, 32768, 49152, 65536};

        static HeapChoice[] options(int... required) {
            TreeSet<Integer> values = new TreeSet<>();
            for (int preset : PRESETS) values.add(preset);
            for (int value : required) values.add(value);
            return values.stream().map(HeapChoice::new).toArray(HeapChoice[]::new);
        }

        @Override
        public String toString() {
            return megabytes % 1024 == 0 ? (megabytes / 1024) + " GB (" + megabytes + " MB)" : megabytes + " MB";
        }
    }

    private static final class Row {
        final JCheckBox inherit;
        JComponent editor;
        List<? extends Component> extraEditors = List.of();

        Row(JCheckBox inherit, JComponent editor) {
            this.inherit = inherit;
            this.editor = editor;
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
