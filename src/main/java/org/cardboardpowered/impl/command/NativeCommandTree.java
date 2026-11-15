package org.cardboardpowered.impl.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.util.IdentityHashMap;

/** Per-registration native view: API parsing, native client types and signing metadata. */
public final class NativeCommandTree {
    private NativeCommandTree() { }

    /** The native dispatcher owns this root; ordinary API builders keep ordinary roots. */
    public static final class Root<S> extends RootCommandNode<S> {
        @Override public void addChild(CommandNode<S> node) {
            if (node instanceof LiteralCommandNode<S> literal && needsConversion(node, new IdentityHashMap<>())) {
                node = copy(literal);
            }
            super.addChild(node);
        }
    }

    private static boolean needsConversion(CommandNode<?> node, IdentityHashMap<CommandNode<?>, Boolean> seen) {
        if (seen.put(node, Boolean.TRUE) != null) return false;
        if (node instanceof ArgumentCommandNode<?, ?> argument && !(node instanceof NativeArgumentNode<?, ?>)) {
            if (argument.getType() instanceof CustomArgumentType<?, ?>
                    || argument.getType() instanceof io.papermc.paper.command.brigadier.argument.VanillaArgumentProviderImpl.NativeWrapperArgumentType<?, ?>) return true;
        }
        if (node.getRedirect() != null && !(node.getRedirect() instanceof RootCommandNode<?>)
                && needsConversion(node.getRedirect(), seen)) return true;
        for (var child : node.getChildren()) if (needsConversion(child, seen)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    public static <S> LiteralCommandNode<S> copy(LiteralCommandNode<S> root) {
        return (LiteralCommandNode<S>) copyNode(root, new IdentityHashMap<>());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <S> CommandNode<S> copyNode(CommandNode<S> node, IdentityHashMap<CommandNode<S>, CommandNode<S>> copies) {
        var existing = copies.get(node);
        if (existing != null) return existing;
        // Redirects to a dispatcher's root must keep pointing at that live root.
        if (node instanceof RootCommandNode<S>) return node;
        CommandNode<S> copy;
        if (node instanceof LiteralCommandNode<S> literal) {
            copy = new NativeLiteralNode<>(literal);
        } else if (node instanceof ArgumentCommandNode<S, ?> argument) {
            copy = new NativeArgumentNode(argument);
        } else throw new IllegalArgumentException("Unsupported command node: " + node.getClass().getName());
        // Cache before following edges: children and redirects can point back to this node.
        copies.put(node, copy);
        if (node.getRedirect() != null) {
            ((RedirectTarget<S>) copy).setRedirect(copyNode(node.getRedirect(), copies));
        }
        for (var child : node.getChildren()) copy.addChild(copyNode(child, copies));
        return copy;
    }

    private interface RedirectTarget<S> { void setRedirect(CommandNode<S> target); }

    private static final class NativeLiteralNode<S> extends LiteralCommandNode<S> implements RedirectTarget<S> {
        private CommandNode<S> redirect;
        NativeLiteralNode(LiteralCommandNode<S> original) {
            super(original.getLiteral(), original.getCommand(), original.getRequirement(), null,
                    original.getRedirectModifier(), original.isFork());
        }
        @Override public CommandNode<S> getRedirect() { return this.redirect; }
        @Override public void setRedirect(CommandNode<S> target) { this.redirect = target; }
    }

    private static final class NativeArgumentNode<S, T> extends ArgumentCommandNode<S, T> implements RedirectTarget<S> {
        private final ArgumentType<?> apiType;
        private CommandNode<S> redirect;
        @SuppressWarnings("unchecked")
        NativeArgumentNode(ArgumentCommandNode<S, T> original) {
            super(original.getName(), (ArgumentType<T>) ClientCommandArgumentSerialization.nativeType(pureType(original)),
                    original.getCommand(), original.getRequirement(), null, original.getRedirectModifier(), original.isFork(), suggestions(original));
            this.apiType = pureType(original);
        }
        @Override public CommandNode<S> getRedirect() { return this.redirect; }
        @Override public void setRedirect(CommandNode<S> target) { this.redirect = target; }
        @SuppressWarnings("unchecked")
        @Override public void parse(StringReader reader, CommandContextBuilder<S> builder) throws CommandSyntaxException {
            int start = reader.getCursor();
            Object result = this.apiType.parse(reader, builder.getSource());
            var parsed = new ParsedArgument<S, Object>(start, reader.getCursor(), result);
            builder.withArgument(this.getName(), parsed);
            builder.withNode(this, parsed.getRange());
        }
    }

    private static ArgumentType<?> pureType(ArgumentCommandNode<?, ?> node) {
        return node instanceof NativeArgumentNode<?, ?> nativeNode ? nativeNode.apiType : node.getType();
    }

    private static <S> SuggestionProvider<S> suggestions(ArgumentCommandNode<S, ?> node) {
        if (node.getCustomSuggestions() != null) return node.getCustomSuggestions();
        if (node.getType() instanceof CustomArgumentType<?, ?> custom) {
            try {
                var method = custom.getClass().getMethod("listSuggestions", CommandContext.class, SuggestionsBuilder.class);
                if (method.getDeclaringClass() != CustomArgumentType.class) return custom::listSuggestions;
            } catch (NoSuchMethodException impossible) {
                throw new IllegalStateException("Custom argument has no suggestion method", impossible);
            }
        }
        return null;
    }
}
