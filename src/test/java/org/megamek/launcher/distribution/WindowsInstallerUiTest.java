package org.megamek.launcher.distribution;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WindowsInstallerUiTest {
    private static final Path RESOURCES = Path.of("src/distribution/windows/msi");
    private static final String WIX = "http://schemas.microsoft.com/wix/2006/wi";

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
        assertEquals("success", find(doc, "Show", "LauncherFinished").getAttribute("OnExit"));
        assertEquals("error", find(doc, "Show", "LauncherFailed").getAttribute("OnExit"));

        Element detail = find(doc, "Control", "Detail");
        assertTrue(detail.getAttribute("Text").contains("[INSTALLDIR]"));
        assertFalse(detail.getAttribute("Text").contains("folder is fixed"));
        Element launch = find(doc, "Publish", "LaunchMegaMekLauncher");
        assertEquals("DoAction", launch.getAttribute("Event"));
        assertEquals("LAUNCH_AFTER_INSTALL = 1 AND UILevel = 5 AND NOT REMOVE",
                launch.getTextContent().trim());
        assertEquals("Finish", ((Element) launch.getParentNode()).getAttribute("Id"));
        Element desktop = find(doc, "Control", "DesktopShortcut");
        assertEquals("LauncherWelcome", ((Element) desktop.getParentNode()).getAttribute("Id"));
        assertEquals("JP_INSTALL_DESKTOP_SHORTCUT", desktop.getAttribute("Property"));
        assertEquals("1", desktop.getAttribute("CheckBoxValue"));
        assertEquals("Create a desktop shortcut", desktop.getAttribute("Text"));
        Element finish = find(doc, "Dialog", "LauncherFinished");
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
        for (String dialogId : new String[] {"LauncherWelcome", "LauncherFinished"}) {
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
