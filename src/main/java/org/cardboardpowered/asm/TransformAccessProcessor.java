package org.cardboardpowered.asm;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class TransformAccessProcessor implements MixinProcessor {

    private static final String TYPE = Type.getDescriptor(TransformAccess.class);
    private final PaperPlayerInfoUpdatePacketProcessor paperPlayerInfoUpdatePacketProcessor =
            new PaperPlayerInfoUpdatePacketProcessor();

    @Override
    public void accept(String className, ClassNode classNode, IMixinInfo mixinInfo) {
        // Cardboard's mixin plugin already invokes this processor for both preApply
        // and postApply. The compatibility processor is idempotent, so routing it
        // through the same hook keeps target-class bytecode changes centralized.
        paperPlayerInfoUpdatePacketProcessor.accept(className, classNode, mixinInfo);

        field:
        for (var field : classNode.fields) {
            if (field.invisibleAnnotations != null) {
                for (var ann : field.invisibleAnnotations) {
                    if (TYPE.equals(ann.desc)) {
                        field.access = (Integer) ann.values.get(1);
                        continue field;
                    }
                }
            }
        }
        method:
        for (var method : classNode.methods) {
            if (method.invisibleAnnotations != null) {
                for (var ann : method.invisibleAnnotations) {
                    if (TYPE.equals(ann.desc)) {
                    	// System.out.println("HELLO WORLD:! " + method);
                        method.access = 9; // (Integer) ann.values.get(1);
                        continue method;
                    }
                }
            }
        }
    }
}