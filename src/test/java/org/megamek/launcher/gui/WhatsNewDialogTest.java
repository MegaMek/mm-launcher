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

import org.junit.jupiter.api.Test;
import org.megamek.launcher.release.ReleaseNotes;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsNewDialogTest {
    private static final URI PR = URI.create("https://github.com/MegaMek/megamek/pull/42");

    @Test
    void describesOneApplicationPerTabAndEscapesItsTitles() {
        ReleaseNotes.Application megamek = new ReleaseNotes.Application("MegaMek", "0.51.01.5", null,
                List.of("v0.51.01.5"),
                List.of(new ReleaseNotes.Release("v0.51.01.5",
                        URI.create("https://github.com/MegaMek/megamek/releases/tag/v0.51.01.5"),
                        List.of(new ReleaseNotes.Section("Bug fixes", List.of(
                                new ReleaseNotes.Change("Fix <script> & 'quotes'", OptionalInt.of(42), PR)))))),
                URI.create("https://github.com/MegaMek/megamek/releases"), null, true);
        ReleaseNotes.Application lab = new ReleaseNotes.Application("MegaMekLab", "0.51.01.5", null, List.of(),
                List.of(), URI.create("https://github.com/MegaMek/megameklab/releases"), "Try again later.", false);
        ReleaseNotes.Application mekhq = new ReleaseNotes.Application("MekHQ", "0.51.01.5", null,
                List.of("v0.51.01.5"), List.of(), URI.create("https://github.com/MegaMek/mekhq/releases"), null,
                false);
        ReleaseNotes.WhatsNew whatsNew = new ReleaseNotes.WhatsNew("0.51.01.5", null, List.of(megamek, lab, mekhq));

        String html = WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew, megamek);
        assertTrue(html.contains("Fix &lt;script&gt; &amp; &#39;quotes&#39;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("href='" + PR + "'>#42</a>"));
        assertEquals(1, html.split("Some older changes are not shown here", -1).length - 1);
        assertFalse(html.contains("Version 0.51.01.5"), "the shared version is in the dialog heading");
        assertTrue(html.contains("<h3>Bug fixes</h3>"), "one release needs no version heading");
        assertFalse(html.contains("MegaMekLab"), "each tab holds only its own application");

        assertTrue(WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew, lab).contains("Try again later."));
        assertTrue(WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew, mekhq)
                .contains("No changes are listed for this version."));
        assertTrue(WhatsNewDialog.html(GuiScale.DEFAULT, null, null).contains("could not be loaded"));
        assertTrue(WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew, null).contains("no release notes"));
    }

    @Test
    void aTabNamesItsOwnVersionAndStartWhenTheyDifferFromTheHeading() {
        ReleaseNotes.Application launcher = new ReleaseNotes.Application(ReleaseNotes.LAUNCHER_PRODUCT, "0.14.3",
                "0.14.1", List.of("v0.14.3", "v0.14.2"), List.of(release(3)),
                URI.create("https://github.com/MegaMek/mm-launcher/releases"), null, false);
        ReleaseNotes.WhatsNew whatsNew = new ReleaseNotes.WhatsNew("0.51.01.5", null, List.of(launcher));

        String html = WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew, launcher);

        assertTrue(html.contains("Version 0.14.3, everything that changed since 0.14.1."));
        assertTrue(html.contains("<h3>0.51.01.3 <a class='quiet'"));
    }

    @Test
    void severalApplicationsGetOneTabEach() throws Exception {
        List<String> titles = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            JTabbedPane tabs = WhatsNewDialog.tabs(GuiScale.DEFAULT, new ReleaseNotes.WhatsNew("0.51.01.5", null,
                    List.of(application("MegaMek"), application("MegaMekLab"), application("MekHQ"),
                            application(ReleaseNotes.LAUNCHER_PRODUCT))), uri -> {});
            for (int index = 0; index < tabs.getTabCount(); index++) titles.add(tabs.getTitleAt(index));
        });
        assertEquals(List.of("MegaMek", "MegaMekLab", "MekHQ", "Launcher"), titles);
    }

    private static ReleaseNotes.Application application(String product) {
        return new ReleaseNotes.Application(product, "0.51.01.5", null, List.of("v0.51.01.5"), List.of(),
                URI.create("https://github.com/MegaMek/megamek/releases"), null, false);
    }

    @Test
    void severalReleasesEachGetAVersionHeadingNewestFirst() {
        ReleaseNotes.Application megamek = new ReleaseNotes.Application("MegaMek", "0.51.01.5", "0.51.01.3",
                List.of("v0.51.01.5", "v0.51.01.4"), List.of(release(5), release(4)),
                URI.create("https://github.com/MegaMek/megamek/releases"), null, false);
        ReleaseNotes.WhatsNew whatsNew = new ReleaseNotes.WhatsNew("0.51.01.5", "0.51.01.3", List.of(megamek));

        String html = WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew, megamek);

        String five = "<h3>0.51.01.5 <a class='quiet' href='https://github.com/MegaMek/megamek/releases/tag/"
                + "v0.51.01.5'>View on GitHub</a></h3>";
        String four = "<h3>0.51.01.4 <a class='quiet' href='https://github.com/MegaMek/megamek/releases/tag/"
                + "v0.51.01.4'>View on GitHub</a></h3>";
        assertTrue(html.contains(five));
        assertTrue(html.contains(four));
        assertTrue(html.contains("<h4>Bug fixes</h4>"));
        assertFalse(html.contains("everything that changed since"), "the dialog heading says where it starts");
        assertTrue(html.indexOf(five) < html.indexOf("#5<") && html.indexOf("#5<") < html.indexOf(four)
                && html.indexOf(four) < html.indexOf("#4<"));
    }

    @Test
    void aRangeWithOnlyOneChangedReleaseStillNamesIt() {
        ReleaseNotes.Application lab = new ReleaseNotes.Application("MegaMekLab", "0.51.01.6", "0.51.01.3",
                List.of("v0.51.01.6", "v0.51.01.5", "v0.51.01.4"), List.of(release(4)),
                URI.create("https://github.com/MegaMek/megameklab/releases"), null, false);
        ReleaseNotes.WhatsNew whatsNew = new ReleaseNotes.WhatsNew("0.51.01.6", "0.51.01.3", List.of(lab));

        String html = WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew, lab);

        assertTrue(html.contains("<h3>0.51.01.4 <a class='quiet'"));
        assertTrue(html.contains("<h4>Bug fixes</h4>"));
    }

    private static ReleaseNotes.Release release(int revision) {
        return new ReleaseNotes.Release("v0.51.01." + revision,
                URI.create("https://github.com/MegaMek/megamek/releases/tag/v0.51.01." + revision),
                List.of(new ReleaseNotes.Section("Bug fixes", List.of(new ReleaseNotes.Change("Fix",
                        OptionalInt.of(revision), URI.create("https://github.com/MegaMek/megamek/pull/" + revision))))));
    }

    @Test
    void onlyHttpsGitHubLinksOpen() {
        assertEquals(PR, WhatsNewDialog.safeLink(PR.toString()));
        assertNull(WhatsNewDialog.safeLink("http://github.com/MegaMek/megamek"));
        assertNull(WhatsNewDialog.safeLink("https://github.com.evil.example/x"));
        assertNull(WhatsNewDialog.safeLink("https://user@github.com/x"));
        assertNull(WhatsNewDialog.safeLink("file:///etc/passwd"));
        assertNull(WhatsNewDialog.safeLink("not a uri"));
        assertNull(WhatsNewDialog.safeLink(null));
    }

    @Test
    void headingNamesTheVersionAndTheOlderOneWhenCombining() throws Exception {
        List<URI> opened = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            WhatsNewDialog.Content combined = WhatsNewDialog.content(GuiScale.DEFAULT, "0.51.01.2",
                    "v0.51.01.5", opened::add, () -> closed.add("close"), new DialogFooter(GuiScale.DEFAULT));
            assertEquals("What's new in 0.51.01.5", combined.heading().getText());
            assertEquals("Everything that changed since 0.51.01.2.", combined.subtitle().getText());
            assertTrue(combined.subtitle().isVisible());
            assertFalse(combined.notes().isEditable());

            combined.notes().fireHyperlinkUpdate(new HyperlinkEvent(combined.notes(),
                    HyperlinkEvent.EventType.ACTIVATED, null, PR.toString()));
            combined.notes().fireHyperlinkUpdate(new HyperlinkEvent(combined.notes(),
                    HyperlinkEvent.EventType.ACTIVATED, null, "https://evil.example/"));
            combined.close().doClick();

            WhatsNewDialog.Content single = WhatsNewDialog.content(GuiScale.DEFAULT, null, "0.51.01.5",
                    opened::add, () -> {}, new DialogFooter(GuiScale.DEFAULT));
            assertFalse(single.subtitle().isVisible());
        });
        assertEquals(List.of(PR), opened);
        assertEquals(List.of("close"), closed);
    }
}