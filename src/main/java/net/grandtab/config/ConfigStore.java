package net.grandtab.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.grandtab.GrandTab;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes config/grandtab/config.json (layout, kingdoms, roles) and
 * config/grandtab/assignments.json (who holds which rank). Both are plain, pretty-printed JSON.
 * Writes go to a temporary file first and then replace the old file, so a crash never leaves a
 * half-written file. A file that cannot be parsed is never overwritten: the error is logged and
 * the last good values (or the defaults) stay in use.
 */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type ASSIGNMENTS = new TypeToken<LinkedHashMap<String, Assignment>>() { }.getType();

    private final Path dir;
    private TabConfig config = new TabConfig();
    private final Map<UUID, Assignment> assignments = new LinkedHashMap<>();
    private String lastError;

    public static final class Assignment {
        /** Last known name, for readability of the file and for /tabrank list. */
        public String name;
        public String kingdom;
        public String role;

        public Assignment() {
        }

        public Assignment(String name, String kingdom, String role) {
            this.name = name;
            this.kingdom = kingdom;
            this.role = role;
        }
    }

    public ConfigStore(Path dir) {
        this.dir = dir;
    }

    public TabConfig config() {
        return config;
    }

    public Map<UUID, Assignment> assignments() {
        return assignments;
    }

    /** Null if the last load succeeded, otherwise what went wrong. */
    public String lastError() {
        return lastError;
    }

    public Path configFile() {
        return dir.resolve("config.json");
    }

    public Path assignmentsFile() {
        return dir.resolve("assignments.json");
    }

    /** (Re)loads both files; creates them with defaults if missing. Returns false on errors. */
    public boolean load() {
        lastError = null;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            lastError = "cannot create " + dir + ": " + e.getMessage();
            GrandTab.LOG.error("Grand Tab: {}", lastError);
            return false;
        }
        boolean ok = true;
        Path file = configFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                TabConfig loaded = GSON.fromJson(reader, TabConfig.class);
                if (loaded == null) {
                    throw new JsonParseException("file is empty");
                }
                loaded.repair();
                config = loaded;
            } catch (IOException | JsonParseException e) {
                ok = false;
                lastError = "config.json is not valid JSON (" + e.getMessage() + "); keeping the previous settings";
                GrandTab.LOG.error("Grand Tab: {}", lastError);
            }
        } else {
            config = new TabConfig();
            config.repair();
            ok &= saveConfig();
        }
        Path afile = assignmentsFile();
        if (Files.exists(afile)) {
            try (Reader reader = Files.newBufferedReader(afile, StandardCharsets.UTF_8)) {
                Map<String, Assignment> raw = GSON.fromJson(reader, ASSIGNMENTS);
                assignments.clear();
                if (raw != null) {
                    for (Map.Entry<String, Assignment> e : raw.entrySet()) {
                        try {
                            if (e.getValue() != null && e.getValue().kingdom != null && e.getValue().role != null) {
                                assignments.put(UUID.fromString(e.getKey()), e.getValue());
                            }
                        } catch (IllegalArgumentException badUuid) {
                            GrandTab.LOG.warn("Grand Tab: ignoring assignment with invalid UUID '{}'", e.getKey());
                        }
                    }
                }
            } catch (IOException | JsonParseException e) {
                ok = false;
                lastError = "assignments.json is not valid JSON (" + e.getMessage() + "); keeping the previous assignments";
                GrandTab.LOG.error("Grand Tab: {}", lastError);
            }
        } else {
            ok &= saveAssignments();
        }
        return ok;
    }

    public boolean saveConfig() {
        return write(configFile(), config);
    }

    public boolean saveAssignments() {
        Map<String, Assignment> raw = new LinkedHashMap<>();
        for (Map.Entry<UUID, Assignment> e : assignments.entrySet()) {
            raw.put(e.getKey().toString(), e.getValue());
        }
        return write(assignmentsFile(), raw);
    }

    private boolean write(Path target, Object value) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e2) {
                GrandTab.LOG.error("Grand Tab: cannot write {}", target, e2);
                return false;
            }
        }
    }
}
