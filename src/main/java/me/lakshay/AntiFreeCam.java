package me.lakshay;

import org.bukkit.plugin.java.JavaPlugin;

public final class AntiFreeCam extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("AntiFreeCam Enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("AntiFreeCam Disabled!");
    }
}
