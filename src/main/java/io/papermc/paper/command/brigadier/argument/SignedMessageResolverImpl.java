package io.papermc.paper.command.brigadier.argument;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.adventure.PaperAdventure;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.PlayerChatMessage;
import org.jspecify.annotations.Nullable;

/** Resolve through vanilla's signing/filtering chain without replacing the caller's API arguments. */
public record SignedMessageResolverImpl(MessageArgument.Message message) implements SignedMessageResolver {
    @Override public String content() { return this.message.text(); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public CompletableFuture<SignedMessage> resolveSignedMessage(String argumentName, CommandContext context) throws CommandSyntaxException {
        MessageArgument.Message response = ((SignedMessageResolverImpl) context.getArgument(argumentName, SignedMessageResolverImpl.class)).message;
        var range = context.getRange();
        // Vanilla's entry point expects the native Message as the named argument. Retain the
        // same source/signing context and input, and adapt only this private invocation.
        var nativeContext = new CommandContext(context.getSource(), context.getInput(),
                Map.of(argumentName, new ParsedArgument(range.getStart(), range.getEnd(), response)),
                context.getCommand(), context.getRootNode(), context.getNodes(), range,
                context.getChild(), context.getRedirectModifier(), context.isForked());
        var future = new CompletableFuture<SignedMessage>();
        MessageArgument.resolveChatMessage(nativeContext, argumentName, resolved -> future.complete(new AdventureView(resolved)));
        return future;
    }

    private record AdventureView(PlayerChatMessage nativeMessage) implements SignedMessage {
        @Override public Instant timestamp() { return this.nativeMessage.timeStamp(); }
        @Override public long salt() { return this.nativeMessage.salt(); }
        @Override public @Nullable Signature signature() {
            var signature = this.nativeMessage.signature();
            return signature == null ? null : SignedMessage.signature(signature.bytes().clone());
        }
        @Override public @Nullable Component unsignedContent() {
            var content = this.nativeMessage.unsignedContent();
            return content == null ? null : PaperAdventure.asAdventure(content);
        }
        @Override public String message() { return this.nativeMessage.signedContent(); }
        @Override public Identity identity() { return Identity.identity(this.nativeMessage.sender()); }
    }
}
