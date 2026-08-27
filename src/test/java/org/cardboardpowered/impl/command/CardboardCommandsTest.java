package org.cardboardpowered.impl.command;

import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.commands.CommandSource;
import org.junit.jupiter.api.Test;

final class CardboardCommandsTest {

    @Test
    void doesNotResolveBukkitSenderForSyntheticNullSource() {
        assertFalse(CardboardCommands.shouldResolveBukkitSender(CommandSource.NULL));
    }
}
