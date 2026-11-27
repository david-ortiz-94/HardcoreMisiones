import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compiler-parsed inventory for structural matching. Identical bodies are not
 * a semantic equivalence claim: binding, dependencies and callers may differ. */
public final class MethodEquivalence {
    private static String csv(Object v){return "\""+String.valueOf(v).replace("\"","\"\"").replace("\n"," ").replace("\r"," ")+"\"";}
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath();
        try(var fm=ToolProvider.getSystemJavaCompiler().getStandardFileManager(null,null,StandardCharsets.UTF_8);var paths=Files.walk(root)){
            var files=paths.filter(p->p.toString().endsWith(".java")).sorted().toList();
            var task=(JavacTask)ToolProvider.getSystemJavaCompiler().getTask(null,fm,null,List.of("-proc:none"),null,fm.getJavaFileObjectsFromPaths(files));
            var positions=Trees.instance(task).getSourcePositions();
            System.out.println("file,class,signature,line,body_sha256,kind");
            for(var unit:task.parse()){
                String source=root.relativize(Path.of(unit.getSourceFile().toUri())).toString();
                new TreePathScanner<Void,Void>(){
                    final Deque<String> names=new ArrayDeque<>();
                    @Override public Void visitClass(ClassTree node,Void unused){names.addLast(node.getSimpleName().toString());try{return super.visitClass(node,unused);}finally{names.removeLast();}}
                    @Override public Void visitMethod(MethodTree node,Void unused){
                        try{
                            String signature=node.getName()+"("+String.join(",",node.getParameters().stream().map(p->p.getType().toString()).toList())+")";
                            String kind=node.getBody()==null?"DECLARACION":"CONCRETO";
                            String h=node.getBody()==null?"":hash(node.getBody().toString());
                            long line=unit.getLineMap().getLineNumber(positions.getStartPosition(unit,node));
                            System.out.println(String.join(",",csv(source),csv(String.join(".",names)),csv(signature),csv(line),csv(h),csv(kind)));
                        }catch(Exception e){throw new IllegalStateException(e);}
                        return super.visitMethod(node,unused);
                    }
                }.scan(unit,null);
            }
        }
    }
}
