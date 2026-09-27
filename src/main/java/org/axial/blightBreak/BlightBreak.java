package org.axial.blightBreak;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class BlightBreak extends JavaPlugin {

    @Override
    public void onEnable() {
        getCommand("test").setExecutor(this::handleTestCommand);
    }

    private boolean handleTestCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(ChatColor.GREEN.toString() + ChatColor.BOLD + "Hello :)");
        return true;
    }
}
