package org.bw.tl.compiler;

import lombok.Data;
import org.bw.tl.Error;
import org.bw.tl.ErrorType;
import org.bw.tl.antlr.ast.*;
import org.bw.tl.compiler.resolve.ExpressionResolver;
import org.bw.tl.compiler.resolve.ExpressionResolverImpl;
import org.bw.tl.compiler.resolve.SymbolResolver;
import org.objectweb.asm.Type;

import java.util.LinkedList;
import java.util.List;

public @Data class MethodCtx {

    private final List<Error> errors = new LinkedList<>();
    private final Scope scope = new Scope();
    private ExpressionResolver resolver;
    private SymbolResolver symbolResolver;
    private final List<Module> classPath;
    private final Function function;
    private final Module module;
    private final File file;

    /**
     * Returns the expression resolver for the file that defined this method.
     * The expression resolver is capable of resolving expressions of any type
     * assuming it has been imported in the file that defined this method or
     * it is fully qualified
     *
     * @return The expression resolver for this file
     */
    public ExpressionResolver getResolver() {
        if (resolver == null) {
            resolver = new ExpressionResolverImpl(getSymbolResolver(), module, file, scope);
        }
        return resolver;
    }

    /**
     * Returns the symbol resolver for file that defined this method.
     * The resolver is capable of resolving any type that has been imported
     * in the file that defined this method or it is fully qualified
     *
     * @return The symbol resolver for this file
     */
    public SymbolResolver getSymbolResolver() {
        if (symbolResolver == null) {
            symbolResolver = new SymbolResolver(classPath, module, file);
        }
        return symbolResolver;
    }

    /**
     * Resolves the return type of this method
     *
     * @return The type descriptor of the return type if found, otherwise null
     */
    public Type getReturnType() {
        return getSymbolResolver().resolveType(function.getType());
    }

    /**
     * Resolves a type descriptor given the contextual information.
     * The typename does not need to be fully qualified if the type
     * is imported
     *
     * @param name The typename
     * @return The type descriptor if the type is found, otherwise null
     */
    public Type resolveType(final QualifiedName name) {
        return getSymbolResolver().resolveType(name);
    }

    /**
     *
     * @return True if the method is responsible for initializing the class
     */
    public boolean isInitializer() {
        return getMethodName().equals("<clinit>");
    }

    /**
     *
     * @return True if the method is synthetic (generated by the compiler)
     */
    public boolean isSynthetic() {
        return file == null;
    }

    /**
     * Begin a new variable score. A new scope should be created a the beginning of every
     * block so that variables can not be referenced after the end of the frame
     */
    public void beginScope() {
        scope.beginScope();
    }

    /**
     * Begin a new variable score. A new scope should be created at the end of every
     * block so that variables can not be referenced after the end of the frame
     */
    public void endScope() {
        scope.endScope();
    }

    /**
     * Report an unknown error to the compiler
     *
     * @param node The node that caused the error
     */
    public void reportError(final Node node) {
        reportError("", node);
    }

    /**
     * Report a general error to the compiler
     *
     * @param message The reason the error occurred
     * @param node The node that caused the error
     */
    public void reportError(final String message, final Node node) {
        reportError(ErrorType.GENERAL_ERROR, message, node);
    }

    /**
     * Report an error of specified type to the compiler
     *
     * @param errorType The type of error
     * @param message The reason the error occurred
     * @param node The node that caused the error
     */
    public void reportError(final ErrorType errorType, final String message, final Node node) {
        errors.add(errorType.newError(message, node));
    }

    /**
     * Detect if this method is a main method
     *
     * @return True if this method a valid program entry point
     */
    public boolean isMain() {
        return getMethodName().equals("main") && function.getType().toString().equals("void") &&
                function.getParameterTypes().length == 1 &&
                function.getParameterTypes()[0].getDesc().equals(Type.getType(String[].class).getDescriptor());
    }

    /**
     *
     * @return True if the method is static
     */
    public boolean isStatic() {
        return true;
    }

    /**
     *
     * @return The name of the method
     */
    public String getMethodName() {
        return function.getName();
    }

    /**
     *
     * @return The internal name of the class that contains this method
     */
    public String getInternalClassName() {
        return module.getModulePackage().toString();
    }
}
