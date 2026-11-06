/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version, as
 * published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project; if not, see
 * <https://www.gnu.org/licenses/>.
 */
package org.megamek.launcher.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.ComponentOrientation;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class DialogFooterTest {
    private static final GuiScale SCALE = GuiScale.DEFAULT;

    @Test
    void actionsAreCentredEquallyWideAndOrderedMainFirstCancelLast() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton main = new JButton("Install");
            JButton other = new JButton("A much longer secondary action");
            JButton cancel = new JButton("Cancel");
            JPanel panel = new DialogFooter(SCALE).primary(main).cancel(cancel).secondary(other).panel();
            panel.setSize(900, panel.getPreferredSize().height);
            panel.doLayout();

            assertTrue(main.getX() < other.getX() && other.getX() < cancel.getX());
            assertEquals(main.getWidth(), other.getWidth());
            assertEquals(main.getWidth(), cancel.getWidth());
            assertTrue(main.getWidth() >= SCALE.scaleForGUI(DialogFooter.MINIMUM_BUTTON_WIDTH));
            int right = panel.getWidth() - (cancel.getX() + cancel.getWidth());
            assertTrue(Math.abs(main.getX() - right) <= 1, "the row is centred");
        });
    }

    @Test
    void toolsSitAtTheLeadingEndAndRightToLeftMirrorsTheRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton tool = new JButton("Copy");
            JButton close = new JButton("Close");
            JPanel panel = new DialogFooter(SCALE).tool(tool).primary(close).panel();
            panel.setSize(900, panel.getPreferredSize().height);
            panel.doLayout();
            assertEquals(0, tool.getX());
            assertTrue(tool.getX() + tool.getWidth() < close.getX());

            panel.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            panel.doLayout();
            assertEquals(panel.getWidth(), tool.getX() + tool.getWidth());
            assertTrue(close.getX() + close.getWidth() < tool.getX());
        });
    }

    @Test
    @Tag("native-gui")
    void primaryIsTheDefaultButtonAndEscapePressesCancel() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "dialogs require a display");
        AtomicInteger cancelled = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog();
            try {
                JButton main = new JButton("Save");
                JButton cancel = new JButton("Cancel");
                cancel.addActionListener(event -> cancelled.incrementAndGet());
                DialogFooter footer = new DialogFooter(SCALE).primary(main).cancel(cancel);
                dialog.setContentPane(footer.panel());
                footer.install(dialog, null);
                assertEquals(main, dialog.getRootPane().getDefaultButton());
                escape(dialog);
                assertEquals(1, cancelled.get());
                Object press = main.getInputMap(JComponent.WHEN_FOCUSED)
                        .get(KeyStroke.getKeyStroke("ENTER"));
                assertNotNull(press, "Enter presses the focused button");
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    @Tag("native-gui")
    void dangerIsNeverTheDefaultButton() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "dialogs require a display");
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog();
            try {
                FirstLaunchButton remove = FirstLaunchButton.danger("Remove", "remove", SCALE,
                        FirstLaunchButton.Size.REGULAR);
                JButton cancel = new JButton("Cancel");
                DialogFooter footer = new DialogFooter(SCALE).danger(remove).cancel(cancel);
                dialog.setContentPane(footer.panel());
                footer.install(dialog, null);
                assertNull(dialog.getRootPane().getDefaultButton());
                dialog.pack();
                dialog.setVisible(true);
                assertEquals(cancel, dialog.getFocusTraversalPolicy().getInitialComponent(dialog));
            } finally {
                dialog.dispose();
            }
        });
    }

    private static void escape(JDialog dialog) {
        dialog.getRootPane().getActionMap().get(DialogFooter.ESCAPE_ACTION)
                .actionPerformed(new ActionEvent(dialog, 0, "escape"));
    }
}
