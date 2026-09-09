package io.papermc.paper.adventure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBarImplementation;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class BossBarImplementationProviderTest {

    @Test
    void adventureBossBarImplementationIsDiscoverable() {
        BossBar bar = BossBar.bossBar(
                Component.text("Test"),
                1.0f,
                BossBar.Color.RED,
                BossBar.Overlay.PROGRESS
        );

        BossBarImplementation implementation = assertDoesNotThrow(
                () -> BossBarImplementation.get(bar, BossBarImplementationImpl.class)
        );
        assertInstanceOf(BossBarImplementationImpl.class, implementation);
    }
}
