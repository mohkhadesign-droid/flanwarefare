package net.flanwarfare.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.flanwarfare.FlanWarfare;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All persistent add-on state, stored as one JSON file next to Flan's claim data.
 *
 * <p>Writes go to a temporary file which is then atomically moved over the real one, and the
 * previous version is kept as {@code .bak}. Writing is cheap (one small file) and happens on
 * the server thread at most every few seconds, or immediately after critical operations.
 */
public final class WarData {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_FINISHED_TXNS = 500;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Serialized form. */
    private static final class Snapshot {
        int formatVersion = FORMAT_VERSION;
        Map<UUID, ClaimRecord> claims = new HashMap<>();
        Map<UUID, String> designs = new HashMap<>();
        List<ConquestTxn> transactions = new ArrayList<>();
        List<OrphanFlag> orphanFlags = new ArrayList<>();
        Map<UUID, List<AlertEntry>> alertHistory = new HashMap<>();
        List<UUID> mutedAlerts = new ArrayList<>();
    }

    private static final int MAX_ALERTS_PER_PLAYER = 20;
    private static final long COALESCE_MILLIS = 10L * 60L * 1000L;

    private final Path file;
    private Snapshot state = new Snapshot();
    private boolean dirty;

    private WarData(Path file) {
        this.file = file;
    }

    /**
     * Loads the data file. A missing file means a fresh installation. A file that exists but
     * cannot be parsed throws, so the caller can refuse to apply rules on corrupted state.
     */
    public static WarData load(Path file) throws IOException {
        WarData data = new WarData(file);
        if (!Files.exists(file)) {
            FlanWarfare.LOG.info("No existing war data at {}; starting fresh", file);
            return data;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Snapshot loaded = GSON.fromJson(reader, Snapshot.class);
            if (loaded == null) {
                throw new IOException("war data file is empty: " + file);
            }
            if (loaded.formatVersion > FORMAT_VERSION) {
                throw new IOException("war data was written by a newer version (format " + loaded.formatVersion + ")");
            }
            if (loaded.claims == null) {
                loaded.claims = new HashMap<>();
            }
            if (loaded.designs == null) {
                loaded.designs = new HashMap<>();
            }
            if (loaded.transactions == null) {
                loaded.transactions = new ArrayList<>();
            }
            if (loaded.orphanFlags == null) {
                loaded.orphanFlags = new ArrayList<>();
            }
            if (loaded.alertHistory == null) {
                loaded.alertHistory = new HashMap<>();
            }
            if (loaded.mutedAlerts == null) {
                loaded.mutedAlerts = new ArrayList<>();
            }
            loaded.claims.values().removeIf(r -> r == null || r.claimId == null || r.dimension == null);
            data.state = loaded;
        } catch (JsonParseException e) {
            throw new IOException("war data file is corrupted: " + file, e);
        }
        return data;
    }

    public Path file() {
        return file;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Writes the file now (atomic replace). */
    public void save() throws IOException {
        trimTransactions();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(state, writer);
        }
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    private void trimTransactions() {
        int finished = 0;
        for (ConquestTxn txn : state.transactions) {
            if (txn.phase != ConquestTxn.Phase.PENDING && txn.phase != ConquestTxn.Phase.NEEDS_ADMIN) {
                finished++;
            }
        }
        Iterator<ConquestTxn> it = state.transactions.iterator();
        while (finished > MAX_FINISHED_TXNS && it.hasNext()) {
            ConquestTxn txn = it.next();
            if (txn.phase != ConquestTxn.Phase.PENDING && txn.phase != ConquestTxn.Phase.NEEDS_ADMIN) {
                it.remove();
                finished--;
            }
        }
    }

    public Map<UUID, ClaimRecord> claims() {
        return state.claims;
    }

    public ClaimRecord claim(UUID id) {
        return state.claims.get(id);
    }

    public boolean isConquered(UUID claimId) {
        ClaimRecord record = state.claims.get(claimId);
        return record != null && record.conquered;
    }

    public String design(UUID player) {
        return state.designs.get(player);
    }

    public void setDesign(UUID player, String snbt) {
        if (snbt == null) {
            state.designs.remove(player);
        } else {
            state.designs.put(player, snbt);
        }
        dirty = true;
    }

    public List<ConquestTxn> transactions() {
        return state.transactions;
    }

    public List<OrphanFlag> orphanFlags() {
        return state.orphanFlags;
    }

    /** Records an event for an offline player, coalescing repeats (bounded per player). */
    public void recordAlert(UUID player, String type, UUID claimId, String label, String dimension, int x, int z) {
        long now = System.currentTimeMillis();
        List<AlertEntry> list = state.alertHistory.computeIfAbsent(player, p -> new ArrayList<>());
        for (AlertEntry entry : list) {
            if (entry.type.equals(type) && entry.claimId.equals(claimId) && now - entry.lastAt < COALESCE_MILLIS) {
                entry.lastAt = now;
                entry.count++;
                dirty = true;
                return;
            }
        }
        AlertEntry entry = new AlertEntry();
        entry.type = type;
        entry.claimId = claimId;
        entry.label = label;
        entry.dimension = dimension;
        entry.x = x;
        entry.z = z;
        entry.firstAt = now;
        entry.lastAt = now;
        entry.count = 1;
        list.add(entry);
        while (list.size() > MAX_ALERTS_PER_PLAYER) {
            list.remove(0);
        }
        dirty = true;
    }

    /** Returns and clears a player's stored alerts. */
    public List<AlertEntry> takeAlerts(UUID player) {
        List<AlertEntry> list = state.alertHistory.remove(player);
        if (list == null) {
            return new ArrayList<>();
        }
        dirty = true;
        return list;
    }

    public boolean isMuted(UUID player) {
        return state.mutedAlerts.contains(player);
    }

    public void setMuted(UUID player, boolean muted) {
        state.mutedAlerts.remove(player);
        if (muted) {
            state.mutedAlerts.add(player);
        }
        dirty = true;
    }
}
