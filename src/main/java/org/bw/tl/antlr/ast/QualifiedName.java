package org.bw.tl.antlr.ast;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bw.tl.compiler.resolve.ExpressionResolver;
import org.bw.tl.compiler.types.Type;
import org.jetbrains.annotations.NotNull;

@EqualsAndHashCode(callSuper = false)
public @Data class QualifiedName extends Expression {

    @NotNull
    private final String[] names;

    @NotNull
    public QualifiedName append(@NotNull final String identifier) {
        final String[] names = new String[this.names.length + 1];
        System.arraycopy(this.names, 0, names, 0, this.names.length);
        names[this.names.length] = identifier;
        return new QualifiedName(names);
    }

    /**
     * Remove the last name from this qualified name
     * Example a.b.c.d ---> a.b.c
     *
     * @return
     */
    @NotNull
    public QualifiedName removeLast() {
        if (names.length < 1)
            return new QualifiedName(new String[0]);

        final String[] names = new String[this.names.length - 1];
        System.arraycopy(this.names, 0, names, 0, this.names.length - 1);
        return new QualifiedName(names);
    }

    @Override
    public void accept(final ASTVisitor visitor) {
        visitor.visitName(this);
    }

    @Override
    public Type resolveType(final ExpressionResolver resolver) {
        return resolver.resolveName(this);
    }
}
