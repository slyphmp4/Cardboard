package org.cardboardpowered.test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Filter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Mutates only the explicitly opted-in disposable server's configuration, restoring exact bytes. */
final class ServerAliasProbes {
    private static final String PREFIX = "p263alias_";

    static void verify(BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("server-command-alias-startup-config", () -> {
            require(Arrays.equals(Bukkit.getServer().getCommandAliases().get("port263boot_alias"), new String[] {"version $1-"}),
                    "Startup commands.yml alias was not loaded");
            require(map().getCommand("port263boot_alias") instanceof FormattedCommandAlias, "Startup alias was not registered");
        });
        check.accept("server-command-alias-values-defensive-order", () -> {
            try (var scope = new Scope()) {
                var aliases = new LinkedHashMap<String, Object>();
                aliases.put(PREFIX + "Mixed", PREFIX + "target fixed $1-");
                aliases.put(PREFIX + "multi", List.of(PREFIX + "target first $1", PREFIX + "target second $2-"));
                scope.load(aliases);
                var values = Bukkit.getServer().getCommandAliases();
                require(values.keySet().stream().filter(key -> key.startsWith(PREFIX)).toList().equals(List.copyOf(aliases.keySet())), "Alias order or original key spelling was lost");
                require(Arrays.equals(values.get(PREFIX + "Mixed"), new String[] {PREFIX + "target fixed $1-"})
                        && Arrays.equals(values.get(PREFIX + "multi"), new String[] {PREFIX + "target first $1", PREFIX + "target second $2-"}), "Scalar/list aliases did not retain their values");
                values.get(PREFIX + "Mixed")[0] = "mutated";
                values.clear();
                require(Bukkit.getServer().getCommandAliases().get(PREFIX + "Mixed")[0].equals(PREFIX + "target fixed $1-"), "Returned alias data mutated the loaded configuration");
            }
        });
        check.accept("server-command-alias-expansion-and-replacement", () -> {
            try (var scope = new Scope()) {
                scope.load(Map.of(PREFIX + "Mixed", PREFIX + "target fixed $1-",
                        PREFIX + "multi", List.of(PREFIX + "target first $1", PREFIX + "target second $2-")));
                require(map().getCommand("P263ALIAS_MIXED") == map().getCommand(PREFIX + "mixed"), "Alias lookup did not normalize case");
                require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), PREFIX + "mixed alpha beta"), "Alias dispatch failed");
                require(scope.calls.equals(List.of("target:fixed,alpha,beta")), "Scalar alias argument expansion changed: " + scope.calls);
                scope.calls.clear();
                require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), PREFIX + "multi one two three"), "Multi-command alias dispatch failed");
                require(scope.calls.equals(List.of("target:first,one", "target:second,two,three")), "Multi-command alias order or indexed arguments changed: " + scope.calls);
                scope.load(Map.of(PREFIX + "next", PREFIX + "target new $1-"));
                require(map().getCommand(PREFIX + "mixed") == null && map().getCommand(PREFIX + "multi") == null, "Removed aliases remained registered");
                scope.calls.clear();
                require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), PREFIX + "next value")
                        && scope.calls.equals(List.of("target:new,value")), "Replacement alias did not dispatch");
                require(map().getCommand(PREFIX + "target") == scope.target && map().getCommand("version") != null, "Alias reload removed unrelated commands");
            }
        });
        check.accept("server-command-alias-failed-reload-contract", () -> {
            try (var scope = new Scope()) {
                scope.load(Map.of(PREFIX + "old", PREFIX + "target $1-"));
                scope.invalid();
                Files.delete(scope.file);
                require(!Bukkit.getServer().reloadCommandAliases(), "Missing commands.yml was reported as successfully loaded");
                require(!Files.exists(scope.file) && map().getCommand(PREFIX + "target") == scope.target, "Failed reload rewrote the file or removed an unrelated command");
            }
        });
    }

    static void verifyNative(BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("server-command-alias-native-dispatch-ownership-and-failure", () -> {
            try (var scope = new Scope()) {
                scope.load(Map.of(PREFIX + "collision", PREFIX + "target replaced $1-", PREFIX + "gone", PREFIX + "target old $1-"));
                executeNative(PREFIX + "collision one");
                require(scope.calls.equals(List.of("target:replaced,one")), "Native dispatcher did not use the configured alias");
                scope.load(Map.of(PREFIX + "next", PREFIX + "target next $1-"));
                require(map().getCommand(PREFIX + "collision") == scope.collision, "Removing an alias did not restore its displaced command");
                require(nativeChild(PREFIX + "gone") == null && nativeChild(PREFIX + "next") != null
                        && nativeChild("port263audit") != null && nativeChild("minecraft:say") != null, "Native command tree retained stale aliases or lost existing commands");
                scope.calls.clear();
                executeNative(PREFIX + "collision original");
                executeNative(PREFIX + "next value");
                require(scope.calls.equals(List.of("collision:original", "target:next,value")), "Native replacement/restored command dispatch failed: " + scope.calls);
                Command before = map().getCommand(PREFIX + "next");
                Object node = nativeChild(PREFIX + "next");
                scope.invalid();
                require(map().getCommand(PREFIX + "next") == before && nativeChild(PREFIX + "next") == node
                        && Bukkit.getServer().getCommandAliases().get(PREFIX + "next")[0].equals(PREFIX + "target next $1-"), "Invalid YAML changed the active aliases or native tree");
                Files.delete(scope.file);
                require(!Bukkit.getServer().reloadCommandAliases() && map().getCommand(PREFIX + "next") == before,
                        "Missing file removed a working alias");
            }
        });
    }

    private static SimpleCommandMap map() { return (SimpleCommandMap) Bukkit.getServer().getCommandMap(); }

    private static Object nativeDispatcher() throws Exception {
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object commands = server.getClass().getMethod("getCommands").invoke(server);
        return commands.getClass().getMethod("getDispatcher").invoke(commands);
    }
    private static Object nativeChild(String label) throws Exception {
        Object dispatcher = nativeDispatcher();
        Object root = dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);
        return root.getClass().getMethod("getChild", String.class).invoke(root, label);
    }
    private static void executeNative(String command) throws Exception {
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object source = server.getClass().getMethod("createCommandSourceStack").invoke(server);
        nativeDispatcher().getClass().getMethod("execute", String.class, Object.class).invoke(nativeDispatcher(), command, source);
    }

    private static final class Scope implements AutoCloseable {
        final Path file = Path.of("commands.yml");
        final byte[] original;
        final List<String> calls = new ArrayList<>();
        final Command target, collision;
        Scope() throws Exception {
            require(Boolean.getBoolean("cardboard.disposableAliasProbe") || Boolean.getBoolean("cardboard.paperRayReference"), "Alias probes require an explicitly disposable test directory");
            this.original = Files.exists(this.file) ? Files.readAllBytes(this.file) : null;
            require(map().getKnownCommands().keySet().stream().noneMatch(key -> key.startsWith(PREFIX) || key.startsWith("p263:" + PREFIX)), "Previous alias fixture commands survived cleanup");
            this.target = command("target"); this.collision = command("collision");
            map().register("p263", this.target); map().register("p263", this.collision);
        }
        Command command(String name) {
            return new Command(PREFIX + name) {
                @Override public boolean execute(CommandSender sender, String label, String[] args) {
                    calls.add(name + ":" + String.join(",", args)); return true;
                }
            };
        }
        void load(Map<String, ?> values) throws Exception {
            var config = new YamlConfiguration();
            values.forEach((key, value) -> config.set("aliases." + key, value));
            config.save(this.file.toFile());
            require(Bukkit.getServer().reloadCommandAliases(), "Valid aliases did not reload");
        }
        void invalid() throws Exception {
            String broken = "aliases: [unterminated\n";
            Files.writeString(this.file, broken);
            var logger = Bukkit.getLogger();
            Filter previous = logger.getFilter();
            int[] observed = {0};
            logger.setFilter(record -> {
                if (record.getMessage().equals("Cannot load commands.yml") && record.getThrown() instanceof InvalidConfigurationException) {
                    observed[0]++; return false;
                }
                return previous == null || previous.isLoggable(record);
            });
            try { require(!Bukkit.getServer().reloadCommandAliases(), "Invalid YAML was reported as successfully loaded"); }
            finally { logger.setFilter(previous); }
            require(observed[0] == 1 && Files.readString(this.file).equals(broken), "Invalid YAML was overwritten or its exact expected error was not reported");
        }
        @Override public void close() throws Exception {
            try {
                if (this.original == null) Files.deleteIfExists(this.file); else Files.write(this.file, this.original);
                Bukkit.getServer().reloadCommandAliases();
            } finally {
                map().getKnownCommands().keySet().removeIf(key -> key.startsWith(PREFIX) || key.startsWith("p263:" + PREFIX));
                this.target.unregister(map()); this.collision.unregister(map());
            }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
