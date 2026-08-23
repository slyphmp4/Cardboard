# Modded BlockEntity registry namespace fix

Cardboard 26.2.3 still propagated `IllegalStateException: Unexpected BlockState` for Storage Delight inventories because the compatibility guard used Bukkit `Material#getKey()` to detect whether the owning block was modded.

Dynamically injected Bukkit materials can report a vanilla namespace even when the underlying Minecraft block is registered by a mod.

The owner fallback now reads the block identifier directly from `BuiltInRegistries.BLOCK` and only suppresses the specific `Unexpected BlockState` failure for non-`minecraft` block namespaces.
