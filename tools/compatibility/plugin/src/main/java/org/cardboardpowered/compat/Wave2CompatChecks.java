package org.cardboardpowered.compat;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.Recipe;

/**
 * Wave 2 compatibility checks: deeper public Bukkit/Paper API semantics that
 * can be validated deterministically without a player or destructive world
 * changes. Chunk lifecycle and real-plugin functional scenarios are handled
 * separately because they need multi-tick/destructive orchestration.
 */
final class Wave2CompatChecks {

    private Wave2CompatChecks() {
    }

    static void run(
        JavaPlugin plugin,
        CommandSender sender,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail,
        BiConsumer<String, String> skip
    ) {
        check(
            "command.plugin-command",
            () -> Bukkit.getPluginCommand("cardboardcompat") != null,
            "Bukkit resolves the probe PluginCommand",
            pass,
            fail
        );

        check(
            "command.dispatch-status",
            () -> Bukkit.dispatchCommand(sender, "cardboardcompat status"),
            "Bukkit.dispatchCommand executed the probe status command",
            pass,
            fail
        );

        check(
            "command.dispatch-unknown",
            () -> !Bukkit.dispatchCommand(sender, "cardboardcompat__missing_command"),
            "unknown command returned false",
            pass,
            fail
        );

        runPermissionChecks(plugin, sender, pass, fail);
        runServiceChecks(plugin, pass, fail);
        runSerializationChecks(plugin, pass, fail);
        runPluginMessagingChecks(plugin, pass, fail);
        runScoreboardChecks(pass, fail);
        runBossBarCheck(pass, fail);
        runRecipeCheck(plugin, pass, fail);

        check(
            "adventure.component-sender",
            () -> {
                sender.sendMessage(Component.text("[CardboardCompatProbe] Adventure component probe"));
                return true;
            },
            "CommandSender accepted an Adventure Component",
            pass,
            fail
        );
    }

    private static void runPermissionChecks(
        JavaPlugin plugin,
        CommandSender sender,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        String dynamicNode = "cardboard.compat.wave2.dynamic." + token();
        Permission permission = new Permission(dynamicNode);
        try {
            Bukkit.getPluginManager().addPermission(permission);
            if (Bukkit.getPluginManager().getPermission(dynamicNode) == permission) {
                pass.accept("permissions.dynamic-register", dynamicNode);
            } else {
                fail.accept("permissions.dynamic-register", "registered permission was not returned by PluginManager");
            }
        } catch (Throwable throwable) {
            fail.accept("permissions.dynamic-register", describe(throwable));
        } finally {
            try {
                Bukkit.getPluginManager().removePermission(dynamicNode);
            } catch (Throwable ignored) {
            }
        }

        String attachmentNode = "cardboard.compat.wave2.attachment." + token();
        PermissionAttachment attachment = null;
        try {
            attachment = sender.addAttachment(plugin);
            attachment.setPermission(attachmentNode, true);
            sender.recalculatePermissions();
            if (sender.hasPermission(attachmentNode)) {
                pass.accept("permissions.attachment", "temporary PermissionAttachment became effective");
            } else {
                fail.accept("permissions.attachment", "temporary PermissionAttachment was not effective");
            }
        } catch (Throwable throwable) {
            fail.accept("permissions.attachment", describe(throwable));
        } finally {
            if (attachment != null) {
                try {
                    sender.removeAttachment(attachment);
                    sender.recalculatePermissions();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void runServiceChecks(
        JavaPlugin plugin,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        ProbeService service = () -> "cardboard-wave2";
        try {
            Bukkit.getServicesManager().register(
                ProbeService.class,
                service,
                plugin,
                ServicePriority.Normal
            );

            ProbeService loaded = Bukkit.getServicesManager().load(ProbeService.class);
            if (loaded == service && "cardboard-wave2".equals(loaded.value())) {
                pass.accept("services.register-load", "service provider round-trip succeeded");
            } else {
                fail.accept("services.register-load", "ServicesManager did not return the registered provider");
            }
        } catch (Throwable throwable) {
            fail.accept("services.register-load", describe(throwable));
        } finally {
            try {
                Bukkit.getServicesManager().unregister(ProbeService.class, service);
            } catch (Throwable throwable) {
                fail.accept("services.unregister", describe(throwable));
                return;
            }
        }

        try {
            if (Bukkit.getServicesManager().load(ProbeService.class) == null) {
                pass.accept("services.unregister", "provider disappeared after unregister");
            } else {
                fail.accept("services.unregister", "provider remained registered after unregister");
            }
        } catch (Throwable throwable) {
            fail.accept("services.unregister", describe(throwable));
        }
    }

    private static void runSerializationChecks(
        JavaPlugin plugin,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        check(
            "namespaced-key.roundtrip",
            () -> {
                NamespacedKey key = new NamespacedKey(plugin, "wave2_key");
                NamespacedKey parsed = NamespacedKey.fromString(key.toString());
                return key.equals(parsed)
                    && plugin.getName().toLowerCase().replace(' ', '_').equals(key.getNamespace())
                    && "wave2_key".equals(key.getKey());
            },
            "NamespacedKey string round-trip succeeded",
            pass,
            fail
        );

        check(
            "itemstack.serialize",
            () -> {
                ItemStack original = new ItemStack(Material.DIAMOND, 2);
                Map<String, Object> serialized = original.serialize();
                ItemStack restored = ItemStack.deserialize(serialized);
                return restored.getType() == Material.DIAMOND && restored.getAmount() == 2;
            },
            "ItemStack serialize/deserialize round-trip succeeded",
            pass,
            fail
        );

        check(
            "config.yaml-roundtrip",
            () -> {
                YamlConfiguration source = new YamlConfiguration();
                source.set("wave2.nested.number", 42);
                source.set("wave2.nested.text", "cardboard");
                String yaml = source.saveToString();

                YamlConfiguration restored = new YamlConfiguration();
                restored.loadFromString(yaml);
                return restored.getInt("wave2.nested.number") == 42
                    && "cardboard".equals(restored.getString("wave2.nested.text"));
            },
            "YamlConfiguration save/load string round-trip succeeded",
            pass,
            fail
        );
    }

    private static void runPluginMessagingChecks(
        JavaPlugin plugin,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        String channel = "cardboard:wave2";
        try {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
            if (Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, channel)) {
                pass.accept("plugin-messaging.outgoing-register", channel);
            } else {
                fail.accept("plugin-messaging.outgoing-register", "outgoing channel was not reported as registered");
            }
        } catch (Throwable throwable) {
            fail.accept("plugin-messaging.outgoing-register", describe(throwable));
        } finally {
            try {
                Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
            } catch (Throwable ignored) {
            }
        }

        PluginMessageListener listener = (ignoredChannel, ignoredPlayer, ignoredBytes) -> { };
        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, channel, listener);
            if (Bukkit.getMessenger().isIncomingChannelRegistered(plugin, channel)) {
                pass.accept("plugin-messaging.incoming-register", channel);
            } else {
                fail.accept("plugin-messaging.incoming-register", "incoming channel was not reported as registered");
            }
        } catch (Throwable throwable) {
            fail.accept("plugin-messaging.incoming-register", describe(throwable));
        } finally {
            try {
                Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, channel, listener);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void runScoreboardChecks(
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        Scoreboard board;
        try {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            if (board != null) {
                pass.accept("scoreboard.create", "created isolated scoreboard");
            } else {
                fail.accept("scoreboard.create", "ScoreboardManager returned null");
                return;
            }
        } catch (Throwable throwable) {
            fail.accept("scoreboard.create", describe(throwable));
            return;
        }

        try {
            Objective objective = board.registerNewObjective("cbwave2", "dummy", "Cardboard Wave 2");
            objective.getScore("probe").setScore(7);
            Objective lookedUpObjective = board.getObjective("cbwave2");
            if (lookedUpObjective != null
                && "cbwave2".equals(lookedUpObjective.getName())
                && lookedUpObjective.getScore("probe").getScore() == 7) {
                pass.accept("scoreboard.objective", "objective registration and score round-trip succeeded");
            } else {
                fail.accept("scoreboard.objective", "objective state did not round-trip");
            }
            objective.unregister();
        } catch (Throwable throwable) {
            fail.accept("scoreboard.objective", describe(throwable));
        }

        try {
            Team team = board.registerNewTeam("cbwave2team");
            team.addEntry("probe");
            Team lookedUpTeam = board.getTeam("cbwave2team");
            if (lookedUpTeam != null
                && "cbwave2team".equals(lookedUpTeam.getName())
                && lookedUpTeam.hasEntry("probe")) {
                pass.accept("scoreboard.team", "team registration and entry round-trip succeeded");
            } else {
                fail.accept("scoreboard.team", "team state did not round-trip");
            }
            team.unregister();
        } catch (Throwable throwable) {
            fail.accept("scoreboard.team", describe(throwable));
        }
    }

    private static void runBossBarCheck(
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        BossBar bar = null;
        try {
            bar = Bukkit.createBossBar("Cardboard Wave 2", BarColor.BLUE, BarStyle.SOLID);
            bar.setProgress(0.5D);
            bar.setVisible(true);
            if (Math.abs(bar.getProgress() - 0.5D) < 0.000001D && bar.isVisible()) {
                pass.accept("bossbar.basic", "boss bar state round-trip succeeded");
            } else {
                fail.accept("bossbar.basic", "boss bar state did not round-trip");
            }
        } catch (Throwable throwable) {
            fail.accept("bossbar.basic", describe(throwable));
        } finally {
            if (bar != null) {
                try {
                    bar.removeAll();
                    bar.setVisible(false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void runRecipeCheck(
        JavaPlugin plugin,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        NamespacedKey key = new NamespacedKey(plugin, "wave2_recipe");
        try {
            Bukkit.removeRecipe(key);
            ShapelessRecipe recipe = new ShapelessRecipe(key, new ItemStack(Material.COBBLESTONE));
            recipe.addIngredient(Material.STONE);

            boolean added = Bukkit.addRecipe(recipe);
            Recipe loaded = Bukkit.getRecipe(key);
            boolean removed = Bukkit.removeRecipe(key);

            if (added && loaded != null && removed && Bukkit.getRecipe(key) == null) {
                pass.accept("recipe.add-lookup-remove", "recipe add/lookup/remove lifecycle succeeded");
            } else {
                fail.accept(
                    "recipe.add-lookup-remove",
                    "added=" + added + " loaded=" + (loaded != null) + " removed=" + removed
                );
            }
        } catch (Throwable throwable) {
            fail.accept("recipe.add-lookup-remove", describe(throwable));
            try {
                Bukkit.removeRecipe(key);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void check(
        String id,
        CheckedBoolean check,
        String successDetail,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail
    ) {
        try {
            if (check.getAsBoolean()) {
                pass.accept(id, successDetail);
            } else {
                fail.accept(id, "check returned false");
            }
        } catch (Throwable throwable) {
            fail.accept(id, describe(throwable));
        }
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean getAsBoolean() throws Exception;
    }

    private interface ProbeService {
        String value();
    }
}
