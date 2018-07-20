package org.bw.tl.compiler;

import lombok.Data;
import org.bw.tl.Error;
import org.bw.tl.ErrorType;
import org.bw.tl.antlr.ast.*;
import org.bw.tl.compiler.resolve.SymbolResolver;
import org.bw.tl.verify.FunReturnVerifier;
import org.bw.tl.verify.Verifiable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.*;
import java.util.stream.Collectors;

import static org.objectweb.asm.ClassWriter.*;
import static org.objectweb.asm.Opcodes.*;

public @Data class Compiler {

    private final Verifiable<Function> functionVerifiable = new FunReturnVerifier();
    private final List<Error> errors = new LinkedList<>();
    private final List<Clazz> classes;

    public Compiler(final List<Clazz> classes) {
        this.classes = classes;
    }

    public Compiler(final Clazz... classes) {
        this(Arrays.asList(classes));
    }

    public Map<String, byte[]> compile() {
        final HashMap<String, byte[]> classMap = new HashMap<>();

        for (final Clazz clazz : classes) {
            try {
                classMap.put(clazz.getModuleClassName(), build(clazz));
            } catch (final Throwable throwable) {
                throwable.printStackTrace();
                return null;
            }
        }

        if (!errors.isEmpty())
            return null;

        return classMap;
    }

    private byte[] build(final Clazz clazz) {
        final ClassWriter cw = new ClassWriter(COMPUTE_FRAMES + COMPUTE_MAXS);

        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, clazz.getInternalName(), null,
                "java/lang/Object", null);

        buildClassInitializer(cw, clazz);


        final SymbolResolver symbolResolver = new SymbolResolver(classes, clazz);

        for (final Field field : clazz.getFields()) {
            if (field.getType() == null) {
                errors.add(ErrorType.GENERAL_ERROR.newError("Implicit typing is only supported for local variables", field));
                continue;
            }
            final Type type = symbolResolver.resolveType(clazz, field.getType());
            if (type == null) {
                errors.add(ErrorType.GENERAL_ERROR.newError("Cannot resolve type: " + field.getType(), field));
                continue;
            }
            cw.visitField(field.getAccessModifiers(), field.getName(), type.getDescriptor(), null, null);
        }

        for (final Function function : clazz.getFunctions()) {
            final Type methodDescriptor = symbolResolver.resolveFunction(clazz, function);

            if (!functionVerifiable.isValid(function)) {
                errors.add(ErrorType.GENERAL_ERROR.newError("Missing return statement", function));
                continue;
            }

            if (methodDescriptor == null) {
                errors.add(ErrorType.GENERAL_ERROR.newError("Invalid method signature", function));
                continue;
            }

            final MethodVisitor mv = cw.visitMethod(function.getAccessModifiers(), function.getName(),
                    methodDescriptor.getDescriptor(), null, null);

            mv.visitCode();

            final MethodCtx ctx = new MethodCtx(classes, function, clazz);

            function.accept(MethodImpl.of(mv, ctx));

            errors.addAll(ctx.getErrors());

            mv.visitMaxs(0, 0);
            mv.visitEnd();

        }

        return cw.toByteArray();
    }

    private void buildClassInitializer(final ClassWriter cw, final Clazz clazz) {

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "<clinit>", "()V",
                null, null);

        mv.visitCode();

        final List<Node> statements = clazz.getFields().stream().filter(stmt -> stmt.getType() != null)
                .map(f -> new Assignment(null, f.getName(), f.getInitialValue()))
                .collect(Collectors.toList());

        final Block block = new Block(statements);
        final Function init = new Function(new TypeName[0], new String[0], "<clinit>", block,
                new TypeName("void"));

        final MethodCtx ctx = new MethodCtx(classes, init, clazz);

        init.accept(MethodImpl.of(mv, ctx));

        errors.addAll(ctx.getErrors());

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
