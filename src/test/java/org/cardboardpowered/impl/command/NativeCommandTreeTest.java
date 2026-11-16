package org.cardboardpowered.impl.command;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class NativeCommandTreeTest {
    @Test void nativeViewKeepsApiResultsAndCustomSuggestions() throws Exception {
        var value = new AtomicInteger();
        var nativeType = StringArgumentType.word();
        var converted = new CustomArgumentType.Converted<Integer, String>() {
            @Override public ArgumentType<String> getNativeType() { return nativeType; }
            @Override public Integer convert(String word) { return word.length(); }
            @Override public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
                return builder.suggest("example").buildFuture();
            }
        };
        var original = LiteralArgumentBuilder.<Object>literal("probe")
                .then(RequiredArgumentBuilder.<Object, Integer>argument("word", converted).executes(context -> {
                    value.set(context.getArgument("word", Integer.class)); return 7;
                })).build();
        var copied = NativeCommandTree.copy(original);
        assertSame(nativeType, ((ArgumentCommandNode<?, ?>) copied.getChild("word")).getType());
        assertSame(converted, ((ArgumentCommandNode<?, ?>) original.getChild("word")).getType());
        var dispatcher = new CommandDispatcher<Object>(new NativeCommandTree.Root<>()); dispatcher.getRoot().addChild(original);
        assertSame(nativeType, ((ArgumentCommandNode<?, ?>) dispatcher.getRoot().getChild("probe").getChild("word")).getType());
        assertEquals(7, dispatcher.execute("probe example", new Object()));
        assertEquals(7, value.get());
        assertEquals("example", dispatcher.getCompletionSuggestions(dispatcher.parse("probe ex", new Object())).get().getList().getFirst().getText());
        // Copying a native view again must not discard its API converter.
        var again = new CommandDispatcher<Object>(); again.getRoot().addChild(NativeCommandTree.copy(copied));
        value.set(0); assertEquals(7, again.execute("probe again", new Object())); assertEquals(5, value.get());
    }

    @Test void redirectsAndForksStayInsideTheCopiedGraph() throws Exception {
        var sum = new AtomicInteger();
        var original = LiteralArgumentBuilder.<Integer>literal("probe")
                .then(LiteralArgumentBuilder.<Integer>literal("end").executes(context -> { sum.addAndGet(context.getSource()); return 1; })).build();
        var fork = LiteralArgumentBuilder.<Integer>literal("again").fork(original, context -> List.of(10, 20)).build();
        original.addChild(fork);
        var copy = NativeCommandTree.copy(original);
        assertNotSame(original, copy);
        assertSame(copy, copy.getChild("again").getRedirect());
        assertSame(original, original.getChild("again").getRedirect());
        var dispatcher = new CommandDispatcher<Integer>(); dispatcher.getRoot().addChild(copy);
        assertEquals(2, dispatcher.execute("probe again end", 0)); assertEquals(30, sum.get());
        var rootRedirect = LiteralArgumentBuilder.<Integer>literal("global").redirect(dispatcher.getRoot()).build();
        assertSame(dispatcher.getRoot(), NativeCommandTree.copy(rootRedirect).getRedirect());
    }

    @Test void invalidCustomNativeTypesFailWithoutRecursionOverflow() {
        var cyclic = new CustomArgumentType<String, String>() {
            @Override public String parse(StringReader reader) { return reader.readUnquotedString(); }
            @Override public ArgumentType<String> getNativeType() { return this; }
        };
        assertThrows(IllegalArgumentException.class, () -> ClientCommandArgumentSerialization.nativeType(cyclic));
        var absent = new CustomArgumentType<String, String>() {
            @Override public String parse(StringReader reader) { return reader.readUnquotedString(); }
            @Override public ArgumentType<String> getNativeType() { return null; }
        };
        assertThrows(IllegalArgumentException.class, () -> ClientCommandArgumentSerialization.nativeType(absent));
    }
}
