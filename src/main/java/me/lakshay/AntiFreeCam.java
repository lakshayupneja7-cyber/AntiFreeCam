package me.lakshay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AntiFreeCam extends JavaPlugin implements Listener {

    // --- Tunables (safe defaults) ---
    private static final long JOIN_GRACE_MS = 20_000;              // don't detect within first 20s after join
    private static final int STATIONARY_TICKS_REQUIRED = 200;       // 200 ticks = 10 seconds
    private static final double MOVE_EPSILON_SQ = 0.0001;           // ignore tiny float changes
    private static final double ROTATION_SCORE_KICK = 1200.0;       // total rotation score needed to kick
    private static final String BYPASS_PERM = "antifreecam.bypass";
    private static final String KICK_MSG = "§cPlease remove FreeCam, then rejoin.";

    // --- State ---
    private final Map<UUID, Long> joinTime = new HashMap<>();
    private final Map<UUID, Integer> stationaryTicks = new HashMap<>();
    private final Map<UUID, Float> lastYaw = new HashMap<>();
    private final Map<UUID, Float> lastPitch = new HashMap<>();
    private final Map<UUID, Double> rotationScore = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // Decay rotation score so it doesn't stick forever (safe: no modifying while iterating)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            rotationScore.replaceAll((id, score) -> Math.max(0.0, score - 80.0)); // decay per 2s
        }, 40L, 40L); // every 2 seconds

        getLogger().info("AntiFreeCam Enabled!");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        joinTime.put(id, System.currentTimeMillis());
        stationaryTicks.put(id, 0);
        rotationScore.put(id, 0.0);

        Location l = e.getPlayer().getLocation();
        lastYaw.put(id, l.getYaw());
        lastPitch.put(id, l.getPitch());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        joinTime.remove(id);
        stationaryTicks.remove(id);
        lastYaw.remove(id);
        lastPitch.remove(id);
        rotationScore.remove(id);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission(BYPASS_PERM)) return;

        Location to = e.getTo();
        if (to == null) return;

        UUID id = p.getUniqueId();

        // Grace period after join (prevents "join + look around" false kicks)
        long jt = joinTime.getOrDefault(id, System.currentTimeMillis());
        if (System.currentTimeMillis() - jt < JOIN_GRACE_MS) {
            // Update yaw/pitch so we don't get huge first delta later
            lastYaw.put(id, to.getYaw());
            lastPitch.put(id, to.getPitch());
            return;
        }

        Location from = e.getFrom();

        // Position movement check (ignore micro-deltas)
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        boolean moved = (dx * dx + dy * dy + dz * dz) > MOVE_EPSILON_SQ;

        // Rotation deltas
        float prevYaw = lastYaw.getOrDefault(id, from.getYaw());
        float prevPitch = lastPitch.getOrDefault(id, from.getPitch());

        float yaw = to.getYaw();
        float pitch = to.getPitch();

        double dYaw = Math.abs(wrapAngle(yaw - prevYaw));  // normalized 0..180
        double dPitch = Math.abs(pitch - prevPitch);       // 0..180

        lastYaw.put(id, yaw);
        lastPitch.put(id, pitch);

        if (moved) {
            // If player truly moved, reset suspicion quickly
            stationaryTicks.put(id, 0);
            rotationScore.put(id, Math.max(0.0, rotationScore.getOrDefault(id, 0.0) - 150.0));
            return;
        }

        // Player is stationary this tick
        int st = stationaryTicks.getOrDefault(id, 0) + 1;
        stationaryTicks.put(id, st);

        // Only start scoring after they've been stationary for a bit
        if (st < 40) return; // 2 seconds idle is normal

        // Add to score only if rotation is "meaningful" (small mouse jitter won't add much)
        double add = 0.0;
        if (dYaw > 12) add += dYaw;
        if (dPitch > 8) add += dPitch * 0.8;

        if (add > 0) {
            rotationScore.put(id, rotationScore.getOrDefault(id, 0.0) + add);
        }

        // Kick only if the pattern is sustained
        if (st >= STATIONARY_TICKS_REQUIRED && rotationScore.getOrDefault(id, 0.0) >= ROTATION_SCORE_KICK) {
            p.kickPlayer(KICK_MSG);
        }
    }

    private static float wrapAngle(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }
}
