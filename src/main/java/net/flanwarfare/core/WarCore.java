package net.flanwarfare.core;

import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.config.ConfigHandler;
import net.flanwarfare.FlanWarfare;
import net.flanwarfare.alerts.ClaimWarState;
import net.flanwarfare.alerts.RaidAlerts;
import net.flanwarfare.capture.CaptureAura;
import net.flanwarfare.capture.CaptureManager;
import net.flanwarfare.claims.ClaimLifecycle;
import net.flanwarfare.config.WarConfig;
import net.flanwarfare.conquest.ConquestService;
import net.flanwarfare.data.ClaimRecord;
import net.flanwarfare.data.WarData;
import net.flanwarfare.flag.FlagService;
import net.flanwarfare.flan.FlanBridge;
import net.flanwarfare.raid.RaidScheduler;
import net.flanwarfare.stats.WarStats;
import net.flanwarfare.team.SideResolver;
import net.flanwarfare.util.Msg;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Owns all add-on services for one running server. Created when the server has finished
 * starting (after Flan has loaded every claim), discarded when it stops.
 *
 * <p>If the add-on's data file exists but cannot be read, the core starts in <b>safe mode</b>:
 * nothing is overridden, so Flan's ordinary protection applies everywhere and raids are off,
 * until an administrator fixes or removes the file. This avoids treating every claim as new
 * (and therefore unprotected) after data loss.
 */
public final class WarCore {
    private static volatile WarCore instance;

    private final MinecraftServer server;
    private final WarData data;
    private final String safeModeReason;
    private final RaidScheduler raid;
    private final ClaimLifecycle lifecycle;
    private final FlagService flags;
    private final CaptureManager capture;
    private final ConquestService conquest;
    private final SideResolver sides;
    private final RaidAlerts alerts;
    private final CaptureAura aura;
    private final WarStats stats;
    private volatile boolean ready;
    private long ticks;
    private long lastSaveTick;

    private WarCore(MinecraftServer server, WarData data, String safeModeReason) {
        this.server = server;
        this.data = data;
        this.safeModeReason = safeModeReason;
        this.raid = new RaidScheduler(this);
        this.sides = new SideResolver();
        this.lifecycle = new ClaimLifecycle(this);
        this.flags = new FlagService(this);
        this.capture = new CaptureManager(this);
        this.conquest = new ConquestService(this);
        this.alerts = new RaidAlerts(this);
        this.aura = new CaptureAura(this);
        this.stats = new WarStats(this);
    }

    /** The current core, possibly in safe mode. */
    public static WarCore get() {
        return instance;
    }

    /** The current core only if it is fully operational, otherwise null. */
    public static WarCore ready() {
        WarCore core = instance;
        return core != null && core.ready ? core : null;
    }

    public static Path dataFile(MinecraftServer server) {
        // Flan 1.7.2 stores Overworld claims in <world>/data/claims; keep our file beside it.
        Path flanClaims = ConfigHandler.getClaimSavePath(server, World.OVERWORLD);
        return flanClaims.getParent().resolve(FlanWarfare.MOD_ID).resolve("war_data.json");
    }

    static void start(MinecraftServer server) {
        Path file = dataFile(server);
        WarData data;
        try {
            data = WarData.load(file);
        } catch (IOException e) {
            FlanWarfare.LOG.error("Cannot read {}. Starting in SAFE MODE: Flan's normal protection applies, "
                    + "raids, flags and capture are disabled until the file is repaired or removed. "
                    + "A backup may exist as war_data.json.bak.", file, e);
            instance = new WarCore(server, null, e.getMessage());
            return;
        }
        WarCore core = new WarCore(server, data, null);
        instance = core;
        for (String problem : WarConfig.validate()) {
            FlanWarfare.LOG.warn("Config problem: {}", problem);
        }
        core.sides.init();
        core.stats.init();
        core.conquest.recoverPendingTransactions();
        core.lifecycle.reconcileAll();
        WarStats.Report report = core.stats.check();
        for (String warning : report.warnings) {
            FlanWarfare.LOG.warn("Statistics check: {} - run /war admin stats check for details", warning);
        }
        core.flags.rebuildIndex();
        core.flags.refreshAllOnLoad();
        core.raid.initialise();
        core.ready = true;
        core.saveNow();
        FlanWarfare.LOG.info("Flan Warfare ready: {} claim records, raid mode {}, teams: {}",
                data.claims().size(), WarConfig.RAID_MODE.get(), core.sides.status());
    }

    static void stop() {
        WarCore core = instance;
        if (core != null && core.ready) {
            core.capture.clearAll();
            core.saveNow();
        }
        instance = null;
    }

    void tick() {
        if (!ready) {
            return;
        }
        ticks++;
        raid.tick();
        lifecycle.tick(ticks);
        flags.tick(ticks);
        capture.tick(ticks);
        aura.tick(ticks);
        if (data.isDirty() && ticks - lastSaveTick >= 200) {
            saveNow();
        }
    }

    /** Writes the data file immediately. Returns false (and logs) if the write failed. */
    public boolean saveNow() {
        if (data == null) {
            return false;
        }
        try {
            data.save();
            lastSaveTick = ticks;
            return true;
        } catch (IOException e) {
            FlanWarfare.LOG.error("Failed to write war data to {}", data.file(), e);
            Msg.admins(server, Msg.error("Flan Warfare could not save its data file. Check the server log."));
            return false;
        }
    }

    public void audit(String message, Object... args) {
        if (WarConfig.AUDIT_LOGGING.get()) {
            FlanWarfare.LOG.info("[audit] " + message, args);
        }
    }

    public boolean isDimensionDisabled(String dimension) {
        return WarConfig.DISABLED_DIMENSIONS.get().contains(dimension);
    }

    /** Claims that the add-on must leave entirely to Flan. */
    public boolean isExcluded(Claim parent) {
        if (isDimensionDisabled(FlanBridge.dimensionId(parent.getWorld()))) {
            return true;
        }
        return WarConfig.EXCLUDED_CLAIMS.get().contains(parent.getClaimID().toString());
    }

    public MinecraftServer server() {
        return server;
    }

    public WarData data() {
        return data;
    }

    public boolean isReady() {
        return ready;
    }

    public String safeModeReason() {
        return safeModeReason;
    }

    public long ticks() {
        return ticks;
    }

    public RaidScheduler raid() {
        return raid;
    }

    public ClaimLifecycle lifecycle() {
        return lifecycle;
    }

    public FlagService flags() {
        return flags;
    }

    public CaptureManager capture() {
        return capture;
    }

    public ConquestService conquest() {
        return conquest;
    }

    public SideResolver sides() {
        return sides;
    }

    public RaidAlerts alerts() {
        return alerts;
    }

    public CaptureAura aura() {
        return aura;
    }

    public WarStats stats() {
        return stats;
    }

    /** Live state of a claim (see {@link ClaimWarState}). */
    public ClaimWarState warState(ClaimRecord record) {
        return ClaimWarState.of(capture.statusOf(record.claimId), alerts.underAttack(record.claimId), record.conquered);
    }
}
