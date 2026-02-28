package me.lakshay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.UUID;

public final class AntiFreeCam extends JavaPlugin implements Listener {

    private final HashMap<UUID, Location> lastLocation = new HashMap<>();
    private final HashMap<UUID, Long> lastMoveTime = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AntiFreeCam Enabled!");
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        // Check if player position changed
        boolean movedPosition = from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();

        // Check if player rotated only
        boolean rotated = from.getYaw() != to.getYaw()
                || from.getPitch() != to.getPitch();

        if (!movedPosition && rotated) {
            long currentTime = System.currentTimeMillis();

            if (!lastMoveTime.containsKey(uuid)) {
                lastMoveTime.put(uuid, currentTime);
            } else {
                long diff = currentTime - lastMoveTime.get(uuid);

                // 5 seconds rotating without moving
                if (diff > 5000) {
                    player.kickPlayer("FreeCam Detected!");
                }
            }
        } else if (movedPosition) {
            lastMoveTime.remove(uuid);
        }
    }
}
