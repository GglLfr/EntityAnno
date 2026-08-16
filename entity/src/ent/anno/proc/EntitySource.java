package ent.anno.proc;

import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.*;
import ent.anno.*;

import java.io.*;
import java.util.*;

import static ent.anno.BaseProcessor.*;
import static javax.lang.model.element.ElementKind.*;
import static javax.lang.model.element.Modifier.*;

public class EntitySource implements Serializable{
    public final List<String> imports = new ArrayList<>();
    public final Map<String, String> varInitializers = new HashMap<>();
    public final Map<String, MethodBlock> methodBlocks = new HashMap<>();

    public EntitySource(BaseProcessor proc, ClassSymbol comp) throws ReprocessedNotRecompiledException{
        var path = proc.trees.getPath(comp);
        if(path == null) throw new ReprocessedNotRecompiledException();

        for(var i : path.getCompilationUnit().getImports()){
            imports.add(i.toString());
        }

        for(var s : comp.getEnclosedElements()){
            if(s.getKind() == FIELD){
                var tree = (JCVariableDecl)proc.trees.getTree(s);
                if(tree == null) continue;

                var init = tree.init;
                if(init != null) varInitializers.put(desc(s), init.toString());
            }else if(s.getKind() == METHOD){
                var m = (MethodSymbol)s;
                if(isAny(m, ABSTRACT, NATIVE)) continue;

                var tree = proc.trees.getTree(m);
                if(tree == null) continue;

                methodBlocks.put(desc(m), new MethodBlock(tree.body));
            }
        }
    }

    public static class MethodBlock implements Serializable{
        public final String block;
        public final List<Long> returns = new ArrayList<>();

        public MethodBlock(JCBlock block){
            var writer = new StringWriter();
            new Pretty(writer, true){
                int innerLevel = 0;

                @Override
                public void visitClassDef(JCClassDecl tree){
                    innerLevel++;
                    super.visitClassDef(tree);
                    innerLevel--;
                }

                @Override
                public void visitLambda(JCLambda tree){
                    innerLevel++;
                    super.visitLambda(tree);
                    innerLevel--;
                }

                @Override
                public void visitReturn(JCReturn tree){
                    if(innerLevel > 0){
                        super.visitReturn(tree);
                    }else{
                        long start = writer.getBuffer().length();
                        super.visitReturn(tree);
                        long end = writer.getBuffer().length();

                        returns.add(start | (end << 32));
                    }
                }
            }.visitBlock(block);

            this.block = writer.toString();
        }

        public String substitute(String blockName){
            if(blockName == null) return block.replaceAll("this\\.<(.*)>self\\(\\)", "this")
                .replaceAll("self\\(\\)(?!\\s+instanceof)", "this")
                .replace(" yield ", "")
                .replaceAll("/\\*missing\\*/", "var");

            var builder = new StringBuilder();
            int last = 0;
            for(var ret : returns){
                int start = ret.intValue();
                int end = (int)(ret >>> 32);

                builder.append(block, last, start).append("break ").append(blockName).append(";");
                last = end;
            }

            return builder.append(block, last, block.length()).toString()
                .replaceAll("this\\.<(.*)>self\\(\\)", "this")
                .replaceAll("self\\(\\)(?!\\s+instanceof)", "this")
                .replace(" yield ", "")
                .replaceAll("/\\*missing\\*/", "var");
        }
    }

    public static class ReprocessedNotRecompiledException extends Exception{
    }
}
