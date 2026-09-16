package org.megamek.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @Test
    void invalidInvocationIsExplicitAndNonzero() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = Main.run(new String[]{"--baseline", "only-one.json"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors));

        assertEquals(2, result);
        String message = errors.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("ERROR:"));
        assertTrue(message.contains("Usage:"));
    }
}
