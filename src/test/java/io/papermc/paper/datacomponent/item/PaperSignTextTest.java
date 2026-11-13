package io.papermc.paper.datacomponent.item;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PaperSignTextTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void reusingBuilderDoesNotMutateBuiltSignText() {
        var builder = new PaperSignText.BuilderImpl();
        builder.line(0, Component.text("first"));
        var first = (PaperSignText) builder.build();
        builder.line(0, Component.text("second"));
        assertEquals("first", first.impl().getMessages(false).getFirst().getString());
        assertEquals("first", first.impl().getMessages(true).getFirst().getString());
    }

    @Test
    void editingOneLinePreservesOtherFilteredLines() {
        var raw = List.of(net.minecraft.network.chat.Component.literal("old"), net.minecraft.network.chat.Component.literal("secret"),
                net.minecraft.network.chat.Component.empty(), net.minecraft.network.chat.Component.empty());
        var filtered = List.of(net.minecraft.network.chat.Component.literal("filtered old"), net.minecraft.network.chat.Component.empty(),
                net.minecraft.network.chat.Component.empty(), net.minecraft.network.chat.Component.empty());
        var builder = new PaperSignText.BuilderImpl(new java.util.ArrayList<>(raw), new java.util.ArrayList<>(filtered));
        builder.line(0, Component.text("new"));
        var result = ((PaperSignText) builder.build()).impl();
        assertEquals("new", result.getMessages(true).getFirst().getString());
        assertEquals("secret", result.getMessages(false).get(1).getString());
        assertEquals("", result.getMessages(true).get(1).getString());
    }

    @Test
    void replacingLinesUpdatesBothVariantsAndPadsToFour() {
        var builder = new PaperSignText.BuilderImpl();
        builder.lines(List.of(Component.text("replacement")));
        var value = ((PaperSignText) builder.build()).impl();
        assertEquals("replacement", value.getMessages(true).getFirst().getString());
        assertEquals(4, value.getMessages(false).size());
        assertEquals(value.getMessages(false), value.getMessages(true));
    }
}
