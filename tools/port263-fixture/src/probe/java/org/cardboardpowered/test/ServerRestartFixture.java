package org.cardboardpowered.test;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicitly enabled only by the disposable process-restart runner. */
final class ServerRestartFixture {
    private static boolean enabled() { return Boolean.getBoolean("cardboard.restartFixture"); }
    static void started() {
        if (enabled()) write("restart-fixture-started.json", Map.of("pid", ProcessHandle.current().pid()));
    }
    static void stopped() {
        if (enabled()) write("restart-fixture-disabled-" + ProcessHandle.current().pid() + ".json",
                Map.of("pid", ProcessHandle.current().pid(), "primaryThread", Bukkit.isPrimaryThread()));
    }
    static boolean command(JavaPlugin plugin, CommandSender sender, String[] args) {
        if (args.length != 2 || !args[0].equals("serverops")) return false;
        if (!enabled()) throw new IllegalStateException("Restart fixture flag is absent");
        String mode = args[1];
        if (!java.util.Set.of("api", "spigot", "command").contains(mode)) throw new IllegalArgumentException(mode);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Runnable request = () -> {
                write("restart-fixture-request.json", Map.of("pid", ProcessHandle.current().pid(), "primaryThread", Bukkit.isPrimaryThread(), "mode", mode));
                if (mode.equals("api")) { Bukkit.getServer().restart(); Bukkit.getServer().restart(); }
                else if (mode.equals("spigot")) { Bukkit.getServer().spigot().restart(); Bukkit.getServer().spigot().restart(); }
                else write("restart-fixture-command.json", Map.of("dispatched", Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart")));
            };
            if (mode.equals("spigot")) Thread.ofPlatform().daemon().name("Restart fixture async caller").start(request);
            else request.run();
        }, 5);
        sender.sendMessage("SERVER_OPS_RESTART_ARMED " + mode);
        return true;
    }
    private static void write(String file, Map<String, ?> data) {
        try { Files.writeString(Path.of(file), new Gson().toJson(data)); }
        catch (Exception failure) { throw new IllegalStateException("Cannot write restart fixture proof", failure); }
    }
}
