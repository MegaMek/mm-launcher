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

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.megamek.launcher.release.ReleaseNotes;

/**
 * Shows what changed in MegaMek, MegaMekLab and MekHQ after an update, or in the installed
 * version from Home: the published release notes of every version the player skipped, grouped
 * by application and by kind of change.
 *
 * <p>Non-modal, and the notes load in the background, so a slow or refused GitHub request never
 * holds up the launcher.</p>
 */
final class WhatsNewDialog extends JDialog {
    static final String TITLE = "What's new";
    static final String LOADING = "Loading the release notes…";

    /** Loads the notes off the event thread. */
    @FunctionalInterface
    interface Loader {
        ReleaseNotes.WhatsNew load() throws InterruptedException;
    }

    /** The parts of the content the dialog updates after it is shown. */
    record Content(JPanel panel, JLabel heading, JLabel subtitle, JEditorPane notes, JButton close) {}

    private final transient Content content;
    private final transient GuiScale scale;
    private transient SwingWorker<ReleaseNotes.WhatsNew, Void> worker;

    private WhatsNewDialog(Window owner, GuiScale scale, String since, String version, Consumer<URI> open) {
        super(owner, TITLE, ModalityType.MODELESS);
        this.scale = scale;
        setName("whatsNewDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getAccessibleContext().setAccessibleName(TITLE);
        LauncherTheme.useLauncherTitle(this);
        DialogFooter footer = new DialogFooter(scale);
        content = content(scale, since, version, open, this::dispose, footer);
        setContentPane(content.panel());
        footer.install(this, null);
        pack();
        fitToScreen();
        setLocationRelativeTo(owner);
    }

    private void fitToScreen() {
        Rectangle available = GuiScale.usableBounds(getGraphicsConfiguration());
        Dimension fitted = GuiScale.fitWindow(getSize(), available);
        setSize(fitted);
        setMinimumSize(new Dimension(Math.min(scale.size("dialog.sm"), fitted.width),
                Math.min(scale.scaleForGUI(320), fitted.height)));
    }

    /**
     * Opens the dialog for the change from {@code since} (null when unknown) to {@code version} and
     * starts loading the notes. {@code open} browses to a link the player chose.
     */
    static WhatsNewDialog show(Window owner, GuiScale scale, String since, String version,
                               Loader loader, Consumer<URI> open) {
        Objects.requireNonNull(loader, "loader");
        WhatsNewDialog dialog = new WhatsNewDialog(owner, scale, since, version, open);
        dialog.worker = new SwingWorker<>() {
            @Override
            protected ReleaseNotes.WhatsNew doInBackground() throws InterruptedException {
                return loader.load();
            }

            @Override
            protected void done() {
                if (isCancelled() || !dialog.isDisplayable()) return;
                try {
                    dialog.showNotes(get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    dialog.showNotesText(html(dialog.scale, null));
                }
            }
        };
        dialog.worker.execute();
        dialog.setVisible(true);
        return dialog;
    }

    @Override
    public void dispose() {
        if (worker != null) worker.cancel(true);
        super.dispose();
    }

    void showNotes(ReleaseNotes.WhatsNew whatsNew) {
        showNotesText(html(scale, whatsNew));
    }

    private void showNotesText(String html) {
        content.notes().setText(html);
        content.notes().setCaretPosition(0);
    }

    static Content content(GuiScale scale, String since, String version, Consumer<URI> open,
                           Runnable close, DialogFooter footer) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);

        JLabel heading = new JLabel(heading(version));
        heading.setName("whatsNewHeading");
        heading.setForeground(FirstLaunchPanel.ACCENT);
        heading.setFont(scale.font(base, "headline"));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        String sinceText = ReleaseNotes.combines(since, version)
                ? "Everything that changed since " + plain(since) + "." : "";
        JLabel subtitle = new JLabel(sinceText);
        subtitle.setName("whatsNewSince");
        subtitle.setForeground(FirstLaunchPanel.MUTED);
        subtitle.setFont(scale.font(base, "body"));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setVisible(!sinceText.isEmpty());

        JEditorPane notes = new JEditorPane("text/html", "");
        notes.setName("whatsNewNotes");
        notes.setEditable(false);
        notes.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        notes.setFont(scale.font(base, "body"));
        notes.setForeground(FirstLaunchPanel.TEXT);
        notes.setBackground(FirstLaunchPanel.PANEL);
        notes.setBorder(scale.padding("lg", "xl"));
        notes.getAccessibleContext().setAccessibleName("Release notes");
        notes.setText(page(scale, "<p class='muted'>" + escape(LOADING) + "</p>"));
        notes.addHyperlinkListener(event -> {
            if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            URI link = safeLink(event.getDescription());
            if (link != null) open.accept(link);
        });

        JScrollPane scroll = new JScrollPane(notes);
        scroll.setName("whatsNewScroll");
        LauncherTheme.styleContent(scroll, scale);
        scroll.setPreferredSize(new Dimension(scale.size("dialog.lg") - scale.space("xxl") * 2,
                scale.scaleForGUI(420)));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(heading);
        if (subtitle.isVisible()) {
            top.add(Box.createVerticalStrut(scale.space("xs")));
            top.add(subtitle);
        }

        JButton closeButton = new FirstLaunchButton("Close", "closeWhatsNewButton", true, scale,
                FirstLaunchButton.Size.SMALL);
        closeButton.addActionListener(event -> close.run());
        footer.primary(closeButton);
        JPanel footerPanel = footer.panel();
        footerPanel.setName("whatsNewActions");

        JPanel panel = new JPanel(new BorderLayout(0, scale.space("lg")));
        panel.setName("whatsNewContent");
        panel.setBackground(FirstLaunchPanel.BACKGROUND);
        panel.setBorder(scale.padding("xxl", "xxl"));
        panel.add(top, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(footerPanel, BorderLayout.SOUTH);
        return new Content(panel, heading, subtitle, notes, closeButton);
    }

    static String heading(String version) {
        return version == null || version.isBlank() ? TITLE : "What's new in " + plain(version);
    }

    /**
     * The notes as HTML for the notes pane, one part per application; {@code whatsNew} is null
     * when they could not be loaded at all.
     */
    static String html(GuiScale scale, ReleaseNotes.WhatsNew whatsNew) {
        StringBuilder html = new StringBuilder();
        if (whatsNew == null) {
            html.append("<p class='muted'>The release notes could not be loaded right now. ")
                    .append("<a href='https://github.com/MegaMek/megamek/releases'>")
                    .append("Read them on GitHub</a>.</p>");
            return page(scale, html.toString());
        }
        for (ReleaseNotes.Application application : whatsNew.applications()) {
            html.append("<h2>").append(escape(application.repository().productName()))
                    .append(" <a class='quiet' href='").append(escape(application.page().toString()))
                    .append("'>View on GitHub</a></h2>");
            if (application.problem() != null) {
                html.append("<p class='muted'>").append(escape(application.problem())).append("</p>");
            } else if (application.sections().isEmpty()) {
                html.append("<p class='muted'>No changes are listed for this version.</p>");
            }
            for (ReleaseNotes.Section section : application.sections()) {
                html.append("<h3>").append(escape(section.heading())).append("</h3><ul>");
                for (ReleaseNotes.Change change : section.changes()) {
                    html.append("<li>").append(escape(change.title()));
                    if (change.link() != null) {
                        html.append(" <a class='quiet' href='").append(escape(change.link().toString()))
                                .append("'>#").append(change.pullRequest().getAsInt()).append("</a>");
                    }
                    html.append("</li>");
                }
                html.append("</ul>");
            }
        }
        return page(scale, html.toString());
    }

    private static String page(GuiScale scale, String body) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.DIALOG, Font.PLAIN, 12);
        int bodySize = scale.font(base, "body").getSize();
        int titleSize = scale.font(base, "title").getSize();
        return "<html><head><style>"
                + "body { color: " + css(FirstLaunchPanel.TEXT) + "; font-size: " + bodySize + "px; }"
                + "h2 { color: " + css(FirstLaunchPanel.ACCENT) + "; font-size: " + titleSize + "px; "
                + "margin-top: " + scale.space("lg") + "px; margin-bottom: " + scale.space("xs") + "px; }"
                + "h3 { font-size: " + bodySize + "px; margin-top: " + scale.space("md") + "px; "
                + "margin-bottom: 0; }"
                + "ul { margin-top: " + scale.space("xs") + "px; margin-left: " + scale.space("xl") + "px; }"
                + "li { margin-bottom: " + scale.space("xs") + "px; }"
                + "a { color: " + css(FirstLaunchPanel.ACCENT) + "; }"
                + ".quiet { font-size: " + scale.font(base, "small").getSize() + "px; }"
                + ".muted { color: " + css(FirstLaunchPanel.MUTED) + "; }"
                + "</style></head><body>" + body + "</body></html>";
    }

    /** Only https links to GitHub open; anything else in a release note is ignored. */
    static URI safeLink(String address) {
        if (address == null) return null;
        try {
            URI link = new URI(address);
            return "https".equalsIgnoreCase(link.getScheme()) && link.getHost() != null
                    && "github.com".equals(link.getHost().toLowerCase(Locale.ROOT))
                    && link.getUserInfo() == null ? link : null;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    private static String plain(String version) {
        String trimmed = version.trim();
        return trimmed.startsWith("v") ? trimmed.substring(1) : trimmed;
    }

    private static String css(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&#39;").replace("\"", "&quot;");
    }
}