package org.cardboardpowered.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CardboardModdedBlockTest {

    @Test
    void blockWithMatchingItemCanBeUsedAsRecipeChoice() {
        CardboardModdedBlock block = new CardboardModdedBlock("minecraft:stone");

        assertTrue(block.isBlock());
        assertTrue(block.isItem());
    }

    @Test
    void blockWithoutMatchingItemIsNotAnItem() {
        CardboardModdedBlock block = new CardboardModdedBlock("minecraft:water");

        assertTrue(block.isBlock());
        assertFalse(block.isItem());
    }
}
