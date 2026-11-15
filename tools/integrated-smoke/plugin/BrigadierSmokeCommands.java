package org.cardboardpowered.smoke.plugin;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.command.brigadier.argument.SignedMessageResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** A real Paper lifecycle consumer, rebuilt after native resource reload in each disposable world. */
final class BrigadierSmokeCommands {
    static final String LABEL = "p263brigadier";
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private volatile UUID owner;
    private volatile int round = -1;
    private volatile int generation, factories;

    void install(JavaPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> register(event.registrar()));
    }

    private void register(Commands commands) {
        this.generation++;
        var root = Commands.literal(LABEL).requires(source -> source.getExecutor() != null);
        root.then(Commands.literal("uuid").then(Commands.argument("value", ArgumentTypes.uuid()).executes(context -> {
            verifyContext(context); record("uuid", context.getArgument("value", UUID.class).toString(), this.round); return 1;
        })));
        var converted = new CustomArgumentType.Converted<String, UUID>() {
            @Override public ArgumentType<UUID> getNativeType() { return ArgumentTypes.uuid(); }
            @Override public String convert(UUID value) { return "uuid:" + value; }
        };
        root.then(Commands.literal("converted").then(Commands.argument("value", converted)
                .suggests((context, builder) -> builder.suggest(context.getSource().getPlayerOrThrow().getUniqueId().toString()).buildFuture())
                .executes(context -> {
                    verifyContext(context); record("converted", context.getArgument("value", String.class), this.round); return 1;
                })));
        root.then(Commands.literal("selector").then(Commands.argument("value", ArgumentTypes.player()).executes(context -> {
            verifyContext(context);
            var selected = context.getArgument("value", PlayerSelectorArgumentResolver.class).resolve(context.getSource());
            require(selected.size() == 1 && selected.getFirst().getUniqueId().equals(this.owner), "Player selector did not resolve the sender");
            record("selector", selected.getFirst().getUniqueId().toString(), this.round); return 1;
        })));
        root.then(Commands.literal("world").then(Commands.argument("value", ArgumentTypes.world()).executes(context -> {
            verifyContext(context);
            World world = context.getArgument("value", World.class);
            require(world == context.getSource().getPlayerOrThrow().getWorld(), "World argument did not resolve the active world");
            record("world", world.getKey().toString(), this.round); return 1;
        })));
        root.then(Commands.literal("component").then(Commands.argument("value", ArgumentTypes.component()).executes(context -> {
            verifyContext(context);
            require(context.getArgument("value", Component.class).equals(Component.text("client brigadier", NamedTextColor.BLUE)), "Component argument lost text/style");
            record("component", "ok", this.round); return 1;
        })));
        root.then(Commands.literal("message").then(Commands.argument("value", ArgumentTypes.signedMessage()).executes(context -> {
            verifyContext(context);
            int current = this.round;
            var resolver = context.getArgument("value", SignedMessageResolver.class);
            require(resolver.content().equals("client brigadier"), "Message argument lost its raw content");
            resolver.resolveSignedMessage("value", context).whenComplete((message, failure) -> {
                if (failure != null) record("failure", failure.toString(), current);
                else if (!message.message().equals("client brigadier") || message.signature() != null) record("failure", "Offline client message conversion failed", current);
                else record("message", "ok", current);
            });
            return 1;
        })));
        root.then(Commands.literal("reply").executes(context -> {
            verifyContext(context);
            var source = context.getSource();
            source.sendReply(Component.text("p263-reply-" + this.round));
            source.sendSuccess(Component.text("p263-success-" + this.round), false);
            source.sendFailure(Component.text("p263-failure-" + this.round));
            record("reply", "ok", this.round); return 1;
        }));
        root.then(Commands.literal("context").executes(context -> {
            verifyContext(context);
            var source = context.getSource();
            var player = source.getPlayerOrThrow();
            var pig = player.getWorld().spawn(player.getLocation().add(2, 2, 2), org.bukkit.entity.Pig.class);
            try {
                var moved = source.withExecutor(pig).withLocation(pig.getLocation());
                require(moved.getSender() == player && moved.getExecutor() == pig && moved.getEntityOrThrow() == pig, "Changing executor replaced the initiating player");
                moved.sendReply(Component.text("p263-delegated-" + this.round));
                record("context", "ok", this.round);
            } finally { pig.remove(); }
            return 1;
        }));
        var factoryRoot = Commands.literal("factories");
        this.factories = 0;
        for (var method : ArgumentTypes.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers()) || method.getReturnType() != ArgumentType.class) continue;
            Object[] parameters = Arrays.stream(method.getParameterTypes()).map(type -> {
                if (type == boolean.class) return (Object) true;
                if (type == int.class) return (Object) 3;
                if (type == RegistryKey.class) return (Object) RegistryKey.ITEM;
                throw new AssertionError("Unknown argument factory: " + method);
            }).toArray();
            try {
                ArgumentType<?> argument = (ArgumentType<?>) method.invoke(null, parameters);
                factoryRoot.then(Commands.literal(method.getName().toLowerCase(Locale.ROOT) + "_" + method.getParameterCount())
                        .then(Commands.argument("value", argument).executes(context -> 1)));
                this.factories++;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot register native argument " + method, failure); }
        }
        require(this.factories >= 37, "Argument factory table is incomplete");
        root.then(factoryRoot);
        commands.register(root.build(), "Disposable native Brigadier client fixture");
    }

    int begin(UUID owner, int round) {
        this.values.clear(); this.owner = owner; this.round = round;
        return this.generation;
    }
    Map<String, String> snapshot() {
        var snapshot = new HashMap<>(this.values);
        snapshot.put("generation", Integer.toString(this.generation));
        snapshot.put("factories", Integer.toString(this.factories));
        return snapshot;
    }
    void end() { this.round = -1; this.owner = null; this.values.clear(); }
    private void record(String key, String value, int round) { if (round == this.round) this.values.put(key, round + ":" + value); }
    private void verifyContext(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrThrow();
        require(Bukkit.isPrimaryThread() && player.getUniqueId().equals(this.owner), "Command did not execute on its owner's server thread");
        require(source.getSender() == player && source.getEntityOrThrow() == player && source.getExecutor() == player, "Command source identity changed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
