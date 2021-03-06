package org.bw.tl.compiler;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bw.tl.antlr.ast.*;
import org.bw.tl.compiler.resolve.*;
import org.bw.tl.compiler.types.AnyTypeHandler;
import org.bw.tl.compiler.types.TypeHandler;
import org.bw.tl.util.TypeUtilities;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bw.tl.util.TypeUtilities.*;

@EqualsAndHashCode(callSuper = false)
public @Data class MethodImpl extends ASTVisitorBase implements Opcodes {

    protected final @NotNull MethodVisitor mv;
    protected final @NotNull MethodCtx ctx;

    private final Label endOfFunctionLabel = new Label();

    protected void defineParameters(final Function function) {
        if (!ctx.isStatic()) {
            ctx.getScope().putVar(" __this__ ", Type.getType(Object.class), 0);
        }

        final String[] parameterNames = function.getParameterNames();
        final TypeName[] parameterTypes = function.getParameterTypes();
        final List<Modifier>[] parameterModifiers = function.getParameterModifiers();

        for (int i = 0; i < function.getParameterNames().length; i++) {
            final int modifiers = parameterModifiers[i].stream().mapToInt(Modifier::getValue).sum();
            final Label startLabel = new Label();

            mv.visitLabel(startLabel);

            if (!ctx.getScope().putVar(parameterNames[i], parameterTypes[i].resolveType(ctx.getResolver()), modifiers)) {
                ctx.reportError("Duplicate function parameter names", function);
            } else {
                final int idx = ctx.getScope().findVar(parameterNames[i]).getIndex();
                final Type type = parameterTypes[i].resolveType(ctx.getResolver());

                if (type != null) {
                    mv.visitLocalVariable(parameterNames[i], type.getDescriptor(), null, startLabel, endOfFunctionLabel, idx);
                } else {
                    ctx.reportError("Cannot resolve symbol", parameterTypes[i]);
                }
            }
        }
    }

    @Override
    public void visitFunction(final Function function) {
        ctx.beginScope();

        defineParameters(function);

        if (function.isShortForm()) {
            if (function.getBody() instanceof Expression) {
                final Expression retVal = (Expression) function.getBody();
                final Type retType = retVal.resolveType(ctx.getResolver());
                if (ctx.getReturnType().equals(Type.VOID_TYPE)) {
                    // short-form function definition with where void return type is explicitly requested
                    retVal.accept(this);

                    if (!retType.equals(Type.VOID_TYPE)) {
                        if (retType.equals(Type.LONG_TYPE) || retType.equals(Type.DOUBLE_TYPE)) {
                            mv.visitInsn(POP2);
                        } else {
                            mv.visitInsn(POP);
                        }
                    }
                } else {
                    visitReturn(new Return(retVal));
                }
            } else {
                ctx.reportError("Illegal return statement", function);
            }
        } else {
            function.getBody().accept(this);
        }

        mv.visitInsn(RETURN);

        mv.visitLabel(endOfFunctionLabel);


        ctx.endScope();
    }

    private Type getImplicitType(final Expression expr) {
        final Type type = expr.resolveType(ctx.getResolver());

        if (type == null)
            return null;

        if (isAssignableFrom(type, Type.INT_TYPE))
            return Type.INT_TYPE;

        return type;
    }

    @Override
    public void visitField(final Field field) {
        Expression value = field.getInitialValue();
        final Type fieldType;

        if (field.getType() != null) {
            fieldType = field.getType().resolveType(ctx.getResolver());
        } else {
            fieldType = getImplicitType(value);

            if (fieldType == null) {
                ctx.reportError("Cannot infer type", field);
                return;
            }

            int dim = 0;

            if (fieldType.getDescriptor().startsWith("["))
                dim = fieldType.getDimensions();

            field.setType(TypeName.of(fieldType.getClassName(), dim));
        }

        if (fieldType == null) {
            ctx.reportError("Cannot resolve field type: " + field.getType(), field);
            return;
        }

        if (!ctx.getScope().putVar(field.getName(), fieldType, field.getAccessModifiers()))
            ctx.reportError("Field: " + field.getName() + " has already been defined", field);

        if (value == null) {
            value = getDefault(fieldType);
        }

        final Assignment assignment = new Assignment(null, field.getName(), value);

        visitAssignment(assignment);
    }

    public Literal getDefault(final Type type) {
        if (type.equals(Type.BYTE_TYPE) || type.equals(Type.CHAR_TYPE)
                || type.equals(Type.SHORT_TYPE) || type.equals(Type.INT_TYPE)) {
            return new Literal<>(0);
        } else if (type.equals(Type.FLOAT_TYPE)) {
            return new Literal<>(0f);
        } else if (type.equals(Type.DOUBLE_TYPE)) {
            return new Literal<>(0.0);
        } else if (type.equals(Type.LONG_TYPE)) {
            return new Literal<>(0L);
        } else if (type.equals(Type.BOOLEAN_TYPE)) {
            return new Literal<>(false);
        } else {
            return new Literal<>(null);
        }
    }

    public void pushDefault(final Type type) {
        if (type.equals(Type.BOOLEAN_TYPE) || type.equals(Type.BYTE_TYPE) || type.equals(Type.CHAR_TYPE)
                || type.equals(Type.SHORT_TYPE) || type.equals(Type.INT_TYPE)) {
            mv.visitInsn(ICONST_0);
        } else if (type.equals(Type.FLOAT_TYPE)) {
            mv.visitInsn(FCONST_0);
        } else if (type.equals(Type.DOUBLE_TYPE)) {
            mv.visitInsn(DCONST_0);
        } else if (type.equals(Type.LONG_TYPE)) {
            mv.visitInsn(LCONST_0);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
    }

    @Override
    public void visitWhile(final WhileLoop whileLoop) {
        final Expression condition = whileLoop.getCondition();
        final Type type = condition.resolveType(ctx.getResolver());

        if (type == null) {
            ctx.reportError("Cannot resolve expression", condition);
            return;
        }

        if (type.equals(Type.BOOLEAN_TYPE)) {
            final Label conditionalLabel = new Label();
            final Label endLabel = new Label();

            mv.visitLabel(conditionalLabel);
            condition.accept(this);
            mv.visitJumpInsn(IFEQ, endLabel);

            ctx.beginScope();
            whileLoop.getBody().accept(this);
            ctx.endScope();

            mv.visitJumpInsn(GOTO, conditionalLabel);
            mv.visitLabel(endLabel);
        } else {
            ctx.reportError("Expected boolean, found: " + type.getClassName(), condition);
        }
    }

    @Override
    public void visitFor(final ForLoop forLoop) {
        ctx.beginScope();

        if (forLoop.getInit() != null)
            forLoop.getInit().accept(this);

        final Expression condition = forLoop.getCondition();

        final Label conditionalLabel = new Label();
        final Label endLabel = new Label();

        mv.visitLabel(conditionalLabel);

        if (condition != null) {
            final Type type = condition.resolveType(ctx.getResolver());

            if (type != null && type.equals(Type.BOOLEAN_TYPE)) {
                condition.accept(this);
                mv.visitJumpInsn(IFEQ, endLabel);
            } else if (type != null) {
                ctx.reportError("Expected boolean, found: " + type.getClassName(), condition);
            } else {
                ctx.reportError("Cannot resolve expression", condition);
            }
        }

        if (forLoop.getBody() != null)
            forLoop.getBody().accept(this);

        forLoop.getUpdate().forEach(e -> e.accept(this));

        mv.visitJumpInsn(GOTO, conditionalLabel);
        mv.visitLabel(endLabel);

        ctx.endScope();
    }

    @Override
    public void visitForEach(final ForEachLoop forEachLoop) {
        ctx.beginScope();

        final Expression iterable = forEachLoop.getIterableExpression();
        final Type iterableType = iterable.resolveType(ctx.getResolver());

        if (iterableType == null) {
            ctx.reportError("Cannot resolve expression", iterable);
            return;
        }

        int dim = TypeUtilities.getDim(iterableType);

        if (dim == 0) {
            ctx.reportError("Expected array type but got " + iterableType.getClassName(), iterable);
            return;
        }

        final Field field = forEachLoop.getField();
        final Type expectedType = TypeUtilities.setDim(iterableType, dim - 1);
        Type fieldType = expectedType;

        if (field.getType() != null) {
            fieldType = field.getType().resolveType(ctx.getResolver());

            if (fieldType == null) {
                ctx.reportError("Cannot resolve type: " + field.getType(), field);
                return;
            }

            if (!expectedType.equals(fieldType)) {
                ctx.reportError("Expected type " + expectedType.getClassName() + " but got " + fieldType.getClassName(), field);
                return;
            }
        }

        ctx.getScope().putVar(" __ITERABLE__ ", iterableType, 0);
        iterable.accept(this);
        mv.visitVarInsn(ASTORE, ctx.getScope().findVar(" __ITERABLE__ ").getIndex());

        ctx.getScope().putVar(" __COUNTER__ ", Type.INT_TYPE, 0);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, ctx.getScope().findVar(" __COUNTER__ ").getIndex());

        final Label conditionalLabel = new Label();
        final Label endLabel = new Label();

        mv.visitLabel(conditionalLabel);

        mv.visitVarInsn(ILOAD, ctx.getScope().findVar(" __COUNTER__ ").getIndex());
        mv.visitVarInsn(ALOAD, ctx.getScope().findVar(" __ITERABLE__ ").getIndex());
        mv.visitInsn(ARRAYLENGTH);

        final Operator operator = Operator.getOperator("<", Type.INT_TYPE, Type.INT_TYPE);
        operator.applyCmp(mv, endLabel);

        ctx.beginScope();

        ctx.getScope().putVar(field.getName(), fieldType, 0);

        final TypeHandler fieldTypeHandler = TypeUtilities.getTypeHandler(fieldType);
        mv.visitVarInsn(ALOAD, ctx.getScope().findVar(" __ITERABLE__ ").getIndex());
        mv.visitVarInsn(ILOAD, ctx.getScope().findVar(" __COUNTER__ ").getIndex());
        fieldTypeHandler.arrayLoad(mv);

        fieldTypeHandler.store(mv, ctx.getScope().findVar(field.getName()).getIndex());

        forEachLoop.getBody().accept(this);
        mv.visitIincInsn(ctx.getScope().findVar(" __COUNTER__ ").getIndex(), 1);

        ctx.endScope();

        mv.visitJumpInsn(GOTO, conditionalLabel);
        mv.visitLabel(endLabel);

        ctx.endScope();
    }

    @Override
    public void visitIf(final IfStatement ifStatement) {
        final Type resultType = ifStatement.resolveType(ctx.getResolver());
        final Expression condition = ifStatement.getCondition();
        final Type type = condition.resolveType(ctx.getResolver());
        final Node elseBlock = ifStatement.getElseBody();

        final Type bodyType = resolveNode(ifStatement.getBody());
        final Type elseBodyType = resolveNode(ifStatement.getElseBody());

        if (!ifStatement.shouldPop()) {
            if (resultType == null) {
                ctx.reportError("If-expression requires each branch to have matching return types", ifStatement);
                return;
            }
        }

        if (type == null) {
            ctx.reportError("Cannot resolve expression", condition);
            return;
        }

        if (type.equals(Type.BOOLEAN_TYPE)) {
            final Label after = new Label();
            final Label elseLabel = new Label();

            condition.accept(this);

            if (!ifStatement.shouldPop())
                asExpression(ifStatement.getBody());

            if (elseBlock != null) {
                mv.visitJumpInsn(IFEQ, elseLabel);

                ctx.beginScope();

                if (!ifStatement.shouldPop())
                    asExpression(elseBlock);

                ifStatement.getBody().accept(this);

                if (!ifStatement.shouldPop())
                    typeCast(bodyType, resultType);

                ctx.endScope();

                mv.visitJumpInsn(GOTO, after);

                mv.visitLabel(elseLabel);

                ctx.beginScope();

                elseBlock.accept(this);

                if (!ifStatement.shouldPop())
                    typeCast(elseBodyType, resultType);
                else if (elseBodyType != null)
                    pop(elseBodyType);

                ctx.endScope();
            } else {
                mv.visitJumpInsn(IFEQ, after);

                ctx.beginScope();

                ifStatement.getBody().accept(this);

                if (!ifStatement.shouldPop())
                    typeCast(bodyType, resultType);
                else
                    pop(bodyType);

                ctx.endScope();
            }

            mv.visitLabel(after);
        } else {
            ctx.reportError("Expected boolean, found: " + type.getClassName(), condition);
        }
    }

    private void typeCast(@NotNull final Type from, @NotNull final Type to) {
        final TypeHandler fromHandler = TypeUtilities.getTypeHandler(from);
        final TypeHandler toHandler = TypeUtilities.getTypeHandler(to);

        toHandler.cast(mv, fromHandler);
    }

    private Type resolveNode(final Node node) {
        if (node instanceof Block) {
            final Block block = (Block) node;
            final List<Node> stmts = block.getStatements();

            if (!stmts.isEmpty())
                return resolveNode(stmts.get(stmts.size() - 1));
        } else if (node instanceof Expression) {
            final Expression expr = (Expression) node;
            return expr.resolveType(ctx.getResolver());
        }

        return Type.VOID_TYPE;
    }

    private void asExpression(final Node node) {
        if (node instanceof Block) {
            final List<Node> stmts = ((Block) node).getStatements();

            if (!stmts.isEmpty())
                asExpression(stmts.get(stmts.size() - 1));
        } else if (node instanceof Expression) {
            ((Expression) node).setPop(false);
        }
    }

    @Override
    public void visitReturn(final Return returnStmt) {
        final Expression expr = returnStmt.getExpression();

        if (ctx.getReturnType() == Type.VOID_TYPE || expr == null) {
            if (ctx.getReturnType() == Type.VOID_TYPE && expr == null) {
                mv.visitInsn(RETURN);
            } else {
                ctx.reportError("Illegal return value", returnStmt.getExpression());
            }
        } else {
            final Type exprType = expr.resolveType(ctx.getResolver());

            if (expr instanceof Literal) {
                final Object val = ((Literal) expr).getValue();

                if (val == null) {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(ARETURN);
                    return;
                }
            }

            if (exprType == null) {
                ctx.reportError("Cannot resolve expression", expr);
                return;
            }

            final Type returnType = ctx.getReturnType();
            final TypeHandler retTypeHandler = getTypeHandler(returnType);

            expr.accept(this);

            if (isAssignableWithImplicitCast(exprType, returnType)) {
                final TypeHandler from = getTypeHandler(exprType);
                retTypeHandler.cast(mv, from);
            } else if (!isAssignableFrom(exprType, returnType) && !exprType.equals(returnType)) {
                ctx.reportError("Expected type: " + returnType.getClassName() + " but" +
                        " got " + exprType.getClassName(), expr);
                return;
            }

            retTypeHandler.ret(mv);
        }
    }

    @Override
    public void visitCall(final Call call) {
        final SymbolContext funCtx = ctx.getResolver().resolveCallCtx(call);

        if (funCtx != null) {
            final List<Expression> expressions = call.getParameters();
            final Type[] argumentTypes = funCtx.getTypeDescriptor().getArgumentTypes();

            if (!funCtx.isStatic()) {
                if (call.getPrecedingExpr() != null) {
                    call.getPrecedingExpr().accept(this);
                } else if (!ctx.isStatic()){
                    final int idx = ctx.getScope().findVar(" __this__ ").getIndex();
                    mv.visitVarInsn(ALOAD, idx);
                } else {
                    ctx.reportError("Cannot access not static method from a static context", call);
                }
            }

            for (int i = 0; i < expressions.size(); i++) {
                expressions.get(i).accept(this);
                final Type exprType = expressions.get(i).resolveType(ctx.getResolver());

                if (isAssignableWithImplicitCast(exprType, argumentTypes[i])) {
                    final TypeHandler from = getTypeHandler(exprType);
                    final TypeHandler to = getTypeHandler(argumentTypes[i]);
                    to.cast(mv, from);
                }
            }

            boolean itf = isInterface(funCtx.getOwner());
            int opcode = funCtx.isStatic() ? INVOKESTATIC : INVOKEVIRTUAL;

            if (itf)
                opcode = INVOKEINTERFACE;

            mv.visitMethodInsn(opcode, funCtx.getOwner(), funCtx.getName(), funCtx.getTypeDescriptor().getDescriptor(), itf);

            if (call.shouldPop() && !funCtx.getTypeDescriptor().getReturnType().equals(Type.VOID_TYPE)) {
                final Type retType = funCtx.getTypeDescriptor().getReturnType();
                if (retType.equals(Type.LONG_TYPE) || retType.equals(Type.DOUBLE_TYPE)) {
                    mv.visitInsn(DUP2);
                } else {
                    mv.visitInsn(DUP);
                }
            }
        } else {
            ctx.reportError("Cannot resolve function", call);
        }
    }

    @Override
    public void visitWhen(final When when) {
        final boolean hasParameter = when.getData() != null;
        final Type whenType = when.resolveType(ctx.getResolver());
        Type parameterType = Type.BOOLEAN_TYPE;

        if (when.getData() != null) {
            parameterType = when.getData().resolveType(ctx.getResolver());

            if (parameterType == null) {
                ctx.reportError("Cannot resolve expression", when.getData());
                return;
            }
        }

        final TypeHandler paramTypeHandler = TypeUtilities.getTypeHandler(parameterType);
        final Label endLabel = new Label();

        if (hasParameter) {
            when.getData().accept(this);
            duplicate(parameterType);
        }

        final List<WhenCase> cases = when.getCases();

        for (int i = 0; i < cases.size(); i++) {
            final WhenCase whenCase = cases.get(i);
            final Type conditionType = whenCase.getCondition().resolveType(ctx.getResolver());
            final Type branchType = resolveNode(whenCase.getBranch());
            final Label nextCaseLabel = new Label();

            if (conditionType == null) {
                ctx.reportError("Cannot resolve expression", whenCase.getCondition());
                return;
            }

            if (!conditionType.equals(parameterType) && !isAssignableFrom(conditionType, parameterType) &&
                    isAssignableWithImplicitCast(conditionType, parameterType)) {
                ctx.reportError("Type: " + conditionType.getClassName() + " cannot be assigned to " +
                        parameterType.getClassName(), whenCase.getCondition());
                return;
            }

            final TypeHandler conditionTypeHandler = TypeUtilities.getTypeHandler(conditionType);
            final Operator eqOperator = Operator.getOperator("==", conditionType, parameterType);

            if (!hasParameter) {
                if (!Type.BOOLEAN_TYPE.equals(conditionType)) {
                    ctx.reportError("Expected type boolean but got " + conditionType.getClassName(),
                            whenCase.getCondition());
                }
            } else if (eqOperator != null) {
                whenCase.getCondition().accept(this);
                eqOperator.applyCmp(mv, nextCaseLabel);
            } else {
                paramTypeHandler.toObject(mv);
                whenCase.getCondition().accept(this);
                conditionTypeHandler.toObject(mv);

                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, nextCaseLabel);
            }

            whenCase.getBranch().accept(this);

            if (whenType != null) {
                typeCast(branchType, whenType);
            }

            mv.visitJumpInsn(GOTO, endLabel);

            mv.visitLabel(nextCaseLabel);

            if (hasParameter && i < cases.size() - 1) {
                duplicate(parameterType);
            }
        }

        final Type elseType = resolveNode(when.getElseBranch());

        if (when.getElseBranch() != null) {
            when.getElseBranch().accept(this);

            if (whenType != null)
                typeCast(elseType, whenType);
        }

        mv.visitLabel(endLabel);

        if (when.shouldPop() && whenType != null) {
            pop(whenType);
        }
    }

    @Override
    public void visitNew(final New newExpr) {
        final List<Expression> parameters = newExpr.getParameters();
        final boolean isArray = newExpr.isArray();

        if (isArray) {
            final Type componentType = ctx.getResolver().resolveType(newExpr.getType());

            if (componentType == null) {
                ctx.reportError("Cannot resolve type: " + newExpr.getType(), newExpr);
            } else {
                final TypeHandler handler = getTypeHandler(componentType);

                for (final Expression parameter : parameters) {
                    final Type parameterType = parameter.resolveType(ctx.getResolver());
                    if (!isAssignableFrom(parameterType, Type.INT_TYPE)) {
                        ctx.reportError("Expected type: int, but got: " + parameterType.getClassName(), parameter);
                        return;
                    }
                    parameter.accept(this);
                }

                if (parameters.isEmpty()) {
                    ctx.reportError("Cannot instantiate 0 dimensional array", newExpr);
                } else if (parameters.size() == 1) {
                    handler.newArray(mv);
                } else {
                    handler.multiNewArray(mv, parameters.size());
                }
                if (newExpr.shouldPop()) {
                    ctx.reportError("Not a statement", newExpr);
                }
            }

        } else {
            final SymbolContext constructorCtx = ctx.getResolver().resolveConstructorCtx(newExpr);

            if (constructorCtx == null) {
                ctx.reportError("Cannot resolve constructor", newExpr);
                return;
            }

            mv.visitTypeInsn(NEW, constructorCtx.getOwner());

            if (!newExpr.shouldPop())
                mv.visitInsn(DUP);

            final Type[] constructorArgTypes = constructorCtx.getTypeDescriptor().getArgumentTypes();
            for (int i = 0; i < constructorArgTypes.length; i++) {
                final TypeHandler to = getTypeHandler(constructorArgTypes[i]);
                final Type paramType = parameters.get(i).resolveType(ctx.getResolver());
                final TypeHandler from = getTypeHandler(paramType);

                parameters.get(i).accept(this);
                to.cast(mv, from);
            }

            mv.visitMethodInsn(INVOKESPECIAL, constructorCtx.getOwner(), constructorCtx.getName(),
                    constructorCtx.getTypeDescriptor().getDescriptor(), false);
        }
    }

    @Override
    public void visitAssignment(final Assignment assignment) {
        final Expression precedingExpr = assignment.getPrecedingExpr();
        final Type valueType = assignment.resolveType(ctx.getResolver());


        if (valueType == null) {
            ctx.reportError("Cannot resolve expression", assignment.getValue());
            return;
        }

        final TypeHandler from = getTypeHandler(valueType);

        final FieldContext fieldCtx = ctx.getResolver().resolveFieldCtx(precedingExpr, assignment.getName());

        if (fieldCtx == null) {
            ctx.reportError("Cannot resolve field: " + assignment.getName(), assignment);
            return;
        }

        if (fieldCtx.isFinal() && !ctx.isInitializer()) {
            ctx.reportError("Cannot assign value to final field", assignment);
            return;
        }

        if (precedingExpr != null && !fieldCtx.isStatic())
            precedingExpr.accept(this);

        final TypeHandler to = getTypeHandler(fieldCtx.getTypeDescriptor());

        assignment.getValue().accept(this);

        if (!assignment.shouldPop()) {
            duplicate(valueType);
        }

        if (!fieldCtx.getTypeDescriptor().equals(valueType) && !isAssignableFrom(valueType, fieldCtx.getTypeDescriptor())) {
            if (isAssignableWithImplicitCast(valueType, fieldCtx.getTypeDescriptor())) {
                to.cast(mv, from);
            } else {
                ctx.reportError("Expected type: " + fieldCtx.getTypeDescriptor().getClassName() + " but got: " +
                        valueType.getClassName(), assignment.getValue());
            }
        }

        if (fieldCtx == ExpressionResolverImpl.ARRAY_LENGTH) {
            ctx.reportError("Cannot modify array length", assignment);
        } else if (fieldCtx.isLocal()) {
            to.store(mv, ctx.getScope().findVar(fieldCtx.getName()).getIndex());
        } else if (fieldCtx.isStatic()) {
            mv.visitFieldInsn(PUTSTATIC, fieldCtx.getOwner(), fieldCtx.getName(), fieldCtx.getTypeDescriptor().getDescriptor());
        } else {
            if (!ctx.isStatic() && assignment.getPrecedingExpr() == null) {
                mv.visitVarInsn(ALOAD, 0); // put this on stack
            } else if (assignment.getPrecedingExpr() != null) {
                assignment.getPrecedingExpr().accept(this);
            } else if (ctx.isStatic()) {
                ctx.reportError("Cannot access non static field: " + assignment.getName() + " from static context",
                        assignment);
            }

            mv.visitFieldInsn(PUTFIELD, fieldCtx.getOwner(), fieldCtx.getName(), fieldCtx.getTypeDescriptor().getDescriptor());
        }
    }

    private void andOperator(final Expression lhs, final Expression rhs, final Type leftType, final Type rightType) {
        if (!leftType.equals(Type.BOOLEAN_TYPE)) {
            ctx.reportError("Expected type: boolean but got: " + rightType.getClassName(), lhs);
        }

        if (!rightType.equals(Type.BOOLEAN_TYPE)) {
            ctx.reportError("Expected type: boolean but got: " + rightType.getClassName(), rhs);
        }

        final Label trueLabel = new Label();
        final Label falseLabel = new Label();

        lhs.accept(this);
        mv.visitJumpInsn(IFEQ, falseLabel);

        rhs.accept(this);
        mv.visitJumpInsn(IFEQ, falseLabel);

        mv.visitInsn(ICONST_1);
        mv.visitJumpInsn(GOTO, trueLabel);

        mv.visitLabel(falseLabel);
        mv.visitInsn(ICONST_0);

        mv.visitLabel(trueLabel);
    }

    private void orOperator(final Expression lhs, final Expression rhs, final Type leftType, final Type rightType) {
        if (!leftType.equals(Type.BOOLEAN_TYPE)) {
            ctx.reportError("Expected type: boolean but got: " + rightType.getClassName(), lhs);
        }

        if (!rightType.equals(Type.BOOLEAN_TYPE)) {
            ctx.reportError("Expected type: boolean but got: " + rightType.getClassName(), rhs);
        }

        final Label trueLabel = new Label();
        final Label endLabel = new Label();
        final Label falseLabel = new Label();

        lhs.accept(this);
        mv.visitJumpInsn(IFNE, trueLabel);

        rhs.accept(this);
        mv.visitJumpInsn(IFNE, trueLabel);

        mv.visitJumpInsn(GOTO, falseLabel);

        mv.visitLabel(trueLabel);
        mv.visitInsn(ICONST_1);
        mv.visitJumpInsn(GOTO, endLabel);

        mv.visitLabel(falseLabel);
        mv.visitInsn(ICONST_0);

        mv.visitLabel(endLabel);
    }

    private int depthCounter = 0;

    private void stringAdd(final Expression lhs, final Expression rhs, final Type leftType, final Type rightType) {
        if (depthCounter == 0) {
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        }

        depthCounter++;

        append(lhs, leftType);
        append(rhs, rightType);

        depthCounter--;

        if (depthCounter == 0) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        }
    }

    private void append(final Expression expr, final Type type) {
        Type typeToAppend = type;

        if (!Type.getType(String.class).equals(type) && isAssignableFrom(type, Type.getType(Object.class))) {
            typeToAppend = Type.getType(Object.class);
        } else if (isAssignableFrom(type, Type.INT_TYPE)) {
            typeToAppend = Type.INT_TYPE;
        }

        final Type appendDesc = Type.getMethodType(Type.getType(StringBuilder.class), typeToAppend);

        expr.accept(this);

        if (!(expr instanceof BinaryOp) || !Type.getType(String.class).equals(type)) {
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDesc.getDescriptor(), false);
        }
    }

    @Override
    public void visitBinaryOp(final BinaryOp binaryOp) {
        final Expression lhs = binaryOp.getLeftSide();
        final Expression rhs = binaryOp.getRightSide();
        final Type leftType = lhs.resolveType(ctx.getResolver());
        final Type rightType = rhs.resolveType(ctx.getResolver());

        if (leftType == null) {
            ctx.reportError("Cannot resolve expression", binaryOp.getLeftSide());
            return;
        }

        if (rightType == null) {
            ctx.reportError("Cannot resolve expression", binaryOp.getRightSide());
            return;
        }

        final Type stringType = Type.getType(String.class);

        if (binaryOp.getOperator().equals("&&")) {
            andOperator(lhs, rhs, leftType, rightType);
            return;
        } else if (binaryOp.getOperator().equals("||")) {
            orOperator(lhs, rhs, leftType, rightType);
            return;
        } else if ((isAssignableFrom(leftType, stringType) || isAssignableFrom(rightType, stringType)) &&
                "+".equals(binaryOp.getOperator())) {
            stringAdd(lhs, rhs, leftType, rightType);
            return;
        }

        final Operator op = Operator.getOperator(binaryOp.getOperator(), leftType, rightType);

        if (op == null) {
            ctx.reportError("No such operator (" + leftType.getClassName() + " " + binaryOp.getOperator() +
                    " " + rightType.getClassName() + ")", binaryOp);
        } else {

            lhs.accept(this);

            if (!op.getLhs().equals(op.getResultType()) && isAssignableWithImplicitCast(op.getLhs(), op.getRhs())) {
                final TypeHandler to = getTypeHandler(rightType);
                final TypeHandler from = getTypeHandler(leftType);
                to.cast(mv, from);
                // lhs must be cast
            }

            rhs.accept(this);

            if (!op.getRhs().equals(op.getResultType()) && isAssignableWithImplicitCast(op.getRhs(), op.getLhs())) {
                final TypeHandler to = getTypeHandler(leftType);
                final TypeHandler from = getTypeHandler(rightType);
                to.cast(mv, from);
                // rhs must be cast
            }

            op.apply(mv);

            if (binaryOp.shouldPop()) {
                final Type opType = op.getResultType();
                if (opType.equals(Type.LONG_TYPE) || opType.equals(Type.DOUBLE_TYPE)) {
                    mv.visitInsn(DUP2);
                } else {
                    mv.visitInsn(DUP);
                }
            }
        }
    }

    @Override
    public void visitLiteral(final Literal literal) {
        final Type type = literal.resolveType(ctx.getResolver());

        if (literal.getValue() == null) {
            mv.visitInsn(ACONST_NULL);
        } else if (type != null) {
            final Object value = literal.getValue();
            if (value instanceof Long) {
                long val = (Long) value;
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    pushInteger(((Long) value).intValue());
                } else {
                    mv.visitLdcInsn(value);
                }
            } else if (value instanceof Float) {
                pushFloat((Float) value);
            } else if (value instanceof Double) {
                pushDouble((Double) value);
            } else if (value instanceof Boolean) {
                pushInteger((Boolean) value ? 1 : 0);
            } else if (value instanceof String) {
                pushString(literal);
            } else {
                mv.visitLdcInsn(value);
            }
        } else {
            System.err.println("Invalid literal type");
        }
    }

    private void pushString(final Literal<String> value) {
        final String str = value.getValue();
        final Pattern identifierPattern = Pattern.compile("\\$[a-zA-Z_][a-zA-Z_0-9]*");
        final Matcher matcher = identifierPattern.matcher(str);
        final List<Expression> expressions = new ArrayList<>();

        int index = 0;

        while (matcher.find()) {
            final int end = matcher.end();
            final int start = matcher.start();
            final Literal<String> lhs = new Literal<>(str.substring(index, start));

            lhs.setText(str.substring(index, start));
            lhs.setLineNumber(value.getLineNumber());
            lhs.setFile(value.getFile());
            lhs.setParent(value);

            if (start - index > 0) {
                expressions.add(lhs);
            }

            final QualifiedName id = QualifiedName.of(str.substring(matcher.start() + 1, end));
            id.setText(str.substring(matcher.start(), end));
            id.setLineNumber(value.getLineNumber());
            id.setFile(value.getFile());
            id.setParent(value);

            expressions.add(id);

            index = end;
        }

        if (index < str.length()) {
            final Literal<String> id = new Literal<>(str.substring(index));

            id.setLineNumber(value.getLineNumber());
            id.setText(str.substring(index));
            id.setFile(value.getFile());
            id.setParent(value);

            expressions.add(id);
        }

        if (expressions.size() < 2) {
            mv.visitLdcInsn(str);
        } else {
            final Expression bop = addExpressions(expressions);
            bop.accept(this);
        }
    }

    private Expression addExpressions(final List<Expression> expressions) {
        final int len = expressions.size();

        if (len == 2) {
            return new BinaryOp(expressions.get(0), "+", expressions.get(1));
        } else if (len == 1) {
            return expressions.get(0);
        }

        final Expression lhs = addExpressions(expressions.subList(0, len / 2));
        final Expression rhs = addExpressions(expressions.subList(len / 2, len));

        return addExpressions(Arrays.asList(lhs, rhs));
    }

    @Override
    public void visitExpressionIndices(final ExpressionIndex expressionIndex) {
        final Type resultType = expressionIndex.resolveType(ctx.getResolver());
        final Expression value = expressionIndex.getValue();

        if (resultType == null) {
            ctx.reportError("Cannot resolve expression", expressionIndex);
        } else {
            if (value != null) {
                visitAssignIdx(expressionIndex.getExpression(), resultType, expressionIndex.getIndices(), value,
                        !expressionIndex.shouldPop());
            } else {
                if (expressionIndex.shouldPop()) {
                    ctx.reportError("Not a statement", expressionIndex);
                } else {
                    visitIndex(expressionIndex.getExpression(), expressionIndex.getIndices());
                }
            }
        }
    }

    private void visitAssignIdx(final Expression array, final Type resultType, final List<Expression> indices,
                                final Expression value, final boolean duplicate) {
        final Type valueType = value.resolveType(ctx.getResolver());

        if (!valueType.equals(resultType) && !isAssignableFrom(valueType, resultType)) {
            ctx.reportError("Expected type: " + resultType.getClassName() + " but got: " + valueType.getClassName(), value);
            return;
        }

        array.accept(this);

        final TypeHandler arrayHandler = new AnyTypeHandler("LJava/lang/Object;");

        for (int i = 0; i + 1 < indices.size(); i++) {
            final Expression index = indices.get(i);
            final Type indexType = index.resolveType(ctx.getResolver());

            if (indexType == null) {
                ctx.reportError("Cannot resolve expression", index);
            } else if (!isAssignableFrom(indexType, Type.INT_TYPE)) {
                ctx.reportError("Expected type: int, but got: " + indexType.getClassName(), index);
            }

            index.accept(this);
            arrayHandler.arrayLoad(mv);
        }

        final Expression lastIndex = indices.get(indices.size() - 1);
        final Type lastIndexType = lastIndex.resolveType(ctx.getResolver());

        if (lastIndexType == null) {
            ctx.reportError("Cannot resolve expression", lastIndex);
        } else if (!isAssignableFrom(lastIndexType, Type.INT_TYPE)) {
            ctx.reportError("Expected type: int, but got: " + lastIndexType.getClassName(), lastIndex);
        } else {
            lastIndex.accept(this);

            value.accept(this);

            if (duplicate) {
                if (valueType.equals(Type.LONG_TYPE) || valueType.equals(Type.DOUBLE_TYPE)) {
                    mv.visitInsn(DUP2);
                } else {
                    mv.visitInsn(DUP);
                }
            }

            if (getDim(resultType) > indices.size()) {
                arrayHandler.arrayStore(mv);
            } else {
                getTypeHandler(resultType).arrayStore(mv);
            }
        }
    }

    private void duplicate(final Type type) {
        if (Type.LONG_TYPE.equals(type) || Type.DOUBLE_TYPE.equals(type)) {
            mv.visitInsn(DUP2);
        } else if (!Type.VOID_TYPE.equals(type)) {
            mv.visitInsn(DUP);
        }
    }

    private void pop(@NotNull final Type type) {
        if (Type.LONG_TYPE.equals(type) || Type.DOUBLE_TYPE.equals(type)) {
            mv.visitInsn(POP2);
        } else if (!Type.VOID_TYPE.equals(type)) {
            mv.visitInsn(POP);
        }
    }

    private void visitIndex(final Expression lstArrayOrMap, final List<Expression> indices) {
        final Type exprType = lstArrayOrMap.resolveType(ctx.getResolver());
        if (exprType.getDescriptor().startsWith("[")) {
            visitArrayIndex(lstArrayOrMap, exprType, indices);
        } else {
            ctx.reportError("Expected array type but got: " + exprType.getClassName(), lstArrayOrMap);
        }
    }

    private void visitArrayIndex(final Expression array, final Type exprType, final List<Expression> indices) {
        array.accept(this);

        final TypeHandler arrayHandler = new AnyTypeHandler("LJava/lang/Object;");

        for (int i = 0; i + 1 < indices.size(); i++) {
            final Expression index = indices.get(i);
            final Type indexType = index.resolveType(ctx.getResolver());

            if (indexType == null) {
                ctx.reportError("Cannot resolve expression", index);
            } else if (!isAssignableFrom(indexType, Type.INT_TYPE)) {
                ctx.reportError("Expected type: int, but got: " + indexType.getClassName(), index);
            }

            index.accept(this);
            arrayHandler.arrayLoad(mv);
        }

        final Expression lastIndex = indices.get(indices.size() - 1);
        final Type lastIndexType = lastIndex.resolveType(ctx.getResolver());

        if (lastIndexType == null) {
            ctx.reportError("Cannot resolve expression", lastIndex);
        } else if (!isAssignableFrom(lastIndexType, Type.INT_TYPE)) {
            ctx.reportError("Expected type: int, but got: " + lastIndexType.getClassName(), lastIndex);
        } else {
            lastIndex.accept(this);

            if (getDim(exprType) > indices.size()) {
                arrayHandler.arrayLoad(mv);
            } else {
                getTypeHandler(exprType.getElementType()).arrayLoad(mv);
            }
        }
    }

    @Override
    public void visitTypeCast(final TypeCast cast) {
        final Type type = cast.resolveType(ctx.getResolver());
        final Type exprType = cast.getExpression().resolveType(ctx.getResolver());

        if (type == null) {
            ctx.reportError("Cannot resolve type: " + cast.getType(), cast.getType());
        } else if (exprType == null) {
            ctx.reportError("Cannot resolve type: " + cast.getType(), cast.getExpression());
        } else {
            cast.getExpression().accept(this);
            final TypeHandler to = getTypeHandler(type);
            final TypeHandler from = getTypeHandler(exprType);

            if (!to.cast(mv, from)) {
                if (isAssignableFrom(type, exprType)) {
                    mv.visitTypeInsn(CHECKCAST, to.getInternalName());
                } else {
                    ctx.reportError("Cast can never succeed from type: " + exprType.getClassName() + " to type: " +
                            type.getClassName(), cast);
                }
            }
        }
    }

    private void loadField(final FieldContext fieldCtx) {
        final Type type = fieldCtx.getTypeDescriptor();
        final TypeHandler handler = TypeUtilities.getTypeHandler(type);

        if (fieldCtx == ExpressionResolverImpl.ARRAY_LENGTH) {
            mv.visitInsn(ARRAYLENGTH);
        } else if (fieldCtx.isLocal()) {
            handler.load(mv, ctx.getScope().findVar(fieldCtx.getName()).getIndex());
        } else if (fieldCtx.isStatic()) {
            mv.visitFieldInsn(GETSTATIC, fieldCtx.getOwner(), fieldCtx.getName(), fieldCtx.getTypeDescriptor().getDescriptor());
        } else {
            mv.visitFieldInsn(GETFIELD, fieldCtx.getOwner(), fieldCtx.getName(), fieldCtx.getTypeDescriptor().getDescriptor());
        }
    }

    @Override
    public void visitExpressionFieldAccess(final ExpressionFieldAccess fa) {
        final Type precedingType = fa.getPrecedingExpr().resolveType(ctx.getResolver());
        final FieldContext fieldCtx = ctx.getResolver().resolveFieldCtx(fa.getPrecedingExpr(), fa.getFieldName());

        if (precedingType == null) {
            ctx.reportError("Cannot resolve expression", fa.getPrecedingExpr());
            return;
        }

        if (fieldCtx == null) {
            ctx.reportError("Cannot resolve field: " + fa.getFieldName(), fa);
            return;
        }

        fa.getPrecedingExpr().accept(this);
        loadField(fieldCtx);
    }

    @Override
    public void visitName(final QualifiedName name) {
        final FieldContext[] ctxList = ctx.getResolver().resolveFieldCtx(name);

        if (ctxList != null) {
            for (final FieldContext fieldCtx : ctxList) {
                loadField(fieldCtx);
            }
        } else {
            ctx.reportError("Cannot resolve field: " + name, name);
        }
    }

    @Override
    public void visitUnaryOp(final UnaryOp unaryOp) {
        final Expression expr = unaryOp.getExpression();
        final Type type = expr.resolveType(ctx.getResolver());

        if (type == null) {
            ctx.reportError("Cannot resolve expression", expr);
            return;
        }

        switch (unaryOp.getOperator()) {
            case "-":
                new BinaryOp(new Literal<>(0), "-", expr).accept(this);
                return;
            case "+":
                expr.accept(this);
                return;
            case "!":
                expr.accept(this);
                if (type.equals(Type.BOOLEAN_TYPE)) {
                    final Label after = new Label();
                    final Label falseLabel = new Label();

                    mv.visitJumpInsn(IFEQ, falseLabel);

                    mv.visitInsn(ICONST_0);
                    mv.visitJumpInsn(GOTO, after);

                    mv.visitLabel(falseLabel);

                    mv.visitInsn(ICONST_1);

                    mv.visitLabel(after);
                    return;
                }
        }

        ctx.reportError("No such operator: (" + unaryOp.getOperator() + type.getClassName() + ")", unaryOp);
    }

    public void pushFloat(final float value) {
        if (value == 0f) {
            mv.visitInsn(FCONST_0);
        } else if (value == 1f) {
            mv.visitInsn(FCONST_1);
        } else if (value == 2f) {
            mv.visitInsn(FCONST_2);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public void pushDouble(final double value) {
        if (value == 0D) {
            mv.visitInsn(DCONST_0);
        } else if (value == 1D) {
            mv.visitInsn(DCONST_1);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public void pushInteger(final int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(ICONST_M1 + value + 1);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }
}
