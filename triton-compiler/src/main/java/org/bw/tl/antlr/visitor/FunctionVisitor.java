package org.bw.tl.antlr.visitor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bw.tl.antlr.GrammarBaseVisitor;
import org.bw.tl.antlr.GrammarParser;
import org.bw.tl.antlr.ast.*;

@RequiredArgsConstructor(staticName = "of")
public class FunctionVisitor extends GrammarBaseVisitor<Function> {

    private final @Getter String sourceFile;

    @Override
    public Function visitFunctionDef(final GrammarParser.FunctionDefContext ctx) {
        final TypeName type = ctx.type() != null ? TypeName.of(ctx.type().getText()) :
                TypeName.of("void");

        final String name = ctx.IDENTIFIER().getText();
        final Block body;

        if (ctx.block() != null) {
            body = ctx.block().accept(BlockVisitor.of(sourceFile));
        } else {
            body = new Block(new Return(ctx.expression().accept(ExpressionVisitor.of(sourceFile))));
        }

        TypeName[] paramTypes = new TypeName[0];
        String[] paramNames = new String[0];

        if (ctx.functionParamDefs() != null) {
            paramTypes = ctx.functionParamDefs().functionParam().stream()
                    .map(p -> TypeName.of(p.type().getText())).toArray(TypeName[]::new);
            paramNames = ctx.functionParamDefs().functionParam().stream()
                    .map(p -> p.IDENTIFIER().getText()).toArray(String[]::new);
        }

        final Function function = new Function(paramTypes, paramNames, name, body, type);

        if (ctx.modifierList() != null && ctx.modifierList().modifier() != null) {
            for (final GrammarParser.ModifierContext modCtx : ctx.modifierList().modifier()) {
                function.addModifiers(modCtx.accept(new ModifierVisitor()));
            }
        }

        body.setParent(function);

        function.setText(ctx.getText());
        function.setFile(sourceFile);
        function.setLineNumber(ctx.start.getLine());

        return function;
    }
}