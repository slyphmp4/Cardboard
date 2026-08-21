package org.cardboardpowered.asm;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Restores the Paper-added constructors on ClientboundPlayerInfoUpdatePacket.
 *
 * Paper plugins such as TAB compile against these constructors directly. Vanilla
 * Minecraft does not provide them, so exposing Paper API without the matching
 * server patch results in a NoSuchMethodError at runtime.
 */
public final class PaperPlayerInfoUpdatePacketProcessor implements MixinProcessor {

    private static final String MIXIN_CLASS =
            "org.cardboardpowered.mixin.network.protocol.game.ClientboundPlayerInfoUpdatePacketMixin";

    private static final String ENUM_SET_DESC = "Ljava/util/EnumSet;";
    private static final String LIST_DESC = "Ljava/util/List;";
    private static final String LIST_OF_DESC = "(Ljava/lang/Object;)Ljava/util/List;";

    @Override
    public void accept(String className, ClassNode classNode, IMixinInfo mixinInfo) {
        if (mixinInfo == null || !MIXIN_CLASS.equals(mixinInfo.getClassName())) {
            return;
        }

        FieldNode actionsField = findUniqueField(classNode, ENUM_SET_DESC, "actions");
        FieldNode entriesField = findUniqueField(classNode, LIST_DESC, "entries");
        String entryInternalName = findEntryInternalName(classNode, entriesField);

        String listConstructorDesc = "(Ljava/util/EnumSet;Ljava/util/List;)V";
        String entryConstructorDesc = "(Ljava/util/EnumSet;L" + entryInternalName + ";)V";

        if (!hasMethod(classNode, "<init>", listConstructorDesc)) {
            classNode.methods.add(createListConstructor(classNode.name, actionsField, entriesField, listConstructorDesc));
        }
        if (!hasMethod(classNode, "<init>", entryConstructorDesc)) {
            classNode.methods.add(createEntryConstructor(classNode.name, actionsField, entriesField, entryConstructorDesc));
        }
    }

    private static FieldNode findUniqueField(ClassNode classNode, String descriptor, String paperName) {
        List<FieldNode> matches = classNode.fields.stream()
                .filter(field -> descriptor.equals(field.desc))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Unable to apply Paper player-info compatibility: expected exactly one " + descriptor
                            + " field for '" + paperName + "' in " + classNode.name + ", found " + matches.size());
        }
        return matches.getFirst();
    }

    private static String findEntryInternalName(ClassNode classNode, FieldNode entriesField) {
        String signature = entriesField.signature;
        if (signature != null) {
            int start = signature.indexOf('<');
            int classStart = start < 0 ? -1 : signature.indexOf('L', start);
            int end = classStart < 0 ? -1 : signature.indexOf(';', classStart);
            if (classStart >= 0 && end > classStart) {
                return signature.substring(classStart + 1, end);
            }
        }
        return classNode.name + "$Entry";
    }

    private static boolean hasMethod(ClassNode classNode, String name, String descriptor) {
        return classNode.methods.stream().anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }

    private static MethodNode createListConstructor(
            String owner,
            FieldNode actionsField,
            FieldNode entriesField,
            String descriptor
    ) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, actionsField.name, actionsField.desc));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, entriesField.name, entriesField.desc));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 3;
        return method;
    }

    private static MethodNode createEntryConstructor(
            String owner,
            FieldNode actionsField,
            FieldNode entriesField,
            String descriptor
    ) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, actionsField.name, actionsField.desc));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", LIST_OF_DESC, true));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, entriesField.name, entriesField.desc));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 3;
        return method;
    }
}
