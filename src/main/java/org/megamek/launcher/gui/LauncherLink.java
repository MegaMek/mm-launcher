/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 */

package org.megamek.launcher.gui;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Line2D;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keyboard-operable, plain-text links with wrapped underlined titles and no button plate. */
final class LauncherLink extends JButton {
    private final GuiScale scale;

    LauncherLink(String text, String name, GuiScale scale) {
        this.scale = Objects.requireNonNull(scale, "scale");
        putClientProperty("html.disable", Boolean.TRUE);
        setText(Objects.requireNonNull(text, "text"));
        setName(name);
        setUI(new BasicButtonUI());
        setFont(scale.font(getFont(), Font.PLAIN, 13f));
        setForeground(LauncherTheme.highContrast()
                ? LauncherTheme.uiColor("Button.foreground", Color.WHITE) : LauncherTheme.ACCENT);
        setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(4), scale.scaleForGUI(3),
                scale.scaleForGUI(4), scale.scaleForGUI(3)));
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        LauncherTheme.repaintOwnerOnFocusChange(this, this);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setAlignmentX(LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName(text);
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, false), "pressed");
        getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true), "released");
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                scrollRectToVisible(new Rectangle(0, 0, getWidth(), getHeight()));
            }
        });
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        int previousWidth = getWidth();
        super.setBounds(x, y, width, height);
        if (scale != null && previousWidth != width) revalidate();
    }

    @Override
    public Dimension getPreferredSize() {
        if (scale == null) return super.getPreferredSize();
        Insets insets = getInsets();
        int naturalWidth = (getText().isEmpty() ? 0 : (int) Math.ceil(
                new TextLayout(getText(), getFont(), getFontMetrics(getFont()).getFontRenderContext()).getAdvance()))
                + insets.left + insets.right;
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            Insets parentInsets = getParent().getInsets();
            width = getParent().getWidth() - parentInsets.left - parentInsets.right;
        }
        int textWidth = (width > 0 ? Math.min(width, naturalWidth) : naturalWidth)
                - insets.left - insets.right;
        float height = insets.top + insets.bottom;
        for (TextLayout line : lines(textWidth)) {
            height += line.getAscent() + line.getDescent() + line.getLeading() + scale.scaleForGUI(2f);
        }
        return new Dimension(naturalWidth, (int) Math.ceil(height));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            canvas.setStroke(new BasicStroke(Math.max(1f, scale.scaleForGUI(1f))));
            Color text = LauncherTheme.highContrast() ? LauncherTheme.buttonText(false, isEnabled())
                    : !isEnabled() ? LauncherTheme.MUTED
                    : getModel().isRollover() ? LauncherTheme.BUTTON_ICON : LauncherTheme.ACCENT;
            canvas.setColor(text);
            Insets insets = getInsets();
            float y = insets.top;
            for (TextLayout line : lines(getWidth() - insets.left - insets.right)) {
                y += line.getAscent();
                line.draw(canvas, insets.left, y);
                float underline = y + scale.scaleForGUI(1f);
                canvas.draw(new Line2D.Float(insets.left, underline,
                        insets.left + line.getAdvance(), underline));
                y += line.getDescent() + line.getLeading() + scale.scaleForGUI(2f);
            }
            if (isFocusOwner()) {
                LauncherTheme.paintOutline(canvas, getWidth(), getHeight(), scale,
                        LauncherTheme.highContrast()
                                ? LauncherTheme.uiColor("Focus.color", Color.WHITE) : LauncherTheme.BUTTON_ICON);
            }
        } finally {
            canvas.dispose();
        }
    }

    private List<TextLayout> lines(int width) {
        if (getText().isEmpty()) return List.of();
        AttributedString text = new AttributedString(getText());
        text.addAttribute(TextAttribute.FONT, getFont());
        var characters = text.getIterator();
        LineBreakMeasurer measurer = new LineBreakMeasurer(characters,
                getFontMetrics(getFont()).getFontRenderContext());
        List<TextLayout> lines = new ArrayList<>();
        while (measurer.getPosition() < characters.getEndIndex()) {
            lines.add(measurer.nextLayout(Math.max(1, width)));
        }
        return lines;
    }

    @Override
    public javax.swing.JToolTip createToolTip() {
        return LauncherTheme.toolTip(this, scale);
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleLink();
        return accessibleContext;
    }

    protected final class AccessibleLink extends AccessibleJButton {
        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.HYPERLINK;
        }
    }
}
