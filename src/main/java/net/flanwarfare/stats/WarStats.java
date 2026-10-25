package net.flanwarfare.stats;

import io.github.flemmli97.flan.claim.Claim;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.core.WarCore;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.ConquestTxn;
import net.flanwarfare.data.Season;
import net.flanwarfare.data.WarData;
import net.flanwarfare.data.WarEvent;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.logic.StatRow;
import net.flanwarfare.logic.StatsAggregator;
import net.flanwarfare.team.SideResolver;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Season statistics, war history and archives.
 *
 * <p>Separation of concerns:
 * <ul>
 *   <li><b>History</b> = the active season's {@link WarEvent} list, stored in war_data.json.
 *   Conquest events are added in the same atomic write that commits the conquest transaction
 *   (event id = transaction id), so a crash can never produce zero or two events for one
 *   conquest. Defense events have random ids and are written once.</li>
 *   <li><b>Current territory</b> = live Flan ownership of the add-on's claim records, computed
 *   on demand. Admin and excluded claims have no records and therefore never count.</li>
 * </ul>
 * Season operations never touch claims, ownership, conquest slots, immunity, flags or raid
 * configuration.
 */
public final class WarStats {

    /** On-disk archive of a finished season, readable without affecting the active one. */
    public static final class SeasonArchive {
        public Season season;
        public long archivedAt;
        public String reason;
        public List<StatRow> finalPlayers = new ArrayList<>();
        public List<StatRow> finalKingdoms = new ArrayList<>();
    }

    private final WarCore core;

    public WarStats(WarCore core) {
        this.core = core;
    }

    private WarData data() {
        return core.data();
    }

    /** Creates the first season on a fresh installation and repairs loaded data. */
    public void init() {
        Season season = data().season();
        if (season == null && data().seasonCounter() == 0) {
            startSeason(WarConfig.SEASON_NAME.get());
            FlanWarfare.LOG.info("Started first season '{}'", WarConfig.SEASON_NAME.get());
        } else if (season != null && season.events == null) {
            season.events = new ArrayList<>();
        }
    }

    public Season season() {
        return data().season();
    }

    /** True if new events are being recorded. */
    public boolean recording() {
        Season season = season();
        return WarConfig.LB_ENABLED.get() && season != null && season.isActive();
    }

    // ------------------------------------------------------------------ recording

    /** Adds the conquest event for a committed transaction; no-op if it already exists. */
    public void recordConquest(ConquestTxn txn) {
        if (!recording()) {
            return;
        }
        String id = txn.txnId.toString();
        if (find(id) != null) {
            return;
        }
        WarEvent e = new WarEvent();
        e.id = id;
        e.type = WarEvent.CONQUEST;
        e.seasonId = season().id;
        e.at = txn.finishedAt > 0 ? txn.finishedAt : System.currentTimeMillis();
        e.claimId = txn.claimId;
        e.claimName = txn.claimLabel;
        e.dimension = txn.dimension;
        e.x = txn.x;
        e.z = txn.z;
        e.previousOwner = txn.oldOwner;
        e.previousOwnerName = txn.oldOwnerName;
        e.newOwner = txn.newOwner;
        e.newOwnerName = txn.newOwnerName;
        e.previousKingdomId = txn.oldKingdomId;
        e.previousKingdomName = txn.oldKingdomName;
        e.actingKingdomId = txn.newKingdomId;
        e.actingKingdomName = txn.newKingdomName;
        e.participants.addAll(txn.participants);
        e.participantNames.addAll(txn.participantNames);
        e.txnId = txn.txnId;
        e.peakPercent = 100;
        season().events.add(e);
        data().markDirty();
    }

    /** Last credited defense of a claim this season, 0 if none (anti-farming cooldown). */
    public long lastDefenseAt(UUID claimId) {
        Season season = season();
        if (season == null) {
            return 0L;
        }
        long last = 0L;
        for (WarEvent e : season.events) {
            if (WarEvent.DEFENSE.equals(e.type) && claimId.equals(e.claimId)) {
                last = Math.max(last, e.at);
            }
        }
        return last;
    }

    public boolean defensesTracked() {
        return recording() && WarConfig.LB_TRACK_DEFENSES.get();
    }

    /** Records one successful defense for a capture session (callers check qualification). */
    public void recordDefense(Claim claim, ClaimRecord record, Collection<UUID> defenders, Collection<UUID> attackers,
                              int peakPercent) {
        if (!defensesTracked()) {
            return;
        }
        WarEvent e = new WarEvent();
        e.id = UUID.randomUUID().toString();
        e.type = WarEvent.DEFENSE;
        e.seasonId = season().id;
        e.at = System.currentTimeMillis();
        e.claimId = record.claimId;
        e.claimName = FlanBridge.label(claim);
        e.dimension = record.dimension;
        e.x = record.flagX;
        e.z = record.flagZ;
        e.previousOwner = claim.getOwner();
        e.previousOwnerName = FlanBridge.playerName(core.server(), claim.getOwner());
        SideResolver.Kingdom defending = core.sides().kingdomOf(claim.getOwner());
        if (defending != null) {
            e.actingKingdomId = defending.id;
            e.actingKingdomName = defending.name;
        }
        for (UUID id : defenders) {
            e.participants.add(id);
            e.participantNames.add(FlanBridge.playerName(core.server(), id));
        }
        for (UUID id : attackers) {
            e.opponentNames.add(FlanBridge.playerName(core.server(), id));
            if (e.opposingKingdomId == null) {
                SideResolver.Kingdom attacking = core.sides().kingdomOf(id);
                if (attacking != null) {
                    e.opposingKingdomId = attacking.id;
                    e.opposingKingdomName = attacking.name;
                }
            }
        }
        e.peakPercent = peakPercent;
        season().events.add(e);
        data().markDirty();
        core.audit("Successful defense of claim {} by {} (peak {}%)", record.claimId, e.participantNames, peakPercent);
    }

    /**
     * Correction policy for administrator rollbacks: the conquest event stays in the history,
     * marked reversed (it no longer counts for anyone), and a CONQUEST_REVERSED entry records
     * who reversed it and when. Returns a note for the admin.
     */
    public String reverseConquest(UUID txnId, String adminName) {
        WarEvent e = find(txnId.toString());
        if (e == null) {
            return "No statistics entry in the current season (statistics unchanged).";
        }
        if (e.reversed) {
            return "Its statistics entry was already reversed.";
        }
        e.reversed = true;
        e.reversedAt = System.currentTimeMillis();
        e.reversedNote = "rolled back by " + adminName;
        WarEvent correction = new WarEvent();
        correction.id = "reversal-" + e.id;
        correction.type = WarEvent.CONQUEST_REVERSED;
        correction.seasonId = e.seasonId;
        correction.at = e.reversedAt;
        correction.claimId = e.claimId;
        correction.claimName = e.claimName;
        correction.dimension = e.dimension;
        correction.x = e.x;
        correction.z = e.z;
        correction.previousOwner = e.newOwner;
        correction.previousOwnerName = e.newOwnerName;
        correction.newOwner = e.previousOwner;
        correction.newOwnerName = e.previousOwnerName;
        correction.correctsEventId = e.id;
        correction.reversedNote = e.reversedNote;
        correction.txnId = e.txnId;
        if (find(correction.id) == null) {
            season().events.add(correction);
        }
        data().markDirty();
        return "Its conquest statistics were reversed (kept in the war log as reversed).";
    }

    private WarEvent find(String id) {
        Season season = season();
        if (season == null) {
            return null;
        }
        for (WarEvent e : season.events) {
            if (id.equals(e.id)) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ computing

    /** Current ownership of every ordinary claim, straight from Flan. */
    public List<StatsAggregator.Ownership> ownership() {
        List<StatsAggregator.Ownership> list = new ArrayList<>();
        for (ClaimRecord record : data().claims().values()) {
            Claim claim = FlanBridge.find(core.server(), record);
            if (claim == null || claim.isAdminClaim() || core.isExcluded(claim)) {
                continue;
            }
            UUID owner = claim.getOwner();
            SideResolver.Kingdom kingdom = core.sides().kingdomOf(owner);
            list.add(new StatsAggregator.Ownership(owner, FlanBridge.playerName(core.server(), owner), record.conquered,
                    kingdom == null ? null : kingdom.id, kingdom == null ? null : kingdom.name));
        }
        return list;
    }

    public StatsAggregator aggregate() {
        Season season = season();
        List<WarEvent> events = season == null ? new ArrayList<WarEvent>() : season.events;
        return StatsAggregator.compute(events, ownership());
    }

    public List<WarEvent> events() {
        Season season = season();
        return season == null ? new ArrayList<WarEvent>() : season.events;
    }

    // ------------------------------------------------------------------ seasons

    public Path archiveDir() {
        return data().file().getParent().resolve("seasons");
    }

    public String startSeason(String name) {
        Season current = season();
        if (current != null && current.isActive()) {
            return "Season '" + current.name + "' is still running. End it first with /war admin season end.";
        }
        if (current != null) {
            String problem = archiveCurrent("archived when the next season started");
            if (problem != null) {
                return problem;
            }
        }
        Season season = new Season();
        season.id = data().nextSeasonId();
        season.name = name;
        season.startedAt = System.currentTimeMillis();
        data().setSeason(season);
        core.saveNow();
        core.audit("Season {} '{}' started", season.id, name);
        return null;
    }

    public String endSeason() {
        Season season = season();
        if (season == null || !season.isActive()) {
            return "There is no running season.";
        }
        season.endedAt = System.currentTimeMillis();
        data().markDirty();
        core.saveNow();
        core.audit("Season {} '{}' ended", season.id, season.name);
        return null;
    }

    /** Archives the ended current season and clears it from the active data. */
    public String archiveCurrent(String reason) {
        Season season = season();
        if (season == null) {
            return "There is no season to archive.";
        }
        if (season.isActive()) {
            return "End the season first with /war admin season end.";
        }
        try {
            writeArchive(season, season.id, reason);
        } catch (IOException e) {
            FlanWarfare.LOG.error("Could not write season archive", e);
            return "Could not write the archive file: " + e.getMessage() + ". Nothing was changed.";
        }
        data().setSeason(null);
        core.saveNow();
        core.audit("Season {} archived ({})", season.id, reason);
        return null;
    }

    /**
     * Safe statistics reset: writes a recoverable archive copy first, then clears the active
     * season's events. Claims, ownership, slots, immunity and flags are untouched.
     */
    public String resetSeason(String adminName) {
        Season season = season();
        if (season == null) {
            return "There is no season to reset.";
        }
        String copyId = season.id + "-reset-" + System.currentTimeMillis();
        try {
            writeArchive(season, copyId, "backup before reset by " + adminName);
        } catch (IOException e) {
            FlanWarfare.LOG.error("Could not write reset backup", e);
            return "Could not write the backup archive: " + e.getMessage() + ". Nothing was reset.";
        }
        season.events.clear();
        season.startedAt = System.currentTimeMillis();
        data().markDirty();
        core.saveNow();
        core.audit("Season {} statistics reset by {} (backup archive {})", season.id, adminName, copyId);
        return null;
    }

    private void writeArchive(Season season, String fileId, String reason) throws IOException {
        SeasonArchive archive = new SeasonArchive();
        archive.season = season;
        archive.archivedAt = System.currentTimeMillis();
        archive.reason = reason;
        StatsAggregator stats = StatsAggregator.compute(season.events, ownership());
        archive.finalPlayers = stats.players(StatRow.BY_CONQUESTS);
        archive.finalKingdoms = stats.kingdoms(StatRow.BY_CONQUESTS);
        WarData.writeJson(archiveDir().resolve(fileId + ".json"), archive);
    }

    public List<String> archiveIds() {
        List<String> ids = new ArrayList<>();
        Path dir = archiveDir();
        if (!Files.isDirectory(dir)) {
            return ids;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                ids.add(name.substring(0, name.length() - 5));
            }
        } catch (IOException e) {
            FlanWarfare.LOG.warn("Could not list season archives: {}", e.toString());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    public SeasonArchive loadArchive(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("invalid archive id");
        }
        Path file = archiveDir().resolve(id + ".json");
        if (!Files.exists(file)) {
            throw new IOException("no archive named " + id);
        }
        SeasonArchive archive = WarData.readJson(file, SeasonArchive.class);
        if (archive.season != null && archive.season.events == null) {
            archive.season.events = new ArrayList<>();
        }
        return archive;
    }

    // ------------------------------------------------------------------ reconciliation

    /** Checks statistics against Flan and the journal; removes exact duplicate events. */
    public List<String> reconcile() {
        List<String> report = new ArrayList<>();
        Season season = season();
        if (season == null) {
            report.add("No active season: statistics are not being recorded.");
            return report;
        }
        Set<String> seen = new HashSet<>();
        int removed = 0;
        Iterator<WarEvent> it = season.events.iterator();
        while (it.hasNext()) {
            WarEvent e = it.next();
            if (e == null || e.id == null || !seen.add(e.id)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            data().markDirty();
            report.add("Removed " + removed + " duplicate or invalid event(s).");
        }
        int noKingdom = 0;
        for (WarEvent e : season.events) {
            if (WarEvent.CONQUEST.equals(e.type) && (e.actingKingdomId == null || e.previousKingdomId == null)) {
                noKingdom++;
            }
        }
        if (noKingdom > 0) {
            report.add(noKingdom + " conquest(s) have no kingdom attribution (player had no kingdom or FTB Teams was unavailable).");
        }
        int missingEvents = 0;
        for (ConquestTxn txn : data().transactions()) {
            if (txn.phase == ConquestTxn.Phase.COMMITTED && txn.finishedAt >= season.startedAt
                    && find(txn.txnId.toString()) == null) {
                missingEvents++;
            }
        }
        if (missingEvents > 0) {
            report.add(missingEvents + " committed conquest(s) in this season have no statistics entry (e.g. recorded while "
                    + "statistics were disabled). They are reported, not invented.");
        }
        int mismatched = 0;
        for (ClaimRecord record : data().claims().values()) {
            Claim claim = FlanBridge.find(core.server(), record);
            if (claim != null && !claim.getOwner().equals(record.owner)) {
                mismatched++;
                core.lifecycle().recordFor(claim);
            }
        }
        if (mismatched > 0) {
            report.add("Re-synced " + mismatched + " claim owner(s) from Flan.");
        }
        if (report.isEmpty()) {
            report.add("Statistics are consistent: " + season.events.size() + " event(s) in season " + season.id + ".");
        }
        return report;
    }
}
