package ent.anno.proc;

import arc.files.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.Pool.*;
import arc.util.pooling.*;
import com.squareup.javapoet.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import ent.anno.Annotations.*;
import ent.anno.*;
import ent.anno.TypeIOResolver.*;
import ent.anno.proc.EntitySource.*;
import mindustry.gen.*;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import java.io.*;
import java.util.*;

import static javax.lang.model.element.ElementKind.*;
import static javax.lang.model.element.Modifier.*;
import static javax.lang.model.type.TypeKind.*;

/**
 * @author GlFolker
 * @author Anuke
 */
public class EntityProcessor extends BaseProcessor{
    private static final Seq<MethodSymbol> tmpMethods = new Seq<>();
    private static final Seq<Object> tmpArgs = new Seq<>();
    private static final Comparator<MethodSymbol> methodSorter = Structs.comps(Structs.comparingFloat(m -> {
        var priority = anno(m, MethodPriority.class);
        return priority == null ? 0 : priority.value();
    }), Structs.comparing(BaseProcessor::name));

    protected Fi revDir, cacheDir;

    protected ObjectMap<String, ClassSymbol> comps = new ObjectMap<>();
    protected ObjectMap<String, ClassSymbol> inters = new ObjectMap<>();
    protected Seq<ClassSymbol> baseComps = new Seq<>();
    protected ObjectMap<String, TypeSpec.Builder> baseClasses = new ObjectMap<>();
    protected ObjectMap<String, ClassSymbol> vanillaBaseClasses = new ObjectMap<>();
    protected ObjectMap<ClassSymbol, String> groups = new ObjectMap<>();
    protected ObjectMap<String, Seq<ClassSymbol>> groupExclusions = new ObjectMap<>();
    protected Seq<Symbol> defs = new Seq<>();

    protected ObjectMap<ClassSymbol, ObjectMap<String, Seq<MethodSymbol>>> inserters = new ObjectMap<>();
    protected ObjectMap<ClassSymbol, ObjectMap<String, Seq<MethodSymbol>>> wrappers = new ObjectMap<>();
    protected Seq<ClassSymbol> pointers = new Seq<>();

    protected ObjectMap<ClassSymbol, EntitySource> sources = new ObjectMap<>();
    protected ObjectMap<ClassSymbol, Seq<ClassSymbol>> dependencies = new ObjectMap<>();
    protected ObjectMap<ClassSymbol, ObjectSet<ClassSymbol>> baseDependencies = new ObjectMap<>();

    protected Seq<EntityDefinition> definitions = new Seq<>();

    protected ClassSerializer serializer;

    {
        rounds = 2;
    }

    @Override
    public synchronized void init(ProcessingEnvironment env){
        super.init(env);

        var revOpt = env.getOptions().get("revisionDir");
        var cacheOpt = env.getOptions().get("cacheDir");
        if(revOpt == null) throw new IllegalStateException("`revisionDir` not supplied!");
        if(cacheOpt == null) throw new IllegalStateException("`cacheDir` not supplied!");

        revDir = Fi.get(revOpt);
        cacheDir = Fi.get(cacheOpt);
        cacheDir.mkdirs();
    }

    protected EntitySource getSource(ClassSymbol comp) throws IOException{
        if(sources.containsKey(comp)) return sources.get(comp);

        var file = cacheDir.child(String.format("%s.bin", name(comp)));
        EntitySource src;

        try{
            src = new EntitySource(this, comp);
            try(var out = file.write(); var object = new ObjectOutputStream(out)){
                object.writeObject(src);
            }
        }catch(ReprocessedNotRecompiledException read){
            try(var in = file.read(); var object = new ObjectInputStream(in)){
                src = (EntitySource)object.readObject();
            }catch(ClassNotFoundException | ClassCastException e){
                throw new IOException(e);
            }
        }

        sources.put(comp, src);
        return src;
    }

    @Override
    protected void process() throws IOException{
        switch(round){
            case 1 -> {
                for(var t : this.<ClassSymbol>with(EntityComponent.class)) comps.put(name(t), t);
                for(var t : this.<ClassSymbol>with(EntityBaseComponent.class)) baseComps.add(t);
                for(var s : with(EntityDef.class)) defs.add(s);

                pointer:
                for(var t : this.<ClassSymbol>with(EntityPoint.class)){
                    for(var s : t.getEnclosedElements()){
                        if(s.getKind() == METHOD){
                            var m = (MethodSymbol)s;
                            if(is(m, PUBLIC, STATIC) && m.params.isEmpty() && same(m.getReturnType(), t)){
                                pointers.add(t);
                                continue pointer;
                            }
                        }
                    }

                    err("Missing `public static " + name(t) + " create()`", t);
                }

                for(var e : this.<MethodSymbol>with(Insert.class)){
                    if(!e.params.isEmpty()) err("All @Insert methods must not have parameters", e);

                    var type = comps.get(name(e.enclClass()));
                    if(type == null) continue;

                    inserters.get(type, ObjectMap::new).get(anno(e, Insert.class).value(), () -> new Seq<>(false)).add(e);
                }

                for(var e : this.<MethodSymbol>with(Wrap.class)){
                    if(!e.params.isEmpty()) err("All @Wrap methods must not have parameters", e);
                    if(e.getReturnType().getKind() != BOOLEAN)
                        err("All @Wrap methods must have boolean return type", e);

                    var type = comps.get(name(e.enclClass()));
                    if(type == null) continue;

                    wrappers.get(type, ObjectMap::new).get(anno(e, Wrap.class).value(), () -> new Seq<>(false)).add(e);
                }

                serializer = TypeIOResolver.resolve(this);
                groups.putAll(
                    comp(Entityc.class), "all",
                    comp(Playerc.class), "player",
                    comp(Bulletc.class), "bullet",
                    comp(Unitc.class), "unit",
                    comp(Buildingc.class), "build",
                    comp(Syncc.class), "sync",
                    comp(Drawc.class), "draw",
                    comp(Firec.class), "fire",
                    comp(Puddlec.class), "puddle",
                    comp(WorldLabelc.class), "label",
                    comp(PowerGraphUpdaterc.class), "powerGraph"
                );

                groupExclusions.put("all", Seq.with(
                    comp(Unitc.class),
                    comp(Bulletc.class),
                    comp(PowerGraphUpdaterc.class)
                ));

                for(var s : elements.getPackageElement("mindustry.gen").getEnclosedElements()){
                    var name = name(s);
                    if(name.endsWith("c") && s.getKind() == INTERFACE && comp(compName(name)) != null){
                        inters.put(name, (ClassSymbol)s);
                    }
                }

                for(var comp : comps.values()){
                    var src = getSource(comp);
                    var deps = dependencies(comp);

                    var compAnno = anno(comp, EntityComponent.class);
                    if(!compAnno.vanilla()){
                        var intBuilder = TypeSpec.interfaceBuilder(intName(comp))
                            .addOriginatingElement(comp)
                            .addModifiers(PUBLIC, ABSTRACT)
                            .addAnnotation(spec(EntityInterface.class))
                            .addAnnotation(
                                AnnotationSpec.builder(spec(SuppressWarnings.class))
                                    .addMember("value", "{$S, $S, $S}", "all", "unchecked", "deprecation")
                                    .build()
                            );

                        for(var ext : comp.getInterfaces())
                            if(!isCompInter(conv(ext))) intBuilder.addSuperinterface(spec(ext));
                        for(var dep : deps) intBuilder.addSuperinterface(procName(dep, this::intName));

                        ObjectSet<String> signatures = new ObjectSet<>();
                        for(var s : comp.getEnclosedElements()){
                            if(isAny(s, PRIVATE, STATIC) || s.getKind() != METHOD) continue;
                            var e = (MethodSymbol)s;

                            signatures.add(sigName(e));
                            if(anno(e, Override.class) == null){
                                var methBuilder = MethodSpec.methodBuilder(name(e))
                                    .addModifiers(PUBLIC, ABSTRACT)
                                    .returns(spec(e.getReturnType()));

                                for(var t : e.getTypeParameters()) methBuilder.addTypeVariable(spec(t));
                                for(var t : e.getThrownTypes()) methBuilder.addException(spec(t));
                                for(var v : e.getParameters()) methBuilder.addParameter(spec(v));
                                intBuilder.addMethod(methBuilder.build());
                            }
                        }
                        for(var s : comp.getEnclosedElements()){
                            if(isAny(s, PRIVATE, STATIC) || s.getKind() != FIELD || anno(s, Import.class) != null)
                                continue;
                            var v = (VarSymbol)s;
                            var name = name(v);

                            if(!signatures.contains(name + "()")){
                                var getter = MethodSpec.methodBuilder(name)
                                    .addModifiers(PUBLIC, ABSTRACT)
                                    .returns(spec(v.type));

                                for(var anno : v.getAnnotationMirrors()){
                                    var aname = name(anno.type.tsym);
                                    if(aname.contains("Null") || aname.contains("Deprecated"))
                                        getter.addAnnotation(spec((ClassSymbol)anno.type.tsym));
                                }

                                intBuilder.addMethod(getter.build());
                            }

                            if(!is(v, FINAL) && anno(v, ReadOnly.class) == null && !signatures.contains(name + "(" + v.type + ")")){
                                var setter = MethodSpec.methodBuilder(name)
                                    .addModifiers(PUBLIC, ABSTRACT)
                                    .returns(TypeName.VOID);

                                var param = ParameterSpec.builder(spec(v.type), name(v));
                                for(var anno : v.getAnnotationMirrors()){
                                    var aname = name(anno.type.tsym);
                                    if(aname.contains("Null") || aname.contains("Deprecated"))
                                        param.addAnnotation(spec((ClassSymbol)anno.type.tsym));
                                }

                                intBuilder.addMethod(setter.addParameter(param.build()).build());
                            }
                        }

                        write(intBuilder, Seq.with(src.imports));
                        if(compAnno.base()){
                            var baseDeps = deps.copy().add(comp);
                            baseDependencies.get(comp, ObjectSet::new).addAll(baseDeps);

                            if(anno(comp, EntityDef.class) == null){
                                var tname = baseName(comp);
                                var base = TypeSpec.classBuilder(tname)
                                    .addModifiers(PUBLIC, ABSTRACT)
                                    .addOriginatingElement(comp);

                                for(var dep : baseDeps){
                                    for(var s : dep.getEnclosedElements()){
                                        if(s.getKind() == FIELD && !isAny(s, PRIVATE, STATIC) && anno(s, Import.class) == null && anno(s, ReadOnly.class) == null){
                                            var v = (VarSymbol)s;
                                            var field = FieldSpec.builder(spec(v.type), name(v), PUBLIC);

                                            if(is(v, TRANSIENT)) field.addModifiers(TRANSIENT);
                                            if(is(v, VOLATILE)) field.addModifiers(VOLATILE);
                                            for(var anno : v.getAnnotationMirrors()) field.addAnnotation(spec(anno));

                                            var init = src.varInitializers.get(desc(v));
                                            if(init != null) field.initializer(init);

                                            base.addField(field.build());
                                        }
                                    }

                                    base.addSuperinterface(procName(dep, this::intName));
                                }

                                baseClasses.put(tname, base);
                            }
                        }
                    }else if(compAnno.base()){
                        var baseDeps = baseDependencies.get(comp, ObjectSet::new);
                        baseDeps.add(comp);
                        baseDeps.addAll(deps);

                        var name = baseName(comp);
                        var base = elements.getTypeElement("mindustry.gen." + name);
                        if(base != null) vanillaBaseClasses.put(name, base);
                    }
                }
            }

            case 2 -> {
                for(var t : this.<ClassSymbol>with(EntityInterface.class)) inters.put(name(t), t);

                OrderedSet<String> registers = new OrderedSet<>();
                registers.orderedItems().ordered = false;

                OrderedMap<String, ClassSymbol> defComps = new OrderedMap<>();
                ObjectMap<String, ClassSymbol> defCompsResolve = new ObjectMap<>();
                Seq<ClassSymbol> valueComps = new Seq<>(false);

                Seq<String> defGroups = new Seq<>(false);
                ObjectSet<ClassSymbol> excludeGroups = new ObjectSet<>();

                ObjectMap<String, Seq<MethodSymbol>> methods = new ObjectMap<>();
                ObjectMap<FieldSpec, VarSymbol> specVariables = new ObjectMap<>();
                ObjectSet<String> usedFields = new ObjectSet<>();

                Seq<VarSymbol> syncedFields = new Seq<>();
                Seq<VarSymbol> allFields = new Seq<>();
                Seq<FieldSpec> allFieldSpecs = new Seq<>();

                ObjectMap<String, Seq<MethodSymbol>> allInserters = new ObjectMap<>();
                ObjectMap<String, Seq<MethodSymbol>> allWrappers = new ObjectMap<>();
                Seq<MethodSymbol> bypass = new Seq<>(), inserts = new Seq<>(), wraps = new Seq<>();
                OrderedSet<MethodSymbol> standaloneInserts = new OrderedSet<>(), standaloneWraps = new OrderedSet<>();

                ObjectSet<MethodSymbol> removal = new ObjectSet<>();
                for(var def : defs){
                    var defAnno = anno(def, EntityDef.class);

                    defComps.clear();
                    for(var comp : types(defAnno::value)){
                        var name = name(comp);

                        var inter = inter(name);
                        if(inter != null) defComps.put(compName(name), comp(inter));
                    }

                    if(defComps.isEmpty()) continue;
                    var name = def instanceof ClassSymbol ? baseName(name(def)) : createName(defComps);

                    defCompsResolve.clear();
                    for(var comp : defComps.values())
                        for(var dep : dependencies(comp)) defCompsResolve.put(name(dep), dep);
                    defComps.putAll(defCompsResolve);

                    ClassSymbol baseClassType = null;
                    for(var comp : defComps.values()){
                        if(anno(comp, EntityComponent.class).base()){
                            if(baseClassType == null){
                                baseClassType = comp;
                            }else{
                                err("Can't have more than one base classes", def);
                                break;
                            }
                        }
                    }

                    boolean typeIsBase = baseClassType != null && anno(def, EntityComponent.class) != null && anno(def, EntityComponent.class).base();

                    if(!typeIsBase && baseClassType != null && name.equals(baseName(baseClassType))) name += "Entity";
                    if(!registers.add(name)) continue;

                    valueComps.clear();
                    defGroups.clear();
                    excludeGroups.clear();
                    for(var comp : defComps.values()){
                        valueComps.add(comp);
                        var ex = anno(comp, ExcludeGroups.class);
                        if(ex != null){
                            for(var i : types(ex::value)){
                                var t = comp(i);
                                if(t != null) excludeGroups.add(t);
                            }
                        }
                    }

                    for(var comp : defComps.values()){
                        if(excludeGroups.contains(comp) || !groups.containsKey(comp)) continue;

                        var val = groups.get(comp);

                        Seq<ClassSymbol> exclusions = groupExclusions.get(val);
                        if(exclusions != null && exclusions.contains(valueComps::contains)) continue;

                        defGroups.add(val);
                    }

                    var builder = TypeSpec.classBuilder(name)
                        .addModifiers(PUBLIC)
                        .addAnnotation(
                            AnnotationSpec.builder(SuppressWarnings.class)
                                .addMember("value", "{$S, $S, $S}", "all", "unchecked", "deprecation")
                                .build()
                        );

                    for(var comp : defComps.values()) builder.addOriginatingElement(comp);

                    methods.clear();
                    specVariables.clear();
                    usedFields.clear();

                    syncedFields.clear();
                    allFields.clear();
                    allFieldSpecs.clear();

                    allInserters.clear();
                    allWrappers.clear();

                    boolean isSync = defComps.containsKey("SyncComp");
                    for(var comp : defComps.values()){
                        var src = getSource(comp);
                        var tmp = inserters.get(comp);
                        if(tmp != null)
                            for(var key : tmp.keys()) allInserters.get(key, Seq::new).addAll(tmp.get(key));

                        tmp = wrappers.get(comp);
                        if(tmp != null)
                            for(var key : tmp.keys()) allWrappers.get(key, Seq::new).addAll(tmp.get(key));

                        boolean isShadowed = baseClassType != null && !typeIsBase && baseDependencies.get(baseClassType).contains(comp);
                        for(var s : comp.getEnclosedElements()){
                            if(s.getKind() == FIELD && anno(s, Import.class) == null){
                                var v = (VarSymbol)s;

                                var fname = name(v);
                                if(!usedFields.add(fname)){
                                    err("Duplicate field names: `" + fname + "`.", v);
                                    continue;
                                }

                                var field = FieldSpec.builder(spec(v.type), fname);
                                if(is(v, STATIC)){
                                    field.addModifiers(STATIC);
                                    if(is(v, FINAL)) field.addModifiers(FINAL);
                                }

                                if(is(v, TRANSIENT)) field.addModifiers(TRANSIENT);
                                if(is(v, VOLATILE)) field.addModifiers(VOLATILE);
                                if(is(v, PRIVATE)){
                                    field.addModifiers(PRIVATE);
                                }else if(anno(v, ReadOnly.class) != null){
                                    field.addModifiers(PROTECTED);
                                }else{
                                    field.addModifiers(PUBLIC);
                                }

                                var init = src.varInitializers.get(desc(v));
                                if(init != null) field.initializer(init);

                                for(var anno : v.getAnnotationMirrors()) field.addAnnotation(spec(anno));
                                var spec = field.build();

                                boolean isVisible = !isAny(v, STATIC, PRIVATE) && anno(v, ReadOnly.class) == null;
                                if(!isShadowed || !isVisible) builder.addField(spec);

                                specVariables.put(spec, v);
                                allFieldSpecs.add(spec);
                                allFields.add(v);

                                if(isSync && anno(v, SyncField.class) != null){
                                    if(v.type.getKind() != FLOAT){
                                        err("All @SyncFields must be float.", v);
                                        continue;
                                    }

                                    syncedFields.add(v);
                                    builder.addField(FieldSpec.builder(TypeName.FLOAT, fname + EntityIO.targetSuffix, TRANSIENT, PRIVATE).build());
                                    builder.addField(FieldSpec.builder(TypeName.FLOAT, fname + EntityIO.lastSuffix, TRANSIENT, PRIVATE).build());
                                }
                            }else if(s.getKind() == METHOD){
                                var m = (MethodSymbol)s;
                                methods.get(sigName(m), Seq::new).add(m);
                            }
                        }
                    }

                    if(!methods.containsKey("toString()")){
                        builder.addMethod(
                            MethodSpec.methodBuilder("toString")
                                .addAnnotation(spec(Override.class))
                                .returns(spec(String.class))
                                .addModifiers(PUBLIC)
                                .addStatement("return $S + $L", name + "#", "id")
                                .build()
                        );
                    }

                    EntityIO io = null;
                    boolean hasIO = defAnno.genIO() && (isSync || defAnno.serialize());

                    boolean serializeOverride = false;
                    for(var entry : methods.entries()){
                        var key = entry.key;
                        var entries = entry.value;

                        var setter = allFields.find(v -> {
                            var first = entries.first();
                            return
                                name(v).equals(name(first)) &&
                                    first.getParameters().size() == 1 && same(first.getParameters().get(0).type, v.type) &&
                                    first.getReturnType().getKind() == VOID;
                        });

                        if(setter != null){
                            var c = setter.enclClass();
                            var n = name(setter);
                            for(var m : entries){
                                if(m.enclClass() == c && n.equals(name(m)) && m.getReturnType().getKind() == VOID && m.params.size() == 1 && same(m.params.get(0).type, setter.type)){
                                    setter = null;
                                    break;
                                }
                            }
                        }

                        var topReplacer = entries.max(m -> {
                            var rep = anno(m, Replace.class);
                            return rep == null ? -1 : rep.value();
                        });

                        Replace topReplace;
                        if(topReplacer != null && (topReplace = anno(topReplacer, Replace.class)) != null){
                            setter = null;

                            float max = topReplace.value();
                            if(topReplacer.getReturnType().getKind() == VOID){
                                entries.removeAll(m -> {
                                    var rep = anno(m, Replace.class);
                                    return rep == null || rep.value() != max;
                                });
                            }else{
                                if(entries.count(m -> {
                                    var rep = anno(m, Replace.class);
                                    return rep != null && rep.value() == max;
                                }) > 1){
                                    err("Type " + name + " has multiple components replacing non-void method " + key + " with similar priorities.", def);
                                    continue;
                                }

                                entries.clear();
                                entries.add(topReplacer);
                            }
                        }

                        removal.clear();
                        for(var m : entries){
                            var rem = anno(m, Remove.class);
                            if(rem != null){
                                if(removal.contains(m)){
                                    err(sigName(m) + " is already @Remove-d by another method.", def);
                                    continue;
                                }

                                var comp = comp(type(rem::value));
                                if(setter != null && same(setter.enclClass(), comp)){
                                    setter = null;
                                }

                                var removed = entries.find(e -> same(e.enclClass(), comp));
                                if(removed != null) removal.add(removed);
                            }
                        }

                        var it = entries.iterator();
                        while(it.hasNext()) if(removal.contains(it.next())) it.remove();

                        if(entries.count(m -> !isAny(m, ABSTRACT, NATIVE) && m.getReturnType().getKind() != VOID) > 1){
                            err("Type " + name + " has multiple components implementing non-void method " + entry.key + ".", def);
                            continue;
                        }

                        entries.sort(methodSorter);

                        var m = entries.first();
                        var mname = name(m);

                        if(entries.size > 1 && mname.equals("serialize") && m.getReturnType().getKind() == BOOLEAN && m.params.isEmpty()){
                            serializeOverride = true;
                        }else if(anno(m, InternalImpl.class) != null){
                            continue;
                        }

                        boolean isPrivate = is(m, PRIVATE);
                        var methBuilder = MethodSpec.methodBuilder(mname)
                            .addModifiers(isPrivate ? PRIVATE : PUBLIC)
                            .returns(spec(m.getReturnType()));

                        if(!isPrivate && !is(m, STATIC)) methBuilder.addAnnotation(spec(Override.class));
                        if(is(m, STATIC)) methBuilder.addModifiers(STATIC);

                        for(var t : m.getTypeParameters()) methBuilder.addTypeVariable(spec(t));
                        for(var t : m.getThrownTypes()) methBuilder.addException(spec(t));
                        for(var v : m.params) methBuilder.addParameter(spec(v));

                        boolean writeBlock = m.getReturnType().getKind() == VOID && entries.size > 1;
                        if(isAny(m, ABSTRACT, NATIVE) && entries.size == 1 && anno(m, InternalImpl.class) == null){
                            err(desc(m) + " is an abstract method and must be implemented in some component.", def);
                            continue;
                        }

                        inserts.set(allInserters.get(key, inserts.clear()));
                        wraps.set(allWrappers.get(key, wraps.clear()));
                        if(m.getReturnType().getKind() != VOID){
                            if(!inserts.isEmpty()){
                                err("Method " + sigName(m) + " is not void; no methods can @Insert to it.", def);
                                continue;
                            }

                            if(!wraps.isEmpty()){
                                err("Method " + sigName(m) + " is not void; no methods can @Wrap it.", def);
                                continue;
                            }
                        }

                        standaloneInserts.clear();
                        standaloneWraps.clear();

                        it = inserts.iterator();
                        while(it.hasNext()){
                            var next = it.next();
                            if(same(type(anno(next, Insert.class)::block), conv(Void.class))){
                                it.remove();
                                standaloneInserts.add(next);
                            }
                        }

                        it = wraps.iterator();
                        while(it.hasNext()){
                            var next = it.next();
                            if(same(type(anno(next, Wrap.class)::block), conv(Void.class))){
                                it.remove();
                                standaloneWraps.add(next);
                            }
                        }

                        standaloneInserts.orderedItems().sort(methodSorter);
                        inserts.removeAll(standaloneInserts::contains);
                        wraps.removeAll(standaloneWraps::contains);
                        inserts.sort(methodSorter);

                        boolean isAdd;
                        if((isAdd = mname.equals("add")) || mname.equals("remove")){
                            bypass.selectFrom(entries, e -> anno(e, BypassGroupCheck.class) != null);
                            entries.removeAll(bypass);

                            bypass.sort(methodSorter);

                            var priorBypass = tmpMethods.selectFrom(standaloneInserts.orderedItems(), e -> !anno(e, Insert.class).after() && anno(e, BypassGroupCheck.class) != null);
                            if(priorBypass.any()){
                                standaloneInserts.removeAll(priorBypass);
                                for(var e : priorBypass) methBuilder.addStatement("this.$L()", name(e));
                            }

                            append(methBuilder, defComps.values(), bypass, inserts, wraps, writeBlock, null);

                            methBuilder.addStatement("if($Ladded) return", mname.equals("add") ? "" : "!");
                            for(var group : defGroups){
                                if(isAdd){
                                    methBuilder.addStatement("$T.$L.add(this)", spec(Groups.class), group);
                                }else{
                                    methBuilder.addStatement("$T.$L.remove(this)", spec(Groups.class), group);
                                }
                            }
                        }

                        if(!standaloneWraps.isEmpty()){
                            var arr = standaloneWraps.orderedItems();

                            var format = new StringBuilder("if(this.$L()");
                            tmpArgs.clear().add(name(arr.first()));

                            for(int i = 1; i < arr.size; i++){
                                format.append(" && this.$L()");
                                tmpArgs.add(name(arr.get(i)));
                            }

                            methBuilder.beginControlFlow(format.append(")").toString(), tmpArgs.toArray());
                        }

                        for(var e : standaloneInserts)
                            if(!anno(e, Insert.class).after()) methBuilder.addStatement("this.$L()", name(e));

                        allFields.sortComparing(BaseProcessor::name);
                        syncedFields.sortComparing(BaseProcessor::name);

                        if(hasIO){
                            if(io == null)
                                io = new EntityIO(this, name, builder, allFieldSpecs, serializer, revDir.child(name));
                            if((mname.equals("read") || mname.equals("write"))){
                                io.write(methBuilder, mname.equals("write"), allFields);
                            }

                            if((mname.equals("readSync") || mname.equals("writeSync"))){
                                io.writeSync(methBuilder, mname.equals("writeSync"), allFields);
                            }

                            if((mname.equals("readSyncManual") || mname.equals("writeSyncManual"))){
                                io.writeSyncManual(methBuilder, mname.equals("writeSyncManual"), syncedFields);
                            }

                            if(mname.equals("interpolate")){
                                io.writeInterpolate(methBuilder, syncedFields);
                            }

                            if(mname.equals("snapSync")){
                                methBuilder.addStatement("updateSpacing = 16");
                                methBuilder.addStatement("lastUpdated = $T.millis()", Time.class);
                                for(var v : syncedFields){
                                    methBuilder.addStatement("$L = $L", name(v) + EntityIO.lastSuffix, name(v) + EntityIO.targetSuffix);
                                    methBuilder.addStatement("$L = $L", name(v), name(v) + EntityIO.targetSuffix);
                                }
                            }

                            if(mname.equals("snapInterpolation")){
                                methBuilder.addStatement("updateSpacing = 16");
                                methBuilder.addStatement("lastUpdated = $T.millis()", Time.class);
                                for(var v : syncedFields){
                                    methBuilder.addStatement("$L = $L", name(v) + EntityIO.lastSuffix, name(v));
                                    methBuilder.addStatement("$L = $L", name(v) + EntityIO.targetSuffix, name(v));
                                }
                            }
                        }

                        append(methBuilder, defComps.values(), entries, inserts, wraps, writeBlock, setter);

                        for(var e : standaloneInserts)
                            if(anno(e, Insert.class).after()) methBuilder.addStatement("this.$L()", name(e));
                        if(defAnno.pooled() && mname.equals("remove"))
                            methBuilder.addStatement("$T.queueFree(this)", spec(Groups.class));

                        if(!standaloneWraps.isEmpty()) methBuilder.endControlFlow();
                        builder.addMethod(methBuilder.build());
                    }

                    if(!serializeOverride){
                        builder.addMethod(
                            MethodSpec.methodBuilder("serialize").addModifiers(Modifier.PUBLIC)
                                .addAnnotation(spec(Override.class))
                                .returns(TypeName.BOOLEAN)
                                .addStatement("return " + defAnno.serialize())
                                .build()
                        );
                    }

                    if(defAnno.pooled()){
                        builder.addSuperinterface(spec(Poolable.class));

                        var resetBuilder = MethodSpec.methodBuilder("reset")
                            .addModifiers(PUBLIC)
                            .addAnnotation(spec(Override.class));

                        allFieldSpecs.sortComparing(s -> s.name);
                        for(var spec : allFieldSpecs){
                            var v = specVariables.get(spec);
                            if(v == null || isAny(v, STATIC, FINAL)) continue;

                            var src = getSource(v.enclClass());
                            var desc = desc(v);
                            resetBuilder.addStatement("this.$L = $L", spec.name, src.varInitializers.containsKey(desc)
                                ? src.varInitializers.get(desc)
                                : spec.type.isPrimitive() ? getDefault(spec.type.toString()) : "null"
                            );
                        }

                        builder.addMethod(resetBuilder.build());
                    }

                    var creator = MethodSpec.methodBuilder("create")
                        .addModifiers(PUBLIC, STATIC)
                        .returns(ClassName.get(packageName, name));

                    if(defAnno.pooled()){
                        creator.addStatement("return $T.obtain($T.class, $T::new)", spec(Pools.class), ClassName.get(packageName, name), ClassName.get(packageName, name));
                    }else{
                        creator.addStatement("return new $T()", ClassName.get(packageName, name));
                    }

                    builder
                        .addMethod(MethodSpec.constructorBuilder().addModifiers(PROTECTED).build())
                        .addMethod(creator.build());

                    definitions.add(new EntityDefinition(name, builder, def, typeIsBase ? null : baseClassType, defComps.values().toSeq(), allFieldSpecs.copy()));
                }

                var registry = TypeSpec.classBuilder("EntityRegistry")
                    .addModifiers(PUBLIC, FINAL)
                    .addAnnotation(
                        AnnotationSpec.builder(spec(SuppressWarnings.class))
                            .addMember("value", "$S", "unchecked")
                            .build()
                    )
                    .addField(
                        FieldSpec.builder(
                                paramSpec(spec(ObjectMap.class), spec(String.class), paramSpec(spec(Prov.class), subSpec(spec(Entityc.class)))),
                                "map",
                                PRIVATE, STATIC, FINAL
                            )
                            .initializer("new $T<>()", spec(ObjectMap.class))
                            .build()
                    )
                    .addField(
                        FieldSpec.builder(
                                paramSpec(spec(ObjectIntMap.class), paramSpec(spec(Class.class), subSpec(spec(Entityc.class)))),
                                "ids",
                                PRIVATE, STATIC, FINAL
                            )
                            .initializer("new $T<>()", spec(ObjectIntMap.class)).
                            build()
                    )
                    .addMethod(
                        MethodSpec.constructorBuilder()
                            .addModifiers(PRIVATE)
                            .addStatement("throw new $T()", spec(AssertionError.class))
                            .build()
                    )
                    .addMethod(
                        MethodSpec.methodBuilder("get")
                            .addModifiers(PUBLIC, STATIC)
                            .addTypeVariable(tvSpec("T", spec(Entityc.class)))
                            .returns(paramSpec(spec(Prov.class), tvSpec("T")))
                            .addParameter(paramSpec(spec(Class.class), tvSpec("T")), "type")
                            .addStatement("return get(type.getCanonicalName())")
                            .build()
                    )
                    .addMethod(
                        MethodSpec.methodBuilder("get")
                            .addModifiers(PUBLIC, STATIC)
                            .addTypeVariable(tvSpec("T", spec(Entityc.class)))
                            .returns(paramSpec(spec(Prov.class), tvSpec("T")))
                            .addParameter(spec(String.class), "name")
                            .addStatement("return ($T)map.get(name)", paramSpec(spec(Prov.class), tvSpec("T")))
                            .build()
                    )
                    .addMethod(
                        MethodSpec.methodBuilder("getID")
                            .addModifiers(PUBLIC, STATIC)
                            .returns(TypeName.INT)
                            .addParameter(paramSpec(spec(Class.class), subSpec(spec(Entityc.class))), "type")
                            .addStatement("return ids.get(type, -1)")
                            .build()
                    )
                    .addMethod(
                        MethodSpec.methodBuilder("register")
                            .addModifiers(PUBLIC, STATIC)
                            .addTypeVariable(tvSpec("T", spec(Entityc.class)))
                            .returns(TypeName.VOID)
                            .addParameter(spec(String.class), "name")
                            .addParameter(paramSpec(spec(Class.class), tvSpec("T")), "type")
                            .addParameter(paramSpec(spec(Prov.class), subSpec(tvSpec("T"))), "prov")
                            .addStatement("map.put(name, prov)")
                            .addStatement("ids.put(type, $T.register(name, prov))", spec(EntityMapping.class))
                            .build()
                    )
                    .addMethod(
                        MethodSpec.methodBuilder("content")
                            .addModifiers(PUBLIC, STATIC)
                            .addTypeVariable(tvSpec("T"))
                            .addTypeVariable(tvSpec("E", spec(Entityc.class)))
                            .returns(tvSpec("T"))
                            .addParameter(spec(String.class), "name")
                            .addParameter(paramSpec(spec(Class.class), tvSpec("E")), "type")
                            .addParameter(paramSpec(spec(Func.class), spec(String.class), subSpec(tvSpec("T"))), "create")
                            .beginControlFlow("if(type.getName().startsWith($S))", "mindustry.gen.")
                            .addStatement("var prov = $T.find($T.idMap, p -> p != null && p.get().getClass().equals(type))", spec(Structs.class), spec(EntityMapping.class))
                            .addStatement("$T.nameMap.put($S + name, prov)", spec(EntityMapping.class), modName + "-")
                            .nextControlFlow("else")
                            .addStatement("$T.nameMap.put($S + name, get(type))", spec(EntityMapping.class), modName + "-")
                            .endControlFlow()
                            .addStatement("return create.get(name)")
                            .build()
                    );

                var register = MethodSpec.methodBuilder("register")
                    .addModifiers(PUBLIC, STATIC)
                    .returns(TypeName.VOID);

                pointers.sort(Structs.comparing(BaseProcessor::fName));
                for(var point : pointers){
                    registry.addOriginatingElement(point);

                    var name = ClassName.get(point);
                    register.addStatement("register($S, $T.class, $T::create)", name.canonicalName(), name, name);
                }

                definitions.sort(Structs.comparing(def -> def.name));
                definitions.flatMap(def -> def.components).distinct().each(registry::addOriginatingElement);

                Seq<String> imports = new Seq<>();
                for(var def : definitions){
                    imports.clear();

                    var name = ClassName.get(packageName, def.name);
                    register.addStatement("register($S, $T.class, $T::create)", name.canonicalName(), name, name);

                    def.builder.addMethod(
                        MethodSpec.methodBuilder("classId")
                            .addModifiers(PUBLIC)
                            .addAnnotation(spec(Override.class))
                            .returns(TypeName.INT)
                            .addStatement("return $T.getID($T.class)", ClassName.get(packageName, "EntityRegistry"), name)
                            .build()
                    );

                    ClassSymbol vanillaSuper = null;
                    if(def.extend != null){
                        var baseName = baseName(def.extend);
                        if(vanillaBaseClasses.containsKey(baseName)){
                            vanillaSuper = vanillaBaseClasses.get(baseName);
                            def.builder.superclass(spec(vanillaSuper));
                        }else{
                            def.builder.superclass(ClassName.get(packageName, baseName));
                        }
                    }

                    var methodNames = def.components.flatMap(t -> {
                        Seq<String> out = new Seq<>();
                        for(var s : t.getEnclosedElements())
                            if(s.getKind() == METHOD) out.add(sigName((MethodSymbol)s));

                        return out;
                    }).asSet();

                    TypeSpec.Builder superclass = null;
                    if(def.extend != null){
                        superclass = baseClasses.get(name(def.extend));
                    }

                    for(var comp : def.components){
                        var src = getSource(comp);
                        imports.addAll(src.imports);

                        var inter = inter(comp);
                        if(inter == null){
                            err("Failed to implement for '" + comp + "'", def.naming);
                            continue;
                        }

                        def.builder.addSuperinterface(spec(inter));
                        for(var s : inter.getEnclosedElements()){
                            if(s.getKind() == METHOD){
                                var m = (MethodSymbol)s;

                                var var = name(m);
                                var field = def.fieldSpecs.find(f -> f.name.equals(var));
                                if(field == null || methodNames.contains(sigName(m))) continue;

                                MethodSpec result = null;
                                if(m.getReturnType().getKind() != VOID){
                                    result = MethodSpec.methodBuilder(var)
                                        .addModifiers(PUBLIC)
                                        .addAnnotation(spec(Override.class))
                                        .returns(spec(m.getReturnType()))
                                        .addStatement("return $L", var)
                                        .build();
                                }else if(!Seq.with(field.annotations).contains(f -> f.type.toString().equals("@" + fName(ReadOnly.class)))){
                                    result = MethodSpec.methodBuilder(var)
                                        .addModifiers(PUBLIC)
                                        .addAnnotation(spec(Override.class))
                                        .returns(TypeName.VOID)
                                        .addParameter(field.type, var)
                                        .addStatement("this.$L = $L", var, var)
                                        .build();
                                }

                                if(result != null){
                                    if(superclass != null){
                                        var superField = Seq.with(superclass.fieldSpecs).find(f -> f.name.equals(var));
                                        if(superField != null){
                                            var ret = result.returnType;
                                            var targetMethod = Seq.with(superclass.methodSpecs).find(e -> e.name.equals(var) && e.returnType.equals(ret));

                                            if(targetMethod == null) superclass.addMethod(result);
                                            continue;
                                        }
                                    }else if(vanillaSuper != null){
                                        VarSymbol superField = null;
                                        for(var sym : vanillaSuper.getEnclosedElements()){
                                            if(sym.getKind() == FIELD && name(sym).equals(var))
                                                superField = (VarSymbol)sym;
                                        }

                                        if(superField != null){
                                            MethodSymbol targetMethod = null;
                                            for(var sym : vanillaSuper.getEnclosedElements()){
                                                if(sym.getKind() == METHOD && name(sym).equals(var)){
                                                    var msym = (MethodSymbol)sym;
                                                    if(spec(msym.getReturnType()).equals(result.returnType))
                                                        targetMethod = msym;
                                                }
                                            }

                                            if(targetMethod != null) continue;
                                        }
                                    }
                                }

                                if(result != null) def.builder.addMethod(result);
                            }
                        }
                    }

                    write(def.builder, imports);
                }

                for(var base : baseClasses.values()){
                    imports.clear();
                    for(var dep : baseDependencies.get(comp(Reflect.get(base, "name") + "Comp"))){
                        var src = getSource(dep);
                        imports.addAll(src.imports);
                    }

                    write(base, imports);
                }

                write(registry.addMethod(register.build()), null);
            }

            default -> throw new IllegalStateException("Unknown round: " + round);
        }
    }

    protected void append(MethodSpec.Builder methBuilder, Iterable<ClassSymbol> defComps, Seq<MethodSymbol> entries, Seq<MethodSymbol> inserts, Seq<MethodSymbol> wraps, boolean writeBlock, VarSymbol setter) throws IOException{
        boolean hasSet = false;
        for(var m : entries){
            if(!ext(m, defComps)) continue;
            if(setter != null && !hasSet && (anno(m, MethodPriority.class) == null || anno(m, MethodPriority.class).value() == 0)){
                hasSet = true;
                var blockName = baseName(setter.enclClass()).toLowerCase();
                var wrapComp = tmpMethods.selectFrom(wraps, e -> baseName(comp(type(anno(e, Wrap.class)::block))).toLowerCase().equals(blockName));

                boolean wrapped = !wrapComp.isEmpty();
                if(wrapped){
                    var format = new StringBuilder("if(this.$L()");
                    tmpArgs.clear().add(name(wrapComp.first()));

                    for(int i = 1; i < wrapComp.size; i++){
                        format.append(" && this.$L()");
                        tmpArgs.add(name(wrapComp.get(i)));
                    }

                    methBuilder.beginControlFlow(format.append(")").toString(), tmpArgs.toArray());
                }

                var insertComp = tmpMethods.selectFrom(inserts, e -> baseName(comp(type(anno(e, Insert.class)::block))).toLowerCase().equals(blockName));
                for(var e : insertComp)
                    if(!anno(e, Insert.class).after()) methBuilder.addStatement("this.$L()", name(e));

                if(!isAny(m, ABSTRACT, NATIVE)){
                    if(writeBlock) methBuilder.beginControlFlow("$L:", blockName);
                    methBuilder.addStatement("this.$L = $L", name(setter), name(setter));
                    if(writeBlock) methBuilder.endControlFlow();
                }

                for(var e : insertComp)
                    if(anno(e, Insert.class).after()) methBuilder.addStatement("this.$L()", name(e));
                if(wrapped) methBuilder.endControlFlow();
            }

            var src = getSource(m.enclClass());
            var blockName = baseName(m.enclClass()).toLowerCase();
            var wrapComp = tmpMethods.selectFrom(wraps, e -> baseName(comp(type(anno(e, Wrap.class)::block))).toLowerCase().equals(blockName));

            boolean wrapped = !wrapComp.isEmpty();
            if(wrapped){
                var format = new StringBuilder("if(this.$L()");
                tmpArgs.clear().add(name(wrapComp.first()));

                for(int i = 1; i < wrapComp.size; i++){
                    format.append(" && this.$L()");
                    tmpArgs.add(name(wrapComp.get(i)));
                }

                methBuilder.beginControlFlow(format.append(")").toString(), tmpArgs.toArray());
            }

            var insertComp = tmpMethods.selectFrom(inserts, e -> baseName(comp(type(anno(e, Insert.class)::block))).toLowerCase().equals(blockName));
            for(var e : insertComp) if(!anno(e, Insert.class).after()) methBuilder.addStatement("this.$L()", name(e));

            var desc = desc(m);
            if(!isAny(m, ABSTRACT, NATIVE) && src.methodBlocks.containsKey(desc)){
                var block = src.methodBlocks.get(desc).substitute((writeBlock && anno(m, BreakAll.class) == null) ? blockName : null);
                if(!block.replace("\n", "").replaceAll("\\s+", "").isEmpty()){
                    if(writeBlock) methBuilder.beginControlFlow("$L:", blockName);
                    methBuilder.addCode(block);
                    if(writeBlock) methBuilder.endControlFlow();
                }
            }

            for(var e : insertComp) if(anno(e, Insert.class).after()) methBuilder.addStatement("this.$L()", name(e));
            if(wrapped) methBuilder.endControlFlow();
        }
    }

    protected boolean ext(ExecutableElement e, Iterable<ClassSymbol> defComps){
        var ext = anno(e, Extend.class);
        if(ext == null) return true;

        if(ext.any()){
            for(var target : types(ext::value)){
                var comp = comp(target);
                for(var def : defComps) if(comp == def) return true;
            }
            return false;
        }else{
            search:
            for(var target : types(ext::value)){
                var comp = comp(target);
                for(var def : defComps) if(comp == def) continue search;

                return false;
            }
            return true;
        }
    }

    protected ClassSymbol comp(Class<?> type){
        return comp(compName(name(type)));
    }

    protected ClassSymbol comp(ClassSymbol inter){
        var name = name(inter);
        if(!name.endsWith("c")) return null;

        return comp(compName(name));
    }

    protected ClassSymbol comp(String compName){
        return comps.get(compName);
    }

    protected ClassSymbol inter(ClassSymbol comp){
        var name = name(comp);
        if(!name.endsWith("Comp")) return null;

        return inter(intName(name));
    }

    protected ClassSymbol inter(String intName){
        return inters.get(intName);
    }

    protected String intName(ClassSymbol comp){
        return intName(name(comp));
    }

    protected String intName(String compName){
        return baseName(compName) + "c";
    }

    protected String baseName(ClassSymbol comp){
        return baseName(name(comp));
    }

    protected String baseName(String compName){
        if(!compName.endsWith("Comp")){
            err("All types annotated with @EntityComponent must have 'Comp' as the name's suffix: '" + compName + "'.");
            return compName;
        }

        return compName.substring(0, compName.length() - 4);
    }

    protected String compName(String intName){
        if(!intName.endsWith("c")){
            err("Interface names must end with 'c': '" + intName + "'.");
            return intName;
        }

        return intName.substring(0, intName.length() - 1) + "Comp";
    }

    protected boolean isCompInter(ClassSymbol inter){
        return comp(inter) != null;
    }

    protected Seq<ClassSymbol> dependencies(ClassSymbol comp){
        if(!dependencies.containsKey(comp)){
            ObjectSet<ClassSymbol> out = new ObjectSet<>();
            for(var type : comp.getInterfaces()){
                var name = name(type.tsym);
                if(name.endsWith("c")){
                    var dep = comp(compName(name));
                    if(dep != null && dep != comp) out.add(dep);
                }
            }

            ObjectSet<ClassSymbol> result = new ObjectSet<>();
            for(var type : out){
                result.add(type);
                result.addAll(dependencies(type));
            }

            if(anno(comp, EntityBaseComponent.class) == null) result.addAll(baseComps);
            dependencies.put(comp, result.toSeq());
        }

        return dependencies.get(comp);
    }

    protected ClassName procName(ClassSymbol comp, Func<ClassSymbol, String> name){
        return ClassName.get(
            comp.packge().toString().contains(packageFetch) ? "mindustry.gen" : packageName,
            name.get(comp)
        );
    }

    protected String createName(OrderedMap<String, ClassSymbol> comps){
        var keys = comps.orderedKeys();

        var builder = new StringBuilder();
        for(int i = keys.size - 1; i >= 0; i--) builder.append(baseName(comps.get(keys.get(i))));

        return builder.toString();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes(){
        return Set.of(
            fName(EntityComponent.class),
            fName(EntityBaseComponent.class),
            fName(EntityDef.class),
            fName(EntityPoint.class)
        );
    }

    @Override
    public Set<String> getSupportedOptions(){
        Set<String> opts = new HashSet<>(super.getSupportedOptions());
        opts.add("cacheDir");
        opts.add("revisionDir");
        return Collections.unmodifiableSet(opts);
    }

    public static class EntityDefinition{
        protected final String name;
        protected final TypeSpec.Builder builder;
        protected final Symbol naming;
        protected final @Nullable ClassSymbol extend;
        protected final Seq<ClassSymbol> components;
        protected final Seq<FieldSpec> fieldSpecs;

        public EntityDefinition(String name, TypeSpec.Builder builder, Symbol naming, ClassSymbol extend, Seq<ClassSymbol> components, Seq<FieldSpec> fieldSpecs){
            this.name = name;
            this.builder = builder;
            this.naming = naming;
            this.extend = extend;
            this.components = components;
            this.fieldSpecs = fieldSpecs;
        }

        @Override
        public String toString(){
            return "EntityDefinition{" + "components=" + components + ", fieldSpecs=" + fieldSpecs + ", builder=" + builder + ", naming=" + naming + ", name='" + name + '\'' + ", extend=" + extend + '}';
        }
    }
}