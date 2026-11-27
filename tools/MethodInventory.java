import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Syntactic inventory of methods and constructors, not a semantic dead-code proof. */
public class MethodInventory {
    static String csv(Object value){return "\""+String.valueOf(value).replace("\"","\"\"").replace("\n"," ").replace("\r"," ")+"\"";}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath();
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=compiler.getStandardFileManager(null,null,null);var paths=Files.walk(root)){
            var javaFiles=paths.filter(p->p.toString().endsWith(".java")).sorted().toList();
            JavacTask task=(JavacTask)compiler.getTask(null,files,null,List.of("-proc:none"),null,files.getJavaFileObjectsFromPaths(javaFiles));
            var pos=Trees.instance(task).getSourcePositions();
            System.out.println("archivo,tipo,firma,linea_inicio,linea_fin,modificadores,invocaciones_sintacticas,clasificacion");
            for(CompilationUnitTree unit:task.parse()){
                var lines=unit.getLineMap();var path=root.relativize(Path.of(unit.getSourceFile().toUri()));
                new TreePathScanner<Void,Void>(){
                    final Deque<String> names=new ArrayDeque<>();
                    @Override public Void visitClass(ClassTree node,Void unused){names.addLast(node.getSimpleName().toString());try{return super.visitClass(node,unused);}finally{names.removeLast();}}
                    @Override public Void visitMethod(MethodTree node,Void unused){
                        var calls=new TreeSet<String>();
                        if(node.getBody()!=null)new TreeScanner<Void,Void>(){
                            @Override public Void visitMethodInvocation(MethodInvocationTree call,Void none){calls.add(call.getMethodSelect().toString());return super.visitMethodInvocation(call,none);}
                            @Override public Void visitNewClass(NewClassTree call,Void none){calls.add("new "+call.getIdentifier());return super.visitNewClass(call,none);}
                        }.scan(node.getBody(),null);
                        var start=pos.getStartPosition(unit,node);var end=pos.getEndPosition(unit,node);
                        String sig=node.getName()+"("+String.join(",",node.getParameters().stream().map(v->v.getType()+" "+v.getName()).toList())+")";
                        System.out.println(String.join(",",csv(path),csv(String.join(".",names)),csv(sig),csv(lines.getLineNumber(start)),csv(lines.getLineNumber(Math.max(start,end-1))),csv(node.getModifiers().getFlags()),csv(String.join("; ",calls)),csv("INVENTARIADO; revisión semántica y equivalencia pendientes")));
                        return super.visitMethod(node,unused);
                    }
                }.scan(unit,null);
            }
        }
    }
}
