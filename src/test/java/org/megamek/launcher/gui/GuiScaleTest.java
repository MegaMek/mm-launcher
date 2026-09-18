package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiScaleTest {
    @Test
    void applicationScaleIsSeparateFromDeviceDpi() {
        GuiScale scale = new GuiScale(1.5f);
        assertEquals(18, scale.scaleForGUI(12));
        assertEquals(new Dimension(300, 150), scale.scaleForGUI(200, 100));
        assertEquals(18f, scale.font(new Font(Font.DIALOG, Font.PLAIN, 12),
                Font.BOLD, 12).getSize2D());
        assertEquals(12, GuiScale.DEFAULT.scaleForGUI(12));
        assertThrows(IllegalArgumentException.class, () -> new GuiScale(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new GuiScale(0));
    }

    @Test
    void workAreaIsAlreadyInLogicalCoordinates() {
        Rectangle workArea = new Rectangle(1920, 0, 960, 520);
        Dimension fitted = GuiScale.fitWindow(new Dimension(1312, 560), workArea);
        assertEquals(new Dimension(902, 488), fitted);
        assertTrue(fitted.width <= workArea.width);
        assertTrue(fitted.height <= workArea.height);
    }

    @Test
    void fitsWholeArtworkWithoutCroppingOrStretching() {
        for (Dimension viewport : new Dimension[]{new Dimension(960, 560),
                new Dimension(800, 200), new Dimension(200, 800)}) {
            Rectangle image = GuiScale.fitImage(1280, 719, viewport.width, viewport.height);
            assertTrue(new Rectangle(viewport).contains(image));
            assertTrue(Math.abs((double) image.width / 1280 - (double) image.height / 719)
                    <= 1.0 / 719);
            assertEquals((viewport.width - image.width) / 2, image.x);
            assertEquals((viewport.height - image.height) / 2, image.y);
        }
        assertEquals(new Rectangle(), GuiScale.fitImage(1280, 719, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GuiScale.fitImage(0, 719, 800, 400));
    }
}
