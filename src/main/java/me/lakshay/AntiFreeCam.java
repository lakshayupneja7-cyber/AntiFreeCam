package me.lakshay;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiFreeCam extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AntiFreeCam Enabled!");

        // Constant check task
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {

                if (player.isFlying() && !player.hasPermission("antifreecam.bypass")) {
                    player.kickPlayer("§cFreeCam Detected!\n§7Please remove FreeCam and rejoin.");
                }

            }
        }, 0L, 40L); // every 2 seconds
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // Ignore pure head rotation (mouse movement)
        if (event.getFrom().getX() == event.getTo().getX() &&
            event.getFrom().getY() == event.getTo().getY() &&
            event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        // Check abnormal teleport distance
        double distance = event.getFrom().distance(event.getTo());

        if (distance > 8 && !player.hasPermission("antifreecam.bypass")) {
            player.kickPlayer("§cFreeCam Detected!\n§7Please remove FreeCam and rejoin.");
        }
    }
}
