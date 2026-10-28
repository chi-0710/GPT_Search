package io.researchrunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A2-08: the Python CLI loads the repository {@code .env}; the Java CLI must do the same.
 *
 * <p>Values become JVM system properties (lower precedence than real environment variables),
 * which Spring's property sources resolve for {@code @Value} and {@code Environment}. Existing
 * environment values are never overridden, so container settings stay authoritative.
 */
public final class Dotenv {
    private Dotenv() { }

    public static int load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int loaded = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring("export ".length()).strip();
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (key.isEmpty() || System.getenv(key) != null || System.getProperty(key) != null) continue;
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            System.setProperty(key, value);
            loaded++;
        }
        return loaded;
    }

    /** Loads {@code .env} from the current directory, matching the documented CLI invocation. */
    public static int loadDefault() {
        try {
            return load(Path.of(".env"));
        } catch (IOException ignored) {
            return 0;
        }
    }
}
