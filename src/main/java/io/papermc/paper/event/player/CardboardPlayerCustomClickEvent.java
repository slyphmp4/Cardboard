package io.papermc.paper.event.player;

import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.dialog.PaperDialogResponseView;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/** Public Paper event wrapper for Minecraft's custom-click packet. */
public final class CardboardPlayerCustomClickEvent extends PlayerCustomClickEvent {
    private final @Nullable BinaryTagHolder tag;
    private final @Nullable DialogResponseView response;

    public CardboardPlayerCustomClickEvent(Key identifier, PlayerCommonConnection connection, @Nullable Tag payload) {
        super(identifier, connection);
        this.tag = payload == null ? null : BinaryTagHolder.encode(payload, PaperAdventure.NBT_CODEC);
        this.response = payload instanceof CompoundTag compound
                ? PaperDialogResponseView.createUnvalidatedResponse(compound)
                : null;
    }

    @Override
    public @Nullable BinaryTagHolder getTag() {
        return this.tag;
    }

    @Override
    public @Nullable DialogResponseView getDialogResponseView() {
        return this.response;
    }
}
