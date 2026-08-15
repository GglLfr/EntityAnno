package ent.anno;

import arc.func.*;
import arc.struct.*;
import arc.util.*;
import com.squareup.javapoet.*;
import com.sun.source.tree.*;
import com.sun.tools.javac.api.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Attribute.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.model.*;

import javax.annotation.processing.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic.*;
import java.io.*;
import java.lang.Class;
import java.lang.annotation.*;
import java.util.*;
import java.util.regex.*;

/**
 * @author GlFolker
 * @author Anuke
 */
@SuppressWarnings("unchecked")
public abstract class BaseProcessor implements Processor{
    private static final ObjectMap<Element, ObjectMap<Class<? extends Annotation>, Annotation>> annotations = new ObjectMap<>();

    public String modName;
    public String packageName;
    public String packageFetch;

    public Filer filer;
    public Messager messager;

    public JavacElements elements;
    public JavacTypes types;
    public JavacTrees trees;

    public RoundEnvironment roundEnv;

    protected int round = 0, rounds = 1;
    protected long initTime;

    protected static Pattern genStrip;

    @Override
    public synchronized void init(ProcessingEnvironment env){
        modName = env.getOptions().get("modName");
        if(modName == null) throw new IllegalStateException("`modName` not supplied!");

        packageName = env.getOptions().get("genPackage");
        if(packageName == null) throw new IllegalStateException("`genPackage` not supplied!");
        genStrip = Pattern.compile(packageName.replace(".", "\\.") + "\\.[^A-Z]*");

        packageFetch = env.getOptions().get("fetchPackage");
        if(packageFetch == null) throw new IllegalStateException("`fetchPackage` not supplied!");

        filer = env.getFiler();
        messager = env.getMessager();
        elements = (JavacElements)env.getElementUtils();
        types = (JavacTypes)env.getTypeUtils();
        trees = JavacTrees.instance(env);

        initTime = Time.millis();
        messager.printMessage(Kind.NOTE, String.format("%s started.", getClass().getSimpleName()));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv){
        this.roundEnv = roundEnv;

        if(round++ < rounds){
            try{
                process();
            }catch(IOException e){
                messager.printMessage(Kind.ERROR, Strings.getFinalMessage(e));
            }
        }

        if(roundEnv.processingOver())
            messager.printMessage(Kind.NOTE, String.format("Time taken for %s: %sms", getClass().getSimpleName(), Time.millis() - initTime));

        return round >= rounds;
    }

    protected void process() throws IOException{
    }

    protected void write(TypeSpec.Builder builder, Seq<String> imports) throws IOException{
        builder.superinterfaces.sort(Structs.comparing(TypeName::toString));
        builder.methodSpecs.sort(Structs.comparing(MethodSpec::toString));
        builder.fieldSpecs.sort(Structs.comparing(f -> f.name));

        var file = JavaFile.builder(packageName, builder.build())
                       .indent("    ")
                       .skipJavaLangImports(true)
                       .build();

        if(imports == null || imports.isEmpty()){
            file.writeTo(filer);
        }else{
            imports = imports.map(m -> Seq.with(m.split("\n")).sort().toString("\n"));
            imports.sort().distinct();

            var rawSource = file.toString();
            Seq<String> result = new Seq<>();
            for(var s : rawSource.split("\n", -1)){
                result.add(s);
                if(s.startsWith("package ")){
                    result.add("");
                    for(var i : imports) result.add(i);
                }
            }

            var object = filer.createSourceFile(file.packageName + "." + file.typeSpec.name, file.typeSpec.originatingElements.toArray(new Element[0]));
            try(var stream = object.openWriter()){
                stream.write(result.toString("\n"));
            }
        }
    }

    public void err(String message){
        messager.printMessage(Kind.ERROR, message);
    }

    public void err(String message, Element elem){
        messager.printMessage(Kind.ERROR, message, elem);
    }

    public Seq<String> imports(Element e){
        Seq<String> out = new Seq<>();
        for(ImportTree t : trees.getPath(e).getCompilationUnit().getImports()) out.add(t.toString());
        return out;
    }

    public <T extends Symbol> Set<T> with(Class<? extends Annotation> annotation){
        return (Set<T>)roundEnv.getElementsAnnotatedWith(annotation);
    }

    public boolean instanceOf(String type, String other){
        var a = elements.getTypeElement(type);
        var b = elements.getTypeElement(other);
        return a != null && b != null && types.isSubtype(a.type, b.type);
    }

    public boolean same(TypeMirror a, TypeMirror b){
        return types.isSameType(a, b);
    }

    public boolean same(TypeMirror a, TypeElement b){
        return same(a, b.asType());
    }

    public boolean same(TypeElement a, TypeElement b){
        return same(a.asType(), b.asType());
    }

    public ClassSymbol conv(Class<?> type){
        return elements.getTypeElement(fName(type));
    }

    public <T extends TypeSymbol> T conv(TypeMirror type){
        return (T)types.asElement(type);
    }

    public static boolean isPrimitive(String type){
        return switch(type){
            case "boolean", "byte", "short", "int", "long", "float", "double", "char" -> true;
            default -> false;
        };
    }

    public static String getDefault(String value){
        return switch(value){
            case "float", "double", "int", "long", "short", "char", "byte" -> "0";
            case "boolean" -> "false";
            default -> "null";
        };
    }

    public static boolean is(Element e, Modifier... modifiers){
        var set = e.getModifiers();
        for(var modifier : modifiers) if(!set.contains(modifier)) return false;

        return true;
    }

    public static boolean isAny(Element e, Modifier... modifiers){
        var set = e.getModifiers();
        for(var modifier : modifiers) if(set.contains(modifier)) return true;

        return false;
    }

    public static <T extends Annotation> T anno(Element e, Class<T> type){
        if(annotations.containsKey(e)){
            return (T)annotations.get(e).get(type, () -> createAnno(e, type));
        }else{
            ObjectMap<Class<? extends Annotation>, Annotation> map;
            annotations.put(e, map = new ObjectMap<>());

            T anno;
            map.put(type, anno = createAnno(e, type));

            return anno;
        }
    }

    private static <T extends Annotation> T createAnno(Element e, Class<T> type){
        Compound compound = Reflect.invoke(AnnoConstruct.class, e, "getAttribute", new Object[]{type}, Class.class);
        return compound == null ? null : AnnoProxyMaker.generate(compound, type);
    }

    public static ClassName spec(Class<?> type){
        return ClassName.get(type);
    }

    public static ClassName spec(TypeElement type){
        return ClassName.get(type);
    }

    public static TypeName spec(TypeMirror type){
        return TypeName.get(type);
    }

    public static TypeVariableName spec(TypeParameterElement type){
        return TypeVariableName.get(type);
    }

    public static ParameterSpec spec(VariableElement var){
        return ParameterSpec.get(var);
    }

    public static AnnotationSpec spec(AnnotationMirror anno){
        return AnnotationSpec.get(anno);
    }

    public static ParameterizedTypeName paramSpec(ClassName type, TypeName... types){
        return ParameterizedTypeName.get(type, types);
    }

    public static WildcardTypeName subSpec(TypeName type){
        return WildcardTypeName.subtypeOf(type);
    }

    public static TypeVariableName tvSpec(String name, TypeName... bounds){
        return TypeVariableName.get(name, bounds);
    }

    public static String fName(Class<?> type){
        return type.getCanonicalName();
    }

    public static String fName(TypeElement e){
        return e.getQualifiedName().toString();
    }

    public static String name(Class<?> type){
        return type.getSimpleName();
    }

    public static String name(Element e){
        return e.getSimpleName().toString();
    }

    public static String name(String canonical){
        return canonical.contains(".") ? canonical.substring(canonical.lastIndexOf('.') + 1) : canonical;
    }

    public ClassSymbol type(Prov<Class<?>> run){
        try{
            run.get();
        }catch(MirroredTypeException e){
            return conv(e.getTypeMirror());
        }

        throw new IllegalArgumentException("type() is used for getting annotation values of a class type.");
    }

    public Seq<ClassSymbol> types(Prov<Class<?>[]> run){
        try{
            run.get();
        }catch(MirroredTypesException e){
            return Seq.with(e.getTypeMirrors()).map(this::conv);
        }

        throw new IllegalArgumentException("types() is used for getting annotation values of class types.");
    }

    public String desc(Element e){
        return switch(e.getKind()){
            case FIELD, LOCAL_VARIABLE -> desc((VariableElement)e);
            case METHOD -> desc((ExecutableElement)e);
            default -> throw new IllegalArgumentException("desc() only accepts variable and method elements: " + e);
        };
    }

    public String desc(VariableElement e){
        return e.getEnclosingElement().toString() + "#" + name(e);
    }

    public String desc(ExecutableElement e){
        return e.getEnclosingElement().toString() + "#" + sigName(e);
    }

    public String sigName(ExecutableElement e){
        var params = e.getParameters();
        if(params.isEmpty()) return name(e) + "()";

        var builder = new StringBuilder(name(e))
                          .append("(")
                          .append(params.get(0).asType());

        for(int i = 1; i < params.size(); i++) builder.append(", ").append(params.get(i).asType());
        return builder.append(")").toString();
    }

    public static String fixName(String canonical){
        var matcher = genStrip.matcher(canonical);
        if(matcher.find() && matcher.start() == 0){
            return canonical.substring(matcher.end());
        }else{
            return canonical;
        }
    }

    @Override
    public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation, ExecutableElement member, String userText){
        return Collections.emptyList();
    }

    @Override
    public SourceVersion getSupportedSourceVersion(){
        return SourceVersion.RELEASE_17;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes(){
        return Collections.emptySet();
    }

    @Override
    public Set<String> getSupportedOptions(){
        return Set.of(
            "modName",
            "genPackage",
            "fetchPackage"
        );
    }
}
