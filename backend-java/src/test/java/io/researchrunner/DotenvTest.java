package io.researchrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class DotenvTest {
    @Test
    void loadsKeysWithoutOverridingExistingProperties() throws Exception {
        var file = Files.createTempFile("researchrunner-dotenv", ".env");
        try {
            Files.writeString(file, """
                    # comment
                    DOTENV_TEST_KEY=value
                    export DOTENV_TEST_SECOND="quoted value"
                    DOTENV_TEST_EXISTING=from-file
                    malformed line
                    """, StandardCharsets.UTF_8);
            System.setProperty("DOTENV_TEST_EXISTING", "from-system");

            assertEquals(2, Dotenv.load(file));
            assertEquals("value", System.getProperty("DOTENV_TEST_KEY"));
            assertEquals("quoted value", System.getProperty("DOTENV_TEST_SECOND"));
            assertEquals("from-system", System.getProperty("DOTENV_TEST_EXISTING"));
        } finally {
            System.clearProperty("DOTENV_TEST_KEY");
            System.clearProperty("DOTENV_TEST_SECOND");
            System.clearProperty("DOTENV_TEST_EXISTING");
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFileIsNotAnError() {
        assertEquals(0, Dotenv.loadDefault());
        assertNull(System.getProperty("DOTENV_TEST_KEY"));
    }
}
