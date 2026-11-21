/*
 * Patches the classes.jar of the official androidx.compose.foundation:foundation-android:1.12.1 AAR:
 * two private methods get an entry guard, every other entry and every other class stays as it is.
 * See README.md in this directory.
 *
 *   TextFieldCoreModifierNode.startCursorJob()V                          if (!isAttached) return
 *   SelectableTextAnnotatedStringNode.retrievePinnableContainer()        if (!isAttached) return null
 *
 * Usage (JDK 21 single-file source launcher, org.ow2.asm:asm 9.x on the class path):
 *
 *   java -cp asm-9.11.1.jar RaftAttachGuardPatch.java IN_CLASSES_JAR OUT_CLASSES_JAR
 *
 * The guard is the bytecode kotlinc emits for `if (!isAttached) return` at the start of a method
 * (for example AbstractScrollableNode.updateDefaultFlingBehavior in the same jar):
 *
 *   aload_0; invokevirtual <this class>.isAttached()Z; ifne BODY; return   (aconst_null; areturn)
 *   BODY: same_frame
 *
 * Stack map frames: the ClassWriter computes nothing (flags 0). It is built from the ClassReader, so
 * the constant pool is copied as is (the isAttached reference is appended) and every other method is
 * copied byte for byte. In the patched method the original frames are passed through, and the
 * guard adds one explicit F_SAME frame at BODY: there the locals are just `this` and the stack is
 * empty, as at method entry, which is what F_SAME means for the first frame. COMPUTE_FRAMES would
 * recompute the frames of the whole method, and merging reference types needs the class hierarchy
 * (Compose UI, coroutines, the Android SDK) or a getCommonSuperClass guess that can produce frames
 * the verifier rejects; this ClassWriter fails if it is ever asked for a common super class.
 *
 * The patcher refuses to run if a target class or method is missing, if a method already starts
 * with an isAttached call, or if its first instruction is a branch target or starts a try block
 * (then the guard would need a different frame or would change the try range). Jar entries are
 * copied in the same order with the same bytes, name, timestamp, compression method, extra field
 * and comment; the patched classes are compressed again.
 */

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class RaftAttachGuardPatch {
    private static final int API = Opcodes.ASM9;
    private static final String GUARD_NAME = "isAttached";
    private static final String GUARD_DESCRIPTOR = "()Z";

    /** A private instance method of {@code owner} that gets the guard; {@code owner} is also the call's owner. */
    private record Target(String owner, String method, String descriptor) {
        String entry() {
            return owner + ".class";
        }

        String returnType() {
            return descriptor.substring(descriptor.indexOf(')') + 1);
        }

        boolean returnsVoid() {
            return returnType().equals("V");
        }

        String label() {
            return owner.replace('/', '.') + "." + method + descriptor;
        }
    }

    private static final List<Target> TARGETS = List.of(
            new Target("androidx/compose/foundation/text/input/internal/TextFieldCoreModifierNode",
                    "startCursorJob", "()V"),
            new Target("androidx/compose/foundation/text/modifiers/SelectableTextAnnotatedStringNode",
                    "retrievePinnableContainer", "()Landroidx/compose/ui/layout/PinnableContainer;"));

    private static final class PatchException extends RuntimeException {
        PatchException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java -cp asm.jar RaftAttachGuardPatch.java IN_CLASSES_JAR OUT_CLASSES_JAR");
            System.exit(2);
            return;
        }
        try {
            run(Path.of(args[0]), Path.of(args[1]));
        } catch (PatchException e) {
            System.err.println("RaftAttachGuardPatch: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Path in, Path out) throws IOException {
        if (Files.exists(out)) {
            throw new PatchException(out + " already exists");
        }
        Map<String, Target> byEntry = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            byEntry.put(target.entry(), target);
        }
        Path partial = out.resolveSibling(out.getFileName() + ".partial");
        Set<String> patched = new HashSet<>();
        int copied = 0;
        try (ZipFile zip = new ZipFile(in.toFile());
                OutputStream file = Files.newOutputStream(partial);
                ZipOutputStream jar = new ZipOutputStream(new BufferedOutputStream(file))) {
            for (String name : byEntry.keySet()) {
                if (zip.getEntry(name) == null) {
                    throw new PatchException(in + " has no " + name);
                }
            }
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!names.add(entry.getName())) {
                    throw new PatchException(in + " has two entries named " + entry.getName());
                }
                byte[] data;
                try (InputStream input = zip.getInputStream(entry)) {
                    data = input.readAllBytes();
                }
                // Copies name, DOS timestamp, method, size, CRC, extra field and comment.
                ZipEntry copy = new ZipEntry(entry);
                Target target = byEntry.get(entry.getName());
                if (target != null) {
                    byte[] result = patchClass(data, target);
                    System.out.printf("patched %s: %d -> %d bytes%n", target.label(), data.length, result.length);
                    data = result;
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    copy.setSize(data.length);
                    copy.setCrc(crc.getValue());
                    patched.add(entry.getName());
                } else {
                    copied++;
                }
                // Compressed bytes are written again: a STORED entry's compressed size is its size,
                // a DEFLATED entry's is recorded after the data.
                copy.setCompressedSize(copy.getMethod() == ZipEntry.STORED ? copy.getSize() : -1);
                jar.putNextEntry(copy);
                jar.write(data);
                jar.closeEntry();
            }
            jar.setComment(zip.getComment());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        if (!patched.equals(byEntry.keySet())) {
            Files.deleteIfExists(partial);
            throw new PatchException("patched " + patched + ", expected " + byEntry.keySet());
        }
        Files.move(partial, out);
        System.out.printf("copied %d other entries unchanged; wrote %s%n", copied, out);
    }

    private static byte[] patchClass(byte[] original, Target target) {
        ClassReader reader = new ClassReader(original);
        if (!reader.getClassName().equals(target.owner())) {
            throw new PatchException(target.entry() + " declares " + reader.getClassName());
        }
        EntryShape.read(reader, target).requirePatchable();
        ClassWriter writer = new ClassWriter(reader, 0) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                throw new PatchException("frames must not be computed (asked for " + type1 + ", " + type2 + ")");
            }
        };
        GuardInserter inserter = new GuardInserter(writer, target);
        reader.accept(inserter, 0);
        if (inserter.inserted != 1) {
            throw new PatchException(target.label() + ": guard inserted " + inserter.inserted + " times");
        }
        byte[] result = writer.toByteArray();
        EntryShape.read(new ClassReader(result), target).requireGuarded();
        return result;
    }

    /** Passes every method through untouched except the target, which gets the guard. */
    private static final class GuardInserter extends ClassVisitor {
        private final Target target;
        private int inserted;

        GuardInserter(ClassVisitor next, Target target) {
            super(API, next);
            this.target = target;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!name.equals(target.method()) || !descriptor.equals(target.descriptor())) {
                // The ClassWriter's own visitor: ClassReader copies this method's bytes as they are.
                return next;
            }
            inserted++;
            return new EntryGuard(next, target);
        }
    }

    /**
     * Emits the guard right before the method's first instruction, after the label and line number
     * at offset 0, so the first line number and the local variable {@code this} also cover it.
     */
    private static final class EntryGuard extends MethodVisitor {
        private final Target target;
        private boolean emitted;

        EntryGuard(MethodVisitor next, Target target) {
            super(API, next);
            this.target = target;
        }

        private void guard() {
            if (emitted) {
                return;
            }
            emitted = true;
            Label body = new Label();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, target.owner(), GUARD_NAME, GUARD_DESCRIPTOR, false);
            super.visitJumpInsn(Opcodes.IFNE, body);
            if (target.returnsVoid()) {
                super.visitInsn(Opcodes.RETURN);
            } else {
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitInsn(Opcodes.ARETURN);
            }
            super.visitLabel(body);
            // Locals [this], empty stack: the same as the implicit frame at method entry.
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if (!emitted) {
                throw new PatchException(target.label() + ": stack map frame before the first instruction");
            }
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitInsn(int opcode) {
            guard();
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            guard();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            guard();
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            guard();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            guard();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            guard();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            guard();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            guard();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            guard();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            guard();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            guard();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            guard();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            guard();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (!emitted) {
                throw new PatchException(target.label() + ": method has no instructions");
            }
            // The guard needs one stack slot; max_locals is unchanged (it only reads local 0).
            super.visitMaxs(Math.max(maxStack, 1), maxLocals);
        }
    }

    /** The first instructions and frames of the target method, and what sits at its offset 0. */
    private static final class EntryShape extends ClassVisitor {
        private static final int HEAD = 6;
        private final Target target;
        private final List<String> head = new ArrayList<>();
        private int found;
        private int access;
        private boolean frameAtEntry;
        private boolean handlerAtEntry;

        private EntryShape(Target target) {
            super(API);
            this.target = target;
        }

        static EntryShape read(ClassReader reader, Target target) {
            EntryShape shape = new EntryShape(target);
            reader.accept(shape, 0);
            return shape;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!name.equals(target.method()) || !descriptor.equals(target.descriptor())) {
                return null;
            }
            found++;
            this.access = access;
            return new MethodVisitor(API) {
                private final Set<Label> handlerStarts = Collections.newSetFromMap(new IdentityHashMap<>());
                private final Set<Label> entryLabels = Collections.newSetFromMap(new IdentityHashMap<>());
                private boolean inBody;

                private void note(String event) {
                    if (head.size() < HEAD) {
                        head.add(event);
                    }
                }

                private void instruction(String event) {
                    if (!inBody) {
                        inBody = true;
                        for (Label label : entryLabels) {
                            if (handlerStarts.contains(label)) {
                                handlerAtEntry = true;
                            }
                        }
                    }
                    note(event);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    handlerStarts.add(start);
                }

                @Override
                public void visitLabel(Label label) {
                    if (!inBody) {
                        entryLabels.add(label);
                    }
                }

                @Override
                public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                    if (!inBody) {
                        frameAtEntry = true;
                    }
                    note("FRAME " + type + " " + numLocal + " " + numStack);
                }

                @Override
                public void visitInsn(int opcode) {
                    instruction("INSN " + opcode);
                }

                @Override
                public void visitIntInsn(int opcode, int operand) {
                    instruction("INT " + opcode + " " + operand);
                }

                @Override
                public void visitVarInsn(int opcode, int varIndex) {
                    instruction("VAR " + opcode + " " + varIndex);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    instruction("TYPE " + opcode + " " + type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    instruction("FIELD " + opcode + " " + owner + "." + name + ":" + descriptor);
                }

                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    instruction("METHOD " + opcode + " " + owner + "." + name + descriptor + (isInterface ? " itf" : ""));
                }

                @Override
                public void visitInvokeDynamicInsn(
                        String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                    instruction("INDY " + name + descriptor);
                }

                @Override
                public void visitJumpInsn(int opcode, Label label) {
                    instruction("JUMP " + opcode);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    instruction("LDC");
                }

                @Override
                public void visitIincInsn(int varIndex, int increment) {
                    instruction("IINC " + varIndex + " " + increment);
                }

                @Override
                public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                    instruction("TABLESWITCH");
                }

                @Override
                public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                    instruction("LOOKUPSWITCH");
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                    instruction("MULTIANEWARRAY " + descriptor);
                }
            };
        }

        void requirePatchable() {
            String what = target.label();
            if (found != 1) {
                throw new PatchException(what + (found == 0 ? " is missing" : " is declared " + found + " times"));
            }
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                throw new PatchException(what + " is not a concrete instance method (access 0x"
                        + Integer.toHexString(access) + ")");
            }
            if (!target.returnsVoid() && !target.returnType().startsWith("L") && !target.returnType().startsWith("[")) {
                throw new PatchException(what + " returns a primitive: the guard can only return nothing or null");
            }
            if (head.size() >= 2
                    && head.get(0).equals("VAR " + Opcodes.ALOAD + " 0")
                    && head.get(1).startsWith("METHOD " + Opcodes.INVOKEVIRTUAL + " ")
                    && head.get(1).endsWith("." + GUARD_NAME + GUARD_DESCRIPTOR)) {
                throw new PatchException(what + " already starts with an isAttached guard; refusing to patch it again");
            }
            if (frameAtEntry) {
                throw new PatchException(what + ": the first instruction is a branch target (stack map frame at offset 0)");
            }
            if (handlerAtEntry) {
                throw new PatchException(what + ": a try block starts at the first instruction");
            }
        }

        void requireGuarded() {
            List<String> expected = new ArrayList<>(List.of(
                    "VAR " + Opcodes.ALOAD + " 0",
                    "METHOD " + Opcodes.INVOKEVIRTUAL + " " + target.owner() + "." + GUARD_NAME + GUARD_DESCRIPTOR,
                    "JUMP " + Opcodes.IFNE));
            if (target.returnsVoid()) {
                expected.add("INSN " + Opcodes.RETURN);
            } else {
                expected.add("INSN " + Opcodes.ACONST_NULL);
                expected.add("INSN " + Opcodes.ARETURN);
            }
            expected.add("FRAME " + Opcodes.F_SAME + " 0 0");
            if (found != 1 || head.size() < expected.size() || !head.subList(0, expected.size()).equals(expected)) {
                throw new PatchException(target.label() + ": the patched method starts with " + head
                        + ", expected " + expected);
            }
        }
    }
}
