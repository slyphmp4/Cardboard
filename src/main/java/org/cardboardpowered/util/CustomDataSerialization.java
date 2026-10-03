package org.cardboardpowered.util;

public final class CustomDataSerialization {

    private static final ThreadLocal<Boolean> SERIALIZE_AS_SNBT =
            ThreadLocal.withInitial(() -> false);

    private CustomDataSerialization() {
    }

    public static boolean serializeAsSnbt() {
        return SERIALIZE_AS_SNBT.get();
    }

    public static void setSerializeAsSnbt(boolean value) {
        SERIALIZE_AS_SNBT.set(value);
    }
}
