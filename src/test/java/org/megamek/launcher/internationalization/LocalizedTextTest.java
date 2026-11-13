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

package org.megamek.launcher.internationalization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;

/** Logs and diagnostics stay English while the player sees their own language. */
class LocalizedTextTest {
    private static final String BUNDLE = "org.megamek.launcher.internationalization.localizedtext";

    @BeforeEach
    void useGerman() {
        I18n.setLanguage("de");
    }

    @AfterEach
    void restoreLanguage() {
        I18n.setLanguage("en");
    }

    @Test
    void resolvesInThePlayersLanguageAndInEnglish() {
        LocalizedText greeting = LocalizedText.of(BUNDLE, "Test.greeting");
        assertEquals("Hallo", greeting.text());
        assertEquals("Hello", greeting.english());
        assertEquals("Hello", greeting.toString());
    }

    @Test
    void nestedTextFollowsTheSameLanguage() {
        LocalizedText count = LocalizedText.of(BUNDLE, "Test.count", "3",
                LocalizedText.of(BUNDLE, "Test.product"));
        assertEquals("3 Dateien in MegaMek-Kopie", count.text());
        assertEquals("3 files in MegaMek copy", count.english());
    }

    @Test
    void concatenatesPartsAndSkipsMissingOnes() {
        LocalizedText joined = LocalizedText.concat(LocalizedText.of(BUNDLE, "Test.greeting"), null,
                LocalizedText.literal(" 0.50.12"));
        assertEquals("Hallo 0.50.12", joined.text());
        assertEquals("Hello 0.50.12", joined.english());
    }

    @Test
    void progressDetailIsEnglishForLogsAndLocalizedForTheDialog() throws Exception {
        List<OperationProgress> events = new ArrayList<>();
        OperationContext context = new OperationContext(OperationType.REPAIR, events::add);
        context.phase(OperationPhase.METADATA, LocalizedText.of(BUNDLE, "Test.greeting"));
        context.progress(OperationPhase.EXTRACT, 1, 2, ProgressUnit.FILES,
                LocalizedText.of(BUNDLE, "Test.count", "1", "2"));
        context.phase(OperationPhase.APPLY, "untranslated");
        assertEquals(List.of("Hello", "1 files in 2", "untranslated"),
                events.stream().map(OperationProgress::detail).toList());
        assertEquals(List.of("Hallo", "1 Dateien in 2", "untranslated"),
                events.stream().map(OperationProgress::displayDetail).toList());
    }

    @Test
    void localizedErrorsAreEnglishInPersistentDiagnostics() {
        IOException error = new LocalizedIOException(LocalizedText.of(BUNDLE, "Test.greeting"));
        assertEquals("Hello", error.getMessage());
        assertEquals("Hallo", error.getLocalizedMessage());
        assertEquals(true, SanitizedErrors.display(error).contains("Hallo"));
        assertEquals(false, SanitizedErrors.persistent(error).contains("Hallo"));
        assertEquals(true, SanitizedErrors.persistent(error).contains("Hello"));
    }
}
