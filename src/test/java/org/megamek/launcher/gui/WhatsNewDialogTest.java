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
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseNotes;

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
    void groupsChangesByApplicationAndEscapesTheirTitles() {
        ReleaseNotes.WhatsNew whatsNew = new ReleaseNotes.WhatsNew("0.51.01.5", "0.51.01.2", List.of(
                new ReleaseNotes.Application(OfficialRepository.MEGAMEK, List.of("v0.51.01.5"),
                        List.of(new ReleaseNotes.Section("Bug fixes", List.of(
                                new ReleaseNotes.Change("Fix <script> & 'quotes'", OptionalInt.of(42), PR)))),
                        URI.create("https://github.com/MegaMek/megamek/releases"), null),
                new ReleaseNotes.Application(OfficialRepository.LAB, List.of(), List.of(),
                        URI.create("https://github.com/MegaMek/megameklab/releases"), "Try again later."),
                new ReleaseNotes.Application(OfficialRepository.MEKHQ, List.of("v0.51.01.5"), List.of(),
                        URI.create("https://github.com/MegaMek/mekhq/releases"), null)));

        String html = WhatsNewDialog.html(GuiScale.DEFAULT, whatsNew);

        assertTrue(html.indexOf("MegaMek ") < html.indexOf("MegaMekLab") && html.indexOf("MegaMekLab")
                < html.indexOf("MekHQ"));
        assertTrue(html.contains("Fix &lt;script&gt; &amp; &#39;quotes&#39;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("href='" + PR + "'>#42</a>"));
        assertTrue(html.contains("Try again later."));
        assertTrue(html.contains("No changes are listed for this version."));
        assertTrue(WhatsNewDialog.html(GuiScale.DEFAULT, null).contains("could not be loaded"));
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