import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Counts syntactic method complexity and exact AST body duplication candidates.
 * This intentionally does not attempt a semantic clone or dead-code analysis. */
public final class SourceQuality {
    private static String csv(Object v) { return "\""+String.valueOf(v).replace("\"","\"\"").replace("\n"," ").replace("\r"," ")+"\""; }
    private static String hash(String s) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static final class Decisions extends TreeScanner<Void,Void> {
        int score=1, statements=0;
        @Override public Void scan(Tree tree,Void unused) {
            if(tree==null)return null;
            if(tree instanceof StatementTree && !(tree instanceof BlockTree) && !(tree instanceof ClassTree))statements++;
            switch(tree.getKind()) {
                case IF, FOR_LOOP, ENHANCED_FOR_LOOP, WHILE_LOOP, DO_WHILE_LOOP, CATCH, CONDITIONAL_EXPRESSION, CONDITIONAL_AND, CONDITIONAL_OR -> score++;
                default -> {}
            }
            return super.scan(tree,unused);
        }
        @Override public Void visitCase(CaseTree node,Void unused){
            if(node.getLabels().stream().anyMatch(label->label.getKind()!=Tree.Kind.DEFAULT_CASE_LABEL))score++;
            return super.visitCase(node,unused);
        }
        // Nested/local/anonymous classes have separate method entries.
        @Override public Void visitClass(ClassTree node,Void unused){return null;}
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        try(var fm=ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,StandardCharsets.UTF_8);var paths=Files.walk(root)) {
            var sources=paths.filter(p->p.toString().endsWith(".java")).sorted().toList();
            var task=(JavacTask)ToolProvider.getSystemJavaCompiler().getTask(null,fm,null,List.of("-proc:none"),null,fm.getJavaFileObjectsFromPaths(sources));
            var positions=Trees.instance(task).getSourcePositions();
            System.out.println("file,class,method,start,end,decision_score,statement_nodes,exact_body_sha256");
            for(var unit:task.parse()) {
                Path source=root.relativize(Path.of(unit.getSourceFile().toUri()));
                new TreePathScanner<Void,Void>() {
                    final Deque<String> names=new ArrayDeque<>();
                    @Override public Void visitClass(ClassTree node,Void unused){names.addLast(node.getSimpleName().toString());try{return super.visitClass(node,unused);}finally{names.removeLast();}}
                    @Override public Void visitMethod(MethodTree node,Void unused){
                        if(node.getBody()!=null){
                            long start=positions.getStartPosition(unit,node),end=positions.getEndPosition(unit,node);
                            var decisions=new Decisions();decisions.scan(node.getBody(),null);
                            String method=node.getName()+"("+String.join(",",node.getParameters().stream().map(v->v.getType().toString()).toList())+")";
                            String normalized=node.getBody().toString();
                            String duplicateHash=decisions.statements>=8?hash(normalized):"";
                            System.out.println(String.join(",",csv(source),csv(String.join(".",names)),csv(method),csv(unit.getLineMap().getLineNumber(start)),csv(unit.getLineMap().getLineNumber(Math.max(start,end-1))),csv(decisions.score),csv(decisions.statements),csv(duplicateHash)));
                        }
                        return super.visitMethod(node,unused);
                    }
                }.scan(unit,null);
            }
        }
    }
}
