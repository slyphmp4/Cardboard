package org.cardboardpowered.test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.command.brigadier.argument.SignedMessageResolver;
import io.papermc.paper.command.brigadier.argument.predicate.BlockInWorldPredicate;
import io.papermc.paper.registry.RegistryKey;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.DisplaySlot;

/** Shared public contracts, also run unmodified on the pinned official Paper control. */
public final class BrigadierArgumentProbes {
    public static void verify(World world, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("brigadier-all-argument-factories", () -> {
            var failures = new ArrayList<String>();
            int count = 0;
            for (var method : ArgumentTypes.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != ArgumentType.class) continue;
                count++;
                Object[] arguments = Arrays.stream(method.getParameterTypes()).map(type -> {
                    if (type == boolean.class) return (Object) true;
                    if (type == int.class) return (Object) 3;
                    if (type == RegistryKey.class) return (Object) RegistryKey.ITEM;
                    throw new AssertionError("Unknown argument factory parameter: " + method);
                }).toArray();
                try {
                    var argument = (ArgumentType<?>) method.invoke(null, arguments);
                    require(argument != null && argument.getExamples() != null, "Factory returned no usable argument");
                } catch (Throwable failure) {
                    Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation ? invocation.getCause() : failure;
                    failures.add(method.getName() + ": " + cause);
                }
            }
            require(count >= 37 && failures.isEmpty(), "Factories=" + count + ", failures=" + failures);
        });
        check.accept("brigadier-values-ranges-and-validation", () -> {
            require(parse(ArgumentTypes.namespacedKey(), "stone").equals(NamespacedKey.minecraft("stone")), "Default namespace lost");
            require(parse(ArgumentTypes.key(), "example:entry").asString().equals("example:entry"), "Adventure key conversion failed");
            UUID id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
            require(parse(ArgumentTypes.uuid(), id.toString()).equals(id), "UUID conversion failed");
            require(parse(ArgumentTypes.namedColor(), "red") == NamedTextColor.RED, "Named color conversion failed");
            require(parse(ArgumentTypes.hexColor(), "123abc").value() == 0x123abc, "Hex color conversion failed");
            require(parse(ArgumentTypes.axes(), "xz").equals(java.util.Set.of(Axis.X, Axis.Z)), "Axis set conversion failed");
            require(parse(ArgumentTypes.gameMode(), "creative") == GameMode.CREATIVE, "Game mode conversion failed");
            var heightMap = ArgumentTypes.heightMap();
            String heightName = heightMap.getExamples().iterator().next();
            require(parse(heightMap, heightName) == HeightMap.valueOf(heightName.toUpperCase(java.util.Locale.ROOT)), "Height map conversion failed");
            require(parse(ArgumentTypes.scoreboardDisplaySlot(), "sidebar") == DisplaySlot.SIDEBAR, "Display slot conversion failed");
            require(parse(ArgumentTypes.entityAnchor(), "eyes") == io.papermc.paper.entity.LookAnchor.EYES, "Anchor conversion failed");
            require(parse(ArgumentTypes.templateMirror(), "front_back") == Mirror.FRONT_BACK, "Mirror conversion failed");
            require(parse(ArgumentTypes.templateRotation(), "clockwise_90") == StructureRotation.CLOCKWISE_90, "Rotation conversion failed");
            require(parse(ArgumentTypes.objectiveCriteria(), "dummy").getName().equals("dummy"), "Criteria conversion failed");
            require(parse(ArgumentTypes.time(), "1.5s") == 30 && parse(ArgumentTypes.time(), "1d") == 24000, "Time units changed");
            require(parse(ArgumentTypes.integerRange(), "-2..4").range().equals(com.google.common.collect.Range.closed(-2, 4)), "Closed integer range changed");
            require(parse(ArgumentTypes.integerRange(), "..3").range().equals(com.google.common.collect.Range.atMost(3)), "Open integer range changed");
            require(parse(ArgumentTypes.doubleRange(), "1.5..").range().equals(com.google.common.collect.Range.atLeast(1.5)), "Open double range changed");
            syntax(() -> parse(ArgumentTypes.axes(), "xx"));
            syntax(() -> parse(ArgumentTypes.time(31), "1.5s"));
            syntax(() -> parse(ArgumentTypes.integerRange(), "5..2"));
            syntax(() -> parse(ArgumentTypes.uuid(), "invalid"));
            syntax(() -> parse(ArgumentTypes.namedColor(), "bold"));
        });
        check.accept("brigadier-position-rotation-and-source-resolution", () -> {
            var source = source(world).withLocation(new Location(world, 17.25, 80.5, -9.75, 0, 0));
            var block = parse(ArgumentTypes.blockPosition(), "~1 ~-2 ~3").resolve(source);
            require(block.blockX() == 18 && block.blockY() == 78 && block.blockZ() == -7, "Relative block position changed: " + block);
            var local = parse(ArgumentTypes.finePosition(), "^1 ^2 ^3").resolve(source);
            near(local.x(), 18.25); near(local.y(), 82.5); near(local.z(), -6.75);
            var centered = parse(ArgumentTypes.finePosition(true), "1 2 3").resolve(source);
            near(centered.x(), 1.5); near(centered.y(), 2); near(centered.z(), 3.5);
            var column = parse(ArgumentTypes.columnBlockPosition(), "~2 ~-3").resolve(source);
            require(column.blockX() == 19 && column.blockZ() == -13, "Column block position changed");
            var fineColumn = parse(ArgumentTypes.columnFinePosition(true), "1 2").resolve(source);
            near(fineColumn.x(), 1.5); near(fineColumn.z(), 2.5);
            var rotation = parse(ArgumentTypes.rotation(), "~15 ~-5").resolve(source);
            near(rotation.yaw(), 15); near(rotation.pitch(), -5);
            near(parse(ArgumentTypes.angle(), "~30").resolve(source), 30);
            syntax(() -> parse(ArgumentTypes.finePosition(), "^1 ~2 ^3"));
        });
        check.accept("brigadier-entity-player-and-profile-resolvers", () -> {
            var pig = world.spawn(world.getSpawnLocation().add(2, 2, 2), org.bukkit.entity.Pig.class);
            try {
                var source = source(world).withExecutor(pig);
                require(parse(ArgumentTypes.entity(), "@s").resolve(source).equals(List.of(pig)), "Single entity resolver changed executor");
                require(parse(ArgumentTypes.entities(), "@s").resolve(source).equals(List.of(pig)), "Entity list resolver changed executor");
                syntax(() -> parse(ArgumentTypes.player(), "@s").resolve(source));
                syntax(() -> parse(ArgumentTypes.playerProfiles(), "@e"));
                require(parse(ArgumentTypes.players(), "@a").resolve(source).isEmpty(), "Disposable dedicated server unexpectedly has players");
            } finally { pig.remove(); }
        });
        check.accept("brigadier-block-item-components-and-predicates", () -> {
            var state = parse(ArgumentTypes.blockState(), "minecraft:oak_log[axis=x]");
            require(!state.isPlaced() && ((org.bukkit.block.data.Orientable) state.getBlockData()).getAxis() == Axis.X, "Unplaced block state lost properties");
            var chest = parse(ArgumentTypes.blockState(), "minecraft:chest{CustomName:{text:'Argument chest'}}");
            require(chest instanceof org.bukkit.block.Chest c && Component.text("Argument chest").equals(c.customName()), "Block entity NBT was lost");
            var item = parse(ArgumentTypes.itemStack(), "minecraft:stone[minecraft:custom_name={text:'argument item'}]");
            require(item.getType() == Material.STONE && item.getAmount() == 1
                    && Component.text("argument item").equals(item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_NAME)), "Item components were lost");
            var itemPredicate = parse(ArgumentTypes.itemPredicate(), "#minecraft:planks");
            require(itemPredicate.test(new ItemStack(Material.OAK_PLANKS)) && !itemPredicate.test(new ItemStack(Material.STONE)), "Item predicate lost native tag semantics");
            var block = world.getSpawnLocation().add(3, 3, 3).getBlock();
            var before = block.getBlockData();
            try {
                var predicate = parse(ArgumentTypes.blockInWorldPredicate(), "#minecraft:logs[axis=x]");
                block.setBlockData(state.getBlockData(), false);
                require(predicate.testBlock(block, false) == BlockInWorldPredicate.Result.TRUE, "Block predicate rejected a matching state");
                block.setType(Material.STONE, false);
                require(predicate.testBlock(block, false) == BlockInWorldPredicate.Result.FALSE, "Block predicate accepted another block");
                int x = 1_100_001, z = 1_100_001;
                require(!world.isChunkLoaded(x >> 4, z >> 4), "Remote fixture chunk already loaded");
                require(predicate.testBlock(world.getBlockAt(x, world.getMinHeight() + 8, z), false) == BlockInWorldPredicate.Result.UNLOADED_CHUNK
                        && !world.isChunkLoaded(x >> 4, z >> 4), "Predicate loaded a chunk despite loadChunk=false");
            } finally { block.setBlockData(before, false); }
        });
        check.accept("brigadier-registry-world-and-component-conversions", () -> {
            require(parse(ArgumentTypes.world(), world.getKey().toString()) == world, "World did not resolve through the active server");
            syntax(() -> parse(ArgumentTypes.world(), "minecraft:cardboard_argument_missing"));
            require(parse(ArgumentTypes.resource(RegistryKey.BIOME), "minecraft:plains") == org.bukkit.block.Biome.PLAINS, "Registry value conversion failed");
            var typed = parse(ArgumentTypes.resourceKey(RegistryKey.ITEM), "example:unregistered");
            require(typed.key().asString().equals("example:unregistered") && typed.registryKey().equals(RegistryKey.ITEM), "Typed key identity changed");
            syntax(() -> parse(ArgumentTypes.resource(RegistryKey.ITEM), "minecraft:cardboard_argument_missing"));
            var mod = new NamespacedKey("cardboard_port263_fixture", "probe");
            if (Registry.ITEM.get(mod) != null) require(parse(ArgumentTypes.resource(RegistryKey.ITEM), mod.toString()) == Registry.ITEM.get(mod), "Mod registry entry became vanilla or null");
            require(parse(ArgumentTypes.component(), "{text:'argument component',color:'red'}").equals(Component.text("argument component", NamedTextColor.RED)), "Component conversion changed content/style");
            var style = parse(ArgumentTypes.style(), "{color:'#123abc',bold:true}");
            require(style.color() != null && style.color().value() == 0x123abc && style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE, "Style conversion lost values");
        });
        check.accept("brigadier-command-source-context-and-identity", () -> {
            var original = source(world);
            var location = new Location(world, 5.25, 90.5, -7.75, 37, -12);
            var moved = original.withLocation(location);
            require(moved != original && moved.getLocation().equals(location), "Source location was not replaced defensively");
            location.setX(999); var copy = moved.getLocation(); copy.setZ(999);
            near(moved.getLocation().getX(), 5.25); near(moved.getLocation().getZ(), -7.75);
            require(moved.getSender() == Bukkit.getConsoleSender() && moved.getExecutor() == null, "Source sender/executor changed");
            syntax(moved::getPlayerOrThrow); syntax(moved::getEntityOrThrow);
            var pig = world.spawn(world.getSpawnLocation().add(1, 2, 1), org.bukkit.entity.Pig.class);
            try {
                var executed = moved.withExecutor(pig);
                require(executed.getEntityOrThrow() == pig && executed.getExecutor() == pig && executed.getSender() == moved.getSender(), "Executor identity or initiator changed");
                syntax(executed::getPlayerOrThrow);
            } finally { pig.remove(); }
        });
    }

    private static java.util.concurrent.CompletableFuture<net.kyori.adventure.chat.SignedMessage> createMessageProbe(World world) throws Exception {
        var source = source(world);
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var seen = new AtomicReference<String>();
        var converted = new CustomArgumentType.Converted<String, UUID>() {
            public ArgumentType<UUID> getNativeType() { return ArgumentTypes.uuid(); }
            public String convert(UUID value) { return "converted:" + value; }
        };
        dispatcher.register(Commands.literal("probe").then(Commands.argument("id", converted).executes(context -> {
            seen.set(context.getArgument("id", String.class)); return 7;
        })));
        String id = "00112233-4455-6677-8899-aabbccddeeff";
        require(dispatcher.execute("probe " + id, source) == 7 && seen.get().equals("converted:" + id), "Custom argument returned native rather than API result");
        var message = new AtomicReference<java.util.concurrent.CompletableFuture<net.kyori.adventure.chat.SignedMessage>>();
        dispatcher.register(Commands.literal("message").then(Commands.argument("text", ArgumentTypes.signedMessage()).executes(context -> {
            var resolver = context.getArgument("text", SignedMessageResolver.class);
            require(resolver.content().equals("argument message"), "Message content changed");
            var future = resolver.resolveSignedMessage("text", context);
            message.set(future); return 1;
        })));
        require(dispatcher.execute("message argument message", source) == 1, "Message command did not execute");
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("probe 0011", source)).get(5, TimeUnit.SECONDS);
        require(suggestions != null, "Suggestion future did not complete");
        return java.util.Objects.requireNonNull(message.get());
    }

    public static void verifyMessages(World world, org.bukkit.plugin.java.JavaPlugin plugin,
            BiConsumer<String, WorldRayTraceProbes.Checked> check, Runnable done) {
        final String name = "brigadier-custom-conversion-and-message-resolution";
        final java.util.concurrent.CompletableFuture<net.kyori.adventure.chat.SignedMessage> future;
        try { future = createMessageProbe(world); }
        catch (Throwable failure) {
            check.accept(name, () -> { throw new AssertionError("Message probe setup failed", failure); });
            done.run(); return;
        }
        class AwaitMessage implements Runnable {
            int ticks;
            @Override public void run() {
                if (!future.isDone() && ++this.ticks < 100) {
                    Bukkit.getScheduler().runTaskLater(plugin, this, 1); return;
                }
                check.accept(name, () -> {
                    require(future.isDone(), "Message resolution did not finish within 100 server ticks");
                    var value = future.join();
                    require(value.message().equals("argument message") && value.signature() == null
                            && value.identity().uuid().equals(new UUID(0, 0)), "Unsigned console fallback is not a system message");
                });
                done.run();
            }
        }
        new AwaitMessage().run();
    }

    public static CommandSourceStack source(World world) throws Exception {
        var wrapper = Class.forName("org.bukkit.craftbukkit.command.VanillaCommandWrapper");
        return ((CommandSourceStack) wrapper.getMethod("getListener", org.bukkit.command.CommandSender.class)
                .invoke(null, Bukkit.getConsoleSender())).withLocation(world.getSpawnLocation());
    }

    private static <T> T parse(ArgumentType<T> type, String input) throws CommandSyntaxException {
        var reader = new StringReader(input);
        T value = type.parse(reader);
        require(!reader.canRead(), "Argument left unread input: " + input.substring(reader.getCursor()));
        return value;
    }
    private static void syntax(WorldRayTraceProbes.Checked action) throws Exception {
        try { action.run(); } catch (CommandSyntaxException expected) { return; }
        throw new AssertionError("Invalid argument or executor was accepted");
    }
    private static void near(double actual, double expected) { require(Math.abs(actual - expected) < 0.00001, "Expected " + expected + ", got " + actual); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private BrigadierArgumentProbes() { }
}
