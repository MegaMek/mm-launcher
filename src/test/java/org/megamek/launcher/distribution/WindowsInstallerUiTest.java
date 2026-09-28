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

package org.megamek.launcher.distribution;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WindowsInstallerUiTest {
    private static final Path RESOURCES = Path.of("src/distribution/windows/msi");
    private static final String WIX = "http://schemas.microsoft.com/wix/2006/wi";

    @Test
    void progressLabelDoesNotRenderUnresolvedInstallerActionTokens() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(RESOURCES.resolve("ui.wxf").toFile());

        Element progress = find(doc, "Dialog", "LauncherProgress");
        Element status = find(doc, "Control", "StatusText");
        assertSame(progress, status.getParentNode());
        assertEquals("Text", status.getAttribute("Type"));
        assertEquals("Setting up MegaMek Launcher...", status.getAttribute("Text"),
                "Use literal text, not an MSI property or an unverified action template");
        assertEquals(0, status.getElementsByTagNameNS(WIX, "Subscribe").getLength(),
                "ActionText fallback may show raw bracketed placeholders on localized Windows");

        Element bar = find(doc, "Control", "ProgressBar");
        assertSame(progress, bar.getParentNode());
        assertEquals("ProgressBar", bar.getAttribute("Type"));
        var subscriptions = bar.getElementsByTagNameNS(WIX, "Subscribe");
        assertEquals(1, subscriptions.getLength());
        Element subscription = (Element) subscriptions.item(0);
        assertEquals("SetProgress", subscription.getAttribute("Event"));
        assertEquals("Progress", subscription.getAttribute("Attribute"));
        var allSubscriptions = doc.getElementsByTagNameNS(WIX, "Subscribe");
        for (int i = 0; i < allSubscriptions.getLength(); i++) {
            assertNotEquals("ActionText", ((Element) allSubscriptions.item(i)).getAttribute("Event"),
                    "No control should render unverified default action messages");
        }
    }

    @Test
    void progressDoesNotBlockExecuteActionAndOnlyFinishCanLaunch() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(RESOURCES.resolve("ui.wxf").toFile());

        Element progress = find(doc, "Dialog", "LauncherProgress");
        assertEquals("yes", progress.getAttribute("Modeless"),
                "A modal Show before ExecuteAction blocks the installation indefinitely");
        Element show = find(doc, "Show", "LauncherProgress");
        assertEquals("ExecuteAction", show.getAttribute("Before"));
        assertEquals("NOT Installed", show.getTextContent().trim());
        assertEquals("success", find(doc, "Show", "Exit").getAttribute("OnExit"));
        assertEquals("error", find(doc, "Show", "FatalError").getAttribute("OnExit"));

        Element detail = find(doc, "Control", "Detail");
        assertTrue(detail.getAttribute("Text").contains("[INSTALLDIR]"));
        assertFalse(detail.getAttribute("Text").contains("folder is fixed"));
        Element launch = find(doc, "Publish", "LaunchMegaMekLauncher");
        assertEquals("DoAction", launch.getAttribute("Event"));
        String launchCondition = "LAUNCH_AFTER_INSTALL = 1 AND UILevel = 5 AND NOT REMOVE";
        assertEquals(launchCondition, launch.getTextContent().trim());
        assertEquals(launchCondition, find(doc, "Publish", "SetLauncherTarget").getTextContent().trim(),
                "Both finish actions must share the interactive, non-removal condition");
        assertEquals("Finish", ((Element) launch.getParentNode()).getAttribute("Id"));
        Element desktop = find(doc, "Control", "DesktopShortcut");
        assertEquals("LauncherWelcome", ((Element) desktop.getParentNode()).getAttribute("Id"));
        assertEquals("JP_INSTALL_DESKTOP_SHORTCUT", desktop.getAttribute("Property"));
        assertEquals("1", desktop.getAttribute("CheckBoxValue"));
        assertEquals("Create a desktop shortcut", desktop.getAttribute("Text"));
        Element finish = find(doc, "Dialog", "Exit");
        Element launchBox = find(doc, "Control", "Launch");
        assertSame(finish, launchBox.getParentNode());
        assertEquals("LAUNCH_AFTER_INSTALL", launchBox.getAttribute("Property"));
        assertEquals("1", launchBox.getAttribute("CheckBoxValue"));
        assertEquals("Launch MegaMek Launcher", launchBox.getAttribute("Text"));
        for (Element checkBox : new Element[] {desktop, launchBox}) {
            assertEquals("CheckBox", checkBox.getAttribute("Type"));
            assertEquals("190", checkBox.getAttribute("X"));
            assertEquals("165", checkBox.getAttribute("Width"),
                    "The label must be inside the clickable checkbox, not a separate text control");
            assertEquals("20", checkBox.getAttribute("Height"));
            Element owner = (Element) checkBox.getParentNode();
            var controls = owner.getElementsByTagNameNS(WIX, "Control");
            for (int i = 0; i < controls.getLength(); i++) {
                assertFalse(((Element) controls.item(i)).getAttribute("Id").endsWith("Label"),
                        "No detached checkbox label or isolated focus rectangle");
            }
        }
        Element preference = find(doc, "RegistryValue", "DesktopShortcutOptOut");
        assertEquals("1", preference.getAttribute("Value"));
        assertEquals("string", preference.getAttribute("Type"),
                "A raw registry search of a DWORD adds a # prefix, breaking the comparison");
        assertEquals("NOT JP_INSTALL_DESKTOP_SHORTCUT",
                find(doc, "Component", "DesktopOptOutPreference")
                        .getElementsByTagNameNS(WIX, "Condition").item(0).getTextContent().trim());
        assertEquals("{}", find(doc, "CustomAction", "RestoreDesktopOptOut").getAttribute("Value"));
        assertEquals("DesktopOptOutPreference",
                find(doc, "ComponentRef", "DesktopOptOutPreference").getAttribute("Id"));
        var restores = doc.getElementsByTagNameNS(WIX, "Custom");
        int count = 0;
        for (int i = 0; i < restores.getLength(); i++) {
            Element action = (Element) restores.item(i);
            if ("RestoreDesktopOptOut".equals(action.getAttribute("Action"))) {
                assertEquals("AppSearch", action.getAttribute("After"));
                assertTrue(action.getTextContent().contains("LAUNCHER_DESKTOP_OPTOUT = 1"));
                if ("InstallExecuteSequence".equals(((Element) action.getParentNode()).getTagName())) {
                    assertTrue(action.getTextContent().contains("UILevel < 5"),
                            "Execute sequence must not overwrite the interactive Welcome choice");
                }
                count++;
            }
        }
        assertEquals(2, count, "Interactive and silent installs must both restore the prior opt-out");
    }

    @Test
    void wizardArtIsOnlyOnTheLeftAndRightPanelUsesNativeDialogBackground() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(RESOURCES.resolve("ui.wxf").toFile());
        for (String dialogId : new String[] {"LauncherWelcome", "Exit"}) {
            Element dialogElement = find(doc, "Dialog", dialogId);
            var controls = dialogElement.getElementsByTagNameNS(WIX, "Control");
            int bitmapCount = 0;
            for (int i = 0; i < controls.getLength(); i++) {
                Element control = (Element) controls.item(i);
                if ("Bitmap".equals(control.getAttribute("Type"))) {
                    assertEquals("0", control.getAttribute("X"));
                    assertEquals("180", control.getAttribute("Width"));
                    assertEquals("234", control.getAttribute("Height"));
                    bitmapCount++;
                } else if ("Detail".equals(control.getAttribute("Id"))
                        || "Title".equals(control.getAttribute("Id"))
                        || "CheckBox".equals(control.getAttribute("Type"))) {
                    assertTrue(Integer.parseInt(control.getAttribute("X")) >= 180);
                    assertNotEquals("yes", control.getAttribute("Transparent"));
                }
            }
            assertEquals(1, bitmapCount, "No opaque image should cover the native right-hand panel");
        }

        var dialog = ImageIO.read(RESOURCES.resolve("wizard-dialog.bmp").toFile());
        var banner = ImageIO.read(RESOURCES.resolve("wizard-banner.bmp").toFile());
        assertEquals(240, dialog.getWidth());
        assertEquals(312, dialog.getHeight());
        assertEquals(493, banner.getWidth());
        assertEquals(58, banner.getHeight());
        assertNotEquals(0xffffff, dialog.getRGB(180, 150) & 0xffffff);
        int bannerCenter = banner.getRGB(250, 29);
        assertTrue(((bannerCenter >> 8) & 255) < 100, "Banner center should contain machinery, not sky");
    }

    @Test
    void requiredInstallerUiTablesAndPerUserFolderCleanupAreAuthored() throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(RESOURCES.resolve("ui.wxf").toFile());

            // These map to ICE20's Property, Dialog, InstallUISequence and
            // AdminUISequence checks; don't silence ICE20 in light.exe instead.
            assertEquals("LauncherError", find(doc, "Property", "ErrorDialog").getAttribute("Value"));
            Element error = find(doc, "Dialog", "LauncherError");
            assertEquals("yes", error.getAttribute("ErrorDialog"),
                    "Windows Installer must handle the buttons, message and standard error sound");
            var errorControls = error.getElementsByTagNameNS(WIX, "Control");
            assertEquals(8, errorControls.getLength());
            Element text = (Element) errorControls.item(0);
            assertEquals("ErrorText", text.getAttribute("Id"),
                    "ErrorText must be the first control in the Dialog table");
            assertEquals("Text", text.getAttribute("Type"));
            for (String[] response : new String[][] {
                    {"A", "ErrorAbort"}, {"C", "ErrorCancel"}, {"I", "ErrorIgnore"},
                    {"N", "ErrorNo"}, {"O", "ErrorOk"}, {"R", "ErrorRetry"}, {"Y", "ErrorYes"}
            }) {
                Element button = null;
                for (int i = 1; i < errorControls.getLength(); i++) {
                    Element candidate = (Element) errorControls.item(i);
                    if (response[0].equals(candidate.getAttribute("Id"))) {
                        button = candidate;
                        break;
                    }
                }
                assertNotNull(button, "Missing error response " + response[0]);
                assertEquals("PushButton", button.getAttribute("Type"));
                assertEquals("150", button.getAttribute("X"));
                assertEquals("243", button.getAttribute("Y"));
                assertEquals("70", button.getAttribute("Width"));
                assertEquals("18", button.getAttribute("Height"));
                var events = button.getElementsByTagNameNS(WIX, "Publish");
                assertEquals(1, events.getLength());
                Element event = (Element) events.item(0);
                assertEquals("EndDialog", event.getAttribute("Event"));
                assertEquals(response[1], event.getAttribute("Value"));
            }
            Element filesInUse = find(doc, "Dialog", "FilesInUse");
            assertEquals("yes", filesInUse.getAttribute("KeepModeless"),
                    "Native FilesInUse must not close the modeless progress dialog");
            for (String action : new String[] {"FatalError", "UserExit", "Exit"}) {
                find(doc, "Dialog", action);
                for (String sequence : new String[] {"InstallUISequence", "AdminUISequence"}) {
                    Element ui = find(doc, "UI", "JpUI");
                    var sequences = ui.getElementsByTagNameNS(WIX, sequence);
                    assertEquals(1, sequences.getLength());
                    var shows = ((Element) sequences.item(0)).getElementsByTagNameNS(WIX, "Show");
                    boolean found = false;
                    for (int i = 0; i < shows.getLength(); i++) {
                        found |= action.equals(((Element) shows.item(i)).getAttribute("Dialog"));
                    }
                    assertTrue(found, sequence + " must have a " + action + " action");
                }
            }

            // This RemoveFolder becomes a RemoveFile row keyed to the generated
            // Programs directory, not to the shared LocalAppDataFolder or Start Menu.
            String programsId = "dir3b50371ef2d03bb0b1971278ecbab3a3";
            assertTrue(Files.readString(Path.of("build.gradle.kts"))
                            .contains("\"--install-dir\", \"Programs/MegaMek Launcher\""),
                    "Inspect the generated MSI Directory table and update this ID if --install-dir changes");
            Element directory = find(doc, "DirectoryRef", programsId);
            Element cleanup = find(doc, "Component", "LauncherProgramsFolderCleanup");
            assertSame(directory, cleanup.getParentNode());
            Element remove = find(doc, "RemoveFolder", "RemoveEmptyLauncherProgramsFolder");
            assertSame(cleanup, remove.getParentNode());
            assertEquals("uninstall", remove.getAttribute("On"));
            assertEquals("", remove.getAttribute("Directory"));
            Element marker = find(doc, "RegistryValue", "ProgramsFolderOwned");
            assertSame(cleanup, marker.getParentNode());
            assertEquals("HKCU", marker.getAttribute("Root"));
            assertEquals("yes", marker.getAttribute("KeyPath"));
            find(doc, "ComponentRef", "LauncherProgramsFolderCleanup");
        }

    private static Element find(Document doc, String tag, String id) {
        var elements = doc.getElementsByTagNameNS(WIX, tag);
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (id.equals(element.getAttribute("Id"))
                    || ("RegistryValue".equals(tag) && id.equals(element.getAttribute("Name")))
                    || ("Show".equals(tag) && id.equals(element.getAttribute("Dialog")))
                    || ("Publish".equals(tag) && id.equals(element.getAttribute("Value")))) {
                return element;
            }
        }
        fail("Missing " + tag + " " + id);
        throw new AssertionError();
    }
}
