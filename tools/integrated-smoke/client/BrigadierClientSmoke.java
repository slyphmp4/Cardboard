package org.cardboardpowered.smoke;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Decode every public factory's real client type, then execute and complete commands over the connection. */
final class BrigadierClientSmoke {
    private static final String LABEL = "p263brigadier";
    private static State active;

    static void begin(MinecraftServer server, UUID owner) {
        if (active != null) throw new AssertionError("Previous Brigadier client scope survived cleanup");
        try {
            var state = new State(server, owner);
            state.prepareRound(1);
            active = state;
        } catch (Exception failure) { throw new IllegalStateException("Cannot begin Brigadier client fixture", failure); }
    }

    static boolean finish(Minecraft client) {
        State state = active;
        if (state == null) return true;
        try {
            if (System.nanoTime() > state.deadline) throw new AssertionError("Brigadier client timed out, phase=" + state.phase + " replies=" + state.snapshot());
            if (state.pending != null) {
                if (!state.pending.isDone()) return false;
                state.pending.join(); state.pending = null;
            }
            if (client.player == null) return false;
            var root = client.player.connection.getCommands().getRoot().getChild(LABEL);
            if (root == null) return false;
            if (state.phase == 0 || state.phase == 2) {
                if (state.phase == 2) {
                    if (root == state.firstClientNode) return false;
                    require(Integer.parseInt(state.snapshot().get("generation")) > state.initialGeneration, "Native reload did not re-register plugin commands");
                }
                state.factories = assertNativeTypes(root);
                if (state.phase == 0) state.firstClientNode = root;
                String input = LABEL + " converted 0011";
                var dispatcher = client.player.connection.getCommands();
                state.suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, client.player.connection.getSuggestionsProvider()));
                client.player.connection.sendCommand(LABEL + " uuid " + state.owner);
                client.player.connection.sendCommand(LABEL + " converted " + state.owner);
                client.player.connection.sendCommand(LABEL + " selector @s");
                client.player.connection.sendCommand(LABEL + " world " + state.worldKey);
                client.player.connection.sendCommand(LABEL + " component {text:'client brigadier',color:'blue'}");
                client.player.connection.sendCommand(LABEL + " message client brigadier");
                client.player.connection.sendCommand(LABEL + " reply");
                client.player.connection.sendCommand(LABEL + " context");
                state.phase++;
                return false;
            }
            if (state.phase == 1 || state.phase == 3) {
                var replies = state.snapshot();
                require(!replies.containsKey("failure"), "Server callback failed: " + replies);
                int round = state.phase == 1 ? 1 : 2;
                Map<String, String> expected = Map.of("uuid", state.owner.toString(), "converted", "uuid:" + state.owner,
                        "selector", state.owner.toString(), "world", state.worldKey, "component", "ok", "message", "ok", "reply", "ok", "context", "ok");
                for (var entry : expected.entrySet()) if (!(round + ":" + entry.getValue()).equals(replies.get(entry.getKey()))) return false;
                if (!state.suggestions.isDone()) return false;
                var suggestions = state.suggestions.join();
                require(suggestions.getList().stream().anyMatch(value -> value.getText().equals(state.owner.toString())), "Server-backed UUID suggestions were lost");
                for (String kind : new String[] {"reply", "success", "failure", "delegated"}) {
                    String marker = "p263-" + kind + "-" + round;
                    if (state.messages.stream().noneMatch(value -> value.contains(marker))) return false;
                }
                if (!state.nativeSigned.isDone() || !state.nativeOracle.isDone()) return false;
                var signed = state.nativeSigned.join();
                var oracle = state.nativeOracle.join();
                require(signed.timestamp().equals(java.time.Instant.parse("2026-01-02T03:04:05Z")) && signed.salt() == 12345L
                        && signed.identity().uuid().equals(state.owner) && signed.message().equals("native signed fixture"), "Native signed-message identity/body metadata changed");
                require(signed.signature() != null && oracle.signature() != null
                        && java.util.Arrays.equals(signed.signature().bytes(), state.signature)
                        && java.util.Arrays.equals(signed.signature().bytes(), oracle.signature().bytes()), "Native signature bytes were lost");
                var decoration = oracle.unsignedContent() == null ? null : io.papermc.paper.adventure.PaperAdventure.asAdventure(oracle.unsignedContent());
                require(java.util.Objects.equals(decoration, signed.unsignedContent()), "Adventure decoration differs from vanilla resolution: actual=" + signed.unsignedContent() + ", native=" + decoration);
                if (round == 1) {
                    state.pending = CompletableFuture.supplyAsync(() -> state.server.reloadResources(state.server.getPackRepository().getSelectedIds()), state.server)
                            .thenCompose(future -> future).thenRunAsync(() -> state.prepareRound(2), state.server);
                    state.phase = 2;
                } else {
                    state.pending = CompletableFuture.runAsync(state::close, state.server);
                    state.phase = 4;
                }
                return false;
            }
            if (state.phase == 4) {
                active = null;
                System.out.println("BRIGADIER_CLIENT_PASS factories=" + state.factories + " rounds=2");
                return true;
            }
            return false;
        } catch (Exception failure) { throw new IllegalStateException("Brigadier client fixture failed", failure); }
    }

    private static int assertNativeTypes(CommandNode<?> root) throws Exception {
        var factories = root.getChild("factories");
        require(factories != null, "Native argument factory command tree is absent");
        int count = 0;
        for (var method : ArgumentTypes.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers()) || method.getReturnType() != ArgumentType.class) continue;
            String name = method.getName().toLowerCase(java.util.Locale.ROOT) + "_" + method.getParameterCount();
            var group = factories.getChild(name);
            require(group != null && group.getChild("value") instanceof ArgumentCommandNode<?, ?>, "Argument factory is absent from client: " + name);
            var type = ((ArgumentCommandNode<?, ?>) group.getChild("value")).getType();
            String expected = nativeClass(method.getName());
            require(type.getClass().getName().equals(expected), "Client type for " + name + " is " + type.getClass().getName() + ", expected " + expected);
            if (method.getName().equals("time") && method.getParameterCount() == 1) syntax(() -> type.parse(new StringReader("2")));
            if (method.getName().equals("player")) syntax(() -> type.parse(new StringReader("@e[limit=1]")));
            count++;
        }
        require(count >= 37 && factories.getChildren().size() == count, "Client argument factory count differs from API");
        require(((ArgumentCommandNode<?, ?>) root.getChild("converted").getChild("value")).getType().getClass().getSimpleName().equals("UuidArgument"), "Converted UUID was sent as a string");
        require(((ArgumentCommandNode<?, ?>) root.getChild("message").getChild("value")).getType().getClass().getSimpleName().equals("MessageArgument"), "Message signing metadata was lost");
        return count;
    }

    // Independent mapping from the pinned Paper provider/native wire contracts.
    private static String nativeClass(String method) {
        String name = switch (method) {
            case "entity", "entities", "player", "players" -> "EntityArgument";
            case "playerProfiles" -> "GameProfileArgument";
            case "blockPosition" -> "coordinates.BlockPosArgument";
            case "columnBlockPosition" -> "coordinates.ColumnPosArgument";
            case "finePosition" -> "coordinates.Vec3Argument";
            case "columnFinePosition" -> "coordinates.Vec2Argument";
            case "rotation" -> "coordinates.RotationArgument";
            case "angle" -> "AngleArgument";
            case "axes" -> "coordinates.SwizzleArgument";
            case "blockState" -> "blocks.BlockStateArgument";
            case "blockInWorldPredicate" -> "blocks.BlockPredicateArgument";
            case "itemStack" -> "item.ItemArgument";
            case "itemPredicate" -> "item.ItemPredicateArgument";
            case "namedColor" -> "TeamColorArgument";
            case "hexColor" -> "HexColorArgument";
            case "component" -> "ComponentArgument";
            case "style" -> "StyleArgument";
            case "signedMessage" -> "MessageArgument";
            case "scoreboardDisplaySlot" -> "ScoreboardSlotArgument";
            case "namespacedKey", "key" -> "IdentifierArgument";
            case "integerRange" -> "RangeArgument$Ints";
            case "doubleRange" -> "RangeArgument$Floats";
            case "world" -> "DimensionArgument";
            case "gameMode" -> "GameModeArgument";
            case "heightMap" -> "HeightmapTypeArgument";
            case "uuid" -> "UuidArgument";
            case "objectiveCriteria" -> "ObjectiveCriteriaArgument";
            case "entityAnchor" -> "EntityAnchorArgument";
            case "time" -> "TimeArgument";
            case "templateMirror" -> "TemplateMirrorArgument";
            case "templateRotation" -> "TemplateRotationArgument";
            case "resourceKey" -> "ResourceKeyArgument";
            case "resource" -> "ResourceArgument";
            default -> throw new AssertionError("Unknown public argument factory: " + method);
        };
        return "net.minecraft.commands.arguments." + name;
    }

    private static final class State {
        final MinecraftServer server;
        final UUID owner;
        final Plugin plugin;
        final String worldKey;
        final Channel channel;
        final String handler = "cardboard-brigadier-smoke";
        final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
        final long deadline = System.nanoTime() + 180_000_000_000L;
        CompletableFuture<?> pending;
        CompletableFuture<Suggestions> suggestions;
        CompletableFuture<net.kyori.adventure.chat.SignedMessage> nativeSigned;
        CompletableFuture<net.minecraft.network.chat.PlayerChatMessage> nativeOracle;
        final byte[] signature = new byte[256];
        CommandNode<?> firstClientNode;
        int phase, initialGeneration, factories;
        State(MinecraftServer server, UUID owner) throws Exception {
            this.server = server; this.owner = owner;
            this.plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
            require(this.plugin != null && this.plugin.isEnabled(), "Fixture plugin is unavailable");
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            require(player != null, "Owner is not connected");
            this.worldKey = Bukkit.getPlayer(owner).getWorld().getKey().toString();
            this.channel = (Channel) field(field(player.connection, "connection"), "channel");
            this.channel.eventLoop().submit(() -> this.channel.pipeline().addLast(this.handler, new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundSystemChatPacket packet) messages.add(packet.content().getString());
                    super.write(context, message, promise);
                }
            })).get(10, TimeUnit.SECONDS);
        }
        void prepareRound(int round) {
            try {
                this.messages.clear();
                int generation = (Integer) this.plugin.getClass().getMethod("beginBrigadier", UUID.class, int.class).invoke(this.plugin, this.owner, round);
                require(generation > 0, "Command lifecycle handler never registered");
                if (round == 1) this.initialGeneration = generation;
                this.nativeSigned = signedContextProbe();
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        @SuppressWarnings("unchecked")
        private CompletableFuture<net.kyori.adventure.chat.SignedMessage> signedContextProbe() {
            for (int index = 0; index < this.signature.length; index++) this.signature[index] = (byte) index;
            var base = net.minecraft.network.chat.PlayerChatMessage.unsigned(this.owner, "native signed fixture");
            var body = new net.minecraft.network.chat.SignedMessageBody("native signed fixture", java.time.Instant.parse("2026-01-02T03:04:05Z"),
                    12345L, net.minecraft.network.chat.LastSeenMessages.EMPTY);
            var nativeMessage = new net.minecraft.network.chat.PlayerChatMessage(base.link(), new net.minecraft.network.chat.MessageSignature(this.signature.clone()),
                    body, net.minecraft.network.chat.Component.literal("decorated fixture"), net.minecraft.network.chat.FilterMask.PASS_THROUGH);
            var originalSource = this.server.getPlayerList().getPlayer(this.owner).createCommandSourceStack();
            var source = originalSource.withSigningContext(
                    new net.minecraft.commands.CommandSigningContext.SignedArguments(Map.of("value", nativeMessage)), originalSource.getChatMessageChainer());
            // Compare the result after vanilla decoration/filtering, not the pre-resolution input.
            this.nativeOracle = new CompletableFuture<>();
            var vanillaDispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            vanillaDispatcher.register(net.minecraft.commands.Commands.literal("nativefixture")
                    .then(net.minecraft.commands.Commands.argument("value", net.minecraft.commands.arguments.MessageArgument.message()).executes(context -> {
                        net.minecraft.commands.arguments.MessageArgument.resolveChatMessage(context, "value", this.nativeOracle::complete);
                        return 1;
                    })));
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<io.papermc.paper.command.brigadier.CommandSourceStack>();
            var result = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<net.kyori.adventure.chat.SignedMessage>>();
            dispatcher.register(io.papermc.paper.command.brigadier.Commands.literal("signedfixture")
                    .then(io.papermc.paper.command.brigadier.Commands.argument("value", ArgumentTypes.signedMessage()).executes(context -> {
                        result.set(context.getArgument("value", io.papermc.paper.command.brigadier.argument.SignedMessageResolver.class).resolveSignedMessage("value", context));
                        return 1;
                    })));
            try {
                require(vanillaDispatcher.execute("nativefixture native signed fixture", source) == 1, "Native signed-message control did not execute");
                require(dispatcher.execute("signedfixture native signed fixture", (io.papermc.paper.command.brigadier.CommandSourceStack) (Object) source) == 1,
                        "Synthetic native signing context did not execute");
            } catch (CommandSyntaxException failure) { throw new IllegalStateException(failure); }
            return java.util.Objects.requireNonNull(result.get());
        }
        @SuppressWarnings("unchecked") Map<String, String> snapshot() throws Exception {
            return (Map<String, String>) this.plugin.getClass().getMethod("readBrigadier").invoke(this.plugin);
        }
        void close() {
            try {
                this.plugin.getClass().getMethod("endBrigadier").invoke(this.plugin);
                this.channel.eventLoop().submit(() -> { if (this.channel.pipeline().get(this.handler) != null) this.channel.pipeline().remove(this.handler); }).get(10, TimeUnit.SECONDS);
                require(this.channel.pipeline().get(this.handler) == null, "Brigadier packet observer survived cleanup");
            } catch (Exception failure) { throw new IllegalStateException("Cannot clean Brigadier fixture", failure); }
        }
    }
    private static Object field(Object value, String name) throws Exception {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(value); }
            catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void syntax(Checked action) throws Exception {
        try { action.run(); } catch (CommandSyntaxException expected) { return; }
        throw new AssertionError("Client argument lost its validation properties");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
