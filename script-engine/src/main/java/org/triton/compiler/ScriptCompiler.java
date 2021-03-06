package org.triton.compiler;

import lombok.Data;
import org.bw.tl.Error;
import org.bw.tl.ErrorType;
import org.bw.tl.antlr.ast.*;
import org.bw.tl.compiler.MethodCtx;
import org.bw.tl.compiler.MethodImpl;
import org.bw.tl.compiler.Scope;
import org.bw.tl.compiler.resolve.ExpressionResolver;
import org.bw.tl.compiler.resolve.ExpressionResolverImpl;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.*;

import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public @Data class ScriptCompiler {

    private final List<Error> errors = new LinkedList<>();
    private final Clazz script;

    public byte[] build(final String name, final Map<String, TypeName> fields) {
        final ClassWriter cw = new ClassWriter(COMPUTE_FRAMES + COMPUTE_MAXS);

        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, name, null,
                "javax/script/CompiledScript", null);

        writeInitializer(cw);
        writeConstructor(cw);

        final ExpressionResolver resolver = new ExpressionResolverImpl(script, Collections.singletonList(script), new Scope());

        for (final Function function : script.getFunctions()) {
            final Type methodDescriptor = resolver.resolveFunctionCtx(script, function);

            if (methodDescriptor == null) {
                errors.add(ErrorType.GENERAL_ERROR.newError("Invalid method signature", function));
                continue;
            }

            final MethodVisitor mv = cw.visitMethod(function.getAccessModifiers(), function.getName(),
                    methodDescriptor.getDescriptor(), null, null);

            mv.visitCode();

            final MethodCtx ctx = new MethodCtx(Collections.singletonList(script), function, script);

            if ("eval".equals(function.getName())) {
                if (function.getBody() instanceof Block) {
                    final Block body = (Block) function.getBody();

                    for (Map.Entry<String, TypeName> entry : fields.entrySet()) {
                        body.getStatements().add(0, new Field(entry.getKey(), entry.getValue(), null));
                    }
                }
            }

            final MethodImpl methodImpl = new ScriptMethodImpl(mv, ctx);
            function.accept(methodImpl);

            errors.addAll(ctx.getErrors());

            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        if (!errors.isEmpty())
            return null;

        return cw.toByteArray();
    }

    private void writeConstructor(final ClassWriter cw) {
        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V",
                null, null);

        mv.visitCode();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "javax/script/CompiledScript", "<init>", "()V", false);

        mv.visitInsn(RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void writeInitializer(final ClassWriter cw) {
        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "<clinit>", "()V",
                null, null);

        mv.visitCode();

        mv.visitInsn(RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
