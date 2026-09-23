package org.cardboardpowered.impl.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.junit.jupiter.api.Test;

class ClientCommandArgumentSerializationTest {
    @Test
    void customArgumentUsesClientCompatibleSerialization() {
        ArgumentType<String> custom = StringReader::readUnquotedString;
        assertNotNull(ClientCommandArgumentSerialization.forClient(custom));
        assertNotNull(ClientCommandArgumentSerialization.forClient(StringArgumentType.string()));
    }
}
