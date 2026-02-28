package me.lakshay;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.PacketType;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AntiFreeCam extends JavaPlugin implements Listener {

    // ---- Settings (tune these) ----
    private static final String BYPASS_PERM = "antifreecam.bypass";
    private static final String KICK_MSG = "§cPlease remove FreeCam, then rejoin.";

    private static final long JOIN_GRACE_MS = 20_000;         // no detection immediately on join
    private static final long STATIONARY_FOR_MS = 30_000;      // only care after 30s of not moving
    private static final long WINDOW_MS = 2_000;              // measure rotation bursts in 2 seconds
    private static final double BURST_YAW_DEG = 220.0;         // yaw burst threshold in window
    private static final double BURST_PITCH_DEG = 140.0;       // pitch burst threshold in window

    private static final double MOVE_EPSILON_SQ = 0.0001;      // ignore micro movement
    private static final long WARN_COOLDOWN_MS = 15_000;       // don't spam warnings

    // ---- State ----
    private final Map<UUID, Long> joinTime = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final Map<UUID, Long> lastMovedAt = new HashMap<>();

    private final Map<UUID, Float> lastYaw = new HashMap<>();
    private final Map<UUID, Float> lastPitch = new HashMap<>();

    // Burst accumulation window
    private final Map<UUID, Long> windowStart = new HashMap<>();
    private final Map<UUID, Double> yawAccum = new HashMap<>();
    private final Map<UUID, Double> pitchAccum = new HashMap<>();

    // Escalation
    private final Map<UUID, Integer> strikes = new HashMap<>();
    private final Map<UUID, Long> lastWarnAt = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        // ProtocolLib must be installed on the server
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found! Install ProtocolLib or this plugin won't detect FreeCam.");
            return;
        }

        ProtocolManager pm = ProtocolLibrary.getProtocolManager();

        pm.addPacketListener(new PacketAdapter(
                this,
                ListenerPriority.NORMAL,
                PacketType.Play.Client.LOOK,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK,
                PacketType.Play.Client.FLYING
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleMovementPacket(event);
            }
        });

        getLogger().info("AntiFreeCam (ProtocolLib) Enabled!");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        joinTime.put(id, System.currentTimeMillis());
        lastPos.put(id, p.getLocation().clone());
        lastMovedAt.put(id, System.currentTimeMillis());

        Location l = p.getLocation();
        lastYaw.put(id, l.getYaw());
        lastPitch.put(id, l.getPitch());

        windowStart.put(id, System.currentTimeMillis());
        yawAccum.put(id, 0.0);
        pitchAccum.put(id, 0.0);

        strikes.put(id, 0);
        lastWarnAt.put(id, 0L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        joinTime.remove(id);
        lastPos.remove(id);
        lastMovedAt.remove(id);
        lastYaw.remove(id);
        lastPitch.remove(id);
        windowStart.remove(id);
        yawAccum.remove(id);
        pitchAccum.remove(id);
        strikes.remove(id);
        lastWarnAt.remove(id);
    }

    private void handleMovementPacket(PacketEvent event) {
        Player p = event.getPlayer();
        if (p == null) return;
        if (p.hasPermission(BYPASS_PERM)) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        // Join grace
        long jt = joinTime.getOrDefault(id, now);
        if (now - jt < JOIN_GRACE_MS) {
            // keep yaw/pitch updated to avoid huge first delta
            Location cur = p.getLocation();
            lastYaw.put(id, cur.getYaw());
            lastPitch.put(id, cur.getPitch());
            return;
        }

        // Track server-side movement (position)
        Location curPos = p.getLocation();
        Location prevPos = lastPos.get(id);
        if (prevPos == null) {
            lastPos.put(id, curPos.clone());
            lastMovedAt.put(id, now);
            return;
        }

        double dx = curPos.getX() - prevPos.getX();
        double dy = curPos.getY() - prevPos.getY();
        double dz = curPos.getZ() - prevPos.getZ();
        boolean moved = (dx * dx + dy * dy + dz * dz) > MOVE_EPSILON_SQ;

        if (moved) {
            lastPos.put(id, curPos.clone());
            lastMovedAt.put(id, now);

            // Reset suspicion when they actually move
            yawAccum.put(id, 0.0);
            pitchAccum.put(id, 0.0);
            windowStart.put(id, now);
            strikes.put(id, 0);
            return;
        }

        // Only care after they've been stationary for a while
        long lastMove = lastMovedAt.getOrDefault(id, now);
        if (now - lastMove < STATIONARY_FOR_MS) {
            // Still stationary but not long enough to suspect
            lastPos.put(id, curPos.clone());
            return;
        }

        // Read rotation *from packet timing* (using server location yaw/pitch updated by packets)
        float prevYaw = lastYaw.getOrDefault(id, curPos.getYaw());
        float prevPitch = lastPitch.getOrDefault(id, curPos.getPitch());

        float yaw = curPos.getYaw();
        float pitch = curPos.getPitch();

        double dYaw = Math.abs(wrapAngle(yaw - prevYaw)); // 0..180
        double dPitch = Math.abs(pitch - prevPitch);

        lastYaw.put(id, yaw);
        lastPitch.put(id, pitch);

        // Accumulate in a sliding window
        long ws = windowStart.getOrDefault(id, now);
        if (now - ws > WINDOW_MS) {
            windowStart.put(id, now);
            yawAccum.put(id, 0.0);
            pitchAccum.put(id, 0.0);
        }

        yawAccum.put(id, yawAccum.getOrDefault(id, 0.0) + dYaw);
        pitchAccum.put(id, pitchAccum.getOrDefault(id, 0.0) + dPitch);

        double yA = yawAccum.getOrDefault(id, 0.0);
        double pA = pitchAccum.getOrDefault(id, 0.0);

        // Trigger only on big bursts while stationary for long time
        if (yA >= BURST_YAW_DEG || pA >= BURST_PITCH_DEG) {
            int s = strikes.getOrDefault(id, 0) + 1;
            strikes.put(id, s);

            // Warn once first (reduces false kicks)
            if (s == 1) {
                long lw = lastWarnAt.getOrDefault(id, 0L);
                if (now - lw > WARN_COOLDOWN_MS) {
                    lastWarnAt.put(id, now);
                    p.sendMessage("§cFreeCam suspected. §7Disable FreeCam or you may be kicked.");
                }
                // reset window so they must keep doing it to get kicked
                windowStart.put(id, now);
                yawAccum.put(id, 0.0);
                pitchAccum.put(id, 0.0);
                return;
            }

            // Strike 2 => kick
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
