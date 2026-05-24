package ent.anno.proc;

import arc.files.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import com.squareup.javapoet.*;
import com.sun.tools.javac.code.Symbol.*;
import ent.anno.*;
import ent.anno.Annotations.*;
import ent.anno.TypeIOResolver.*;
import mindustry.*;
import mindustry.ctype.*;

import java.lang.annotation.*;
import java.util.*;

import static ent.anno.BaseProcessor.*;
import static javax.lang.model.element.Modifier.*;

/**
 * @author Anuke
 * @author GlFolker
 */
public class EntityIO{
    public static final Json json = new Json();
    public static final String targetSuffix = "_TARGET_", lastSuffix = "_LAST_";
    public static final StringMap refactors = StringMap.of("mindustry.entities.units.BuildRequest", "mindustry.entities.units.BuildPlan");

    public final BaseProcessor proc;
    public final ClassSerializer serializer;
    public final String name;
    public final TypeSpec.Builder type;
    public final Fi directory;
    public final Seq<Revision> revisions = new Seq<>();

    public ObjectSet<String> presentFields = new ObjectSet<>();
    protected boolean write;
    protected MethodSpec.Builder method;

    static{
        json.setIgnoreUnknownFields(true);
    }

    public EntityIO(BaseProcessor proc, String name, TypeSpec.Builder type, Seq<FieldSpec> typeFields, ClassSerializer serializer, Fi directory){
        this.proc = proc;
        this.directory = directory;
        this.type = type;
        this.serializer = serializer;
        this.name = name;

        json.setIgnoreUnknownFields(true);
        directory.mkdirs();

        for(var fi : directory.list()) revisions.add(json.fromJson(Revision.class, fi).proc(proc));
        revisions.sort(r -> r.version);

        int nextRevision = revisions.isEmpty() ? 0 : revisions.max(r -> r.version).version + 1;

        var fields = typeFields.select(spec ->
            !spec.hasModifier(TRANSIENT) &&
                !spec.hasModifier(STATIC) &&
                !spec.hasModifier(FINAL));

        fields.sortComparing(f -> f.name);
        presentFields.addAll(fields.map(f -> f.name));

        var previous = revisions.isEmpty() ? null : revisions.peek();
        if(revisions.isEmpty() || !revisions.peek().equal(fields)){
            revisions.add(new Revision(nextRevision, fields.map(f -> new RevisionField(f.name, f.type.toString()))).proc(proc));
            Log.warn("Adding new revision @ for @.\nPre = @\nNew = @\n", nextRevision, name, previous == null ? "(none)" : previous.fields.toString(", ", f -> f.name + ":" + f.type), fields.toString(", ", f -> f.name + ":" + f.type.toString()));

            directory.child(nextRevision + ".json").writeString(json.toJson(revisions.peek()));
        }
    }

    public void write(MethodSpec.Builder method, boolean write, Seq<VarSymbol> allFields){
        this.method = method;
        this.write = write;

        if(write){
            st("write.s($L)", revisions.peek().version);
            for(var field : revisions.peek().fields){
                var var = allFields.find(s -> name(s).equals(field.name));
                if(var == null || anno(var, NoSerialize.class) != null) continue;

                io(field.type, "this." + field.name, false);
            }
        }else{
            st("short REV = read.s()");

            cont("switch(REV)");
            for(var rev : revisions){
                cont("case $L ->", rev.version);
                for(var field : rev.fields){
                    var var = allFields.find(s -> name(s).equals(field.name));
                    if(var == null || anno(var, NoSerialize.class) != null) continue;

                    io(field.type, presentFields.contains(field.name) ? "this." + field.name + " = " : "", false);
                }
                econt();
            }

            cont("default ->");
            st("throw new $T(\"Unknown revision '\" + REV + \"' for entities type '" + name + "'\")", spec(IllegalArgumentException.class));
            econt();

            econt();
        }
    }

    public void writeSync(MethodSpec.Builder method, boolean write, Seq<VarSymbol> allFields){
        this.method = method;
        this.write = write;

        if(write){
            for(var field : revisions.peek().fields){
                var var = allFields.find(s -> name(s).equals(field.name));
                if(var == null || anno(var, NoSync.class) != null) continue;

                io(field.type, "this." + field.name, true);
            }
        }else{
            var rev = revisions.peek();

            st("if(lastUpdated != 0) updateSpacing = $T.timeSinceMillis(lastUpdated)", spec(Time.class));
            st("lastUpdated = $T.millis()", spec(Time.class));
            st("boolean islocal = isLocal()");

            for(var field : rev.fields){
                var var = allFields.find(s -> name(s).equals(field.name));
                if(var == null || anno(var, NoSync.class) != null) continue;

                boolean sf = anno(var, SyncField.class) != null, sl = anno(var, SyncLocal.class) != null;
                if(sl) cont("if(!islocal)");
                if(sf) st(field.name + lastSuffix + " = this." + field.name);

                io(field.type, "this." + (sf ? field.name + targetSuffix : field.name) + " = ", true);

                if(sl){
                    ncont("else");
                    io(field.type, "", true);

                    if(sf){
                        st(field.name + lastSuffix + " = this." + field.name);
                        st(field.name + targetSuffix + " = this." + field.name);
                    }

                    econt();
                }
            }

            st("afterSync()");
        }
    }

    public void writeSyncManual(MethodSpec.Builder method, boolean write, Seq<VarSymbol> syncFields){
        this.method = method;
        this.write = write;

        if(write){
            for(var field : syncFields){
                st("buffer.put(this.$L)", name(field));
            }
        }else{
            st("if(lastUpdated != 0) updateSpacing = $T.timeSinceMillis(lastUpdated)", spec(Time.class));
            st("lastUpdated = $T.millis()", spec(Time.class));

            for(var field : syncFields){
                st("this.$L = this.$L", name(field) + lastSuffix, name(field));
                st("this.$L = buffer.get()", name(field) + targetSuffix);
            }
        }
    }

    public void writeInterpolate(MethodSpec.Builder method, Seq<VarSymbol> fields){
        this.method = method;

        cont("if(lastUpdated != 0 && updateSpacing != 0)");

        st("float timeSinceUpdate = Time.timeSinceMillis(lastUpdated)");
        st("float alpha = Math.min(timeSinceUpdate / updateSpacing, 2f)");

        for(var field : fields){
            String name = name(field), targetName = name + targetSuffix, lastName = name + lastSuffix;
            st("$L = $L($T.$L($L, $L, alpha))",
                name, anno(field, SyncField.class).clamped() ? "arc.math.Mathf.clamp" : "",
                spec(Mathf.class),
                anno(field, SyncField.class).value() ? "lerp" : "slerp", lastName, targetName
            );
        }

        ncont("else if(lastUpdated != 0)"); //check if no meaningful data has arrived yet

        for(var field : fields){
            String name = name(field), targetName = name + targetSuffix;
            st("$L = $L", name, targetName);
        }

        econt();
    }

    private void io(String type, String field, boolean network){
        type = type.replace(proc.packageName + ".", "");
        type = refactors.get(type, type);

        if(isPrimitive(type)){
            s(type.equals("boolean") ? "bool" : String.valueOf(type.charAt(0)), field);
        }else if(
            proc.instanceOf(type, "mindustry.ctype.Content") &&
            !type.equals("mindustry.ai.UnitStance") &&
            !type.equals("mindustry.ai.UnitCommand")
        ){
            if(write){
                s("s", field + ".id");
            }else{
                st(field + "$T.content.getByID($T.$L, read.s())", spec(Vars.class), spec(ContentType.class), name(type).toLowerCase().replace("type", ""));
            }
        }else if((serializer.writers.containsKey(type) || (network && serializer.netWriters.containsKey(type))) && write){
            st("$L(write, $L)", network ? serializer.getNetWriter(type, null) : serializer.writers.get(type), field);
        }else if(serializer.mutatorReaders.containsKey(type) && !write && !field.replace(" = ", "").contains(" ") && !field.isEmpty()){
            st("$L$L(read, $L)", field, serializer.mutatorReaders.get(type), field.replace(" = ", ""));
        }else if(serializer.readers.containsKey(type) && !write){
            st("$L$L(read)", field, serializer.readers.get(type));
        }else if(type.endsWith("[]")){
            var rawType = type.substring(0, type.length() - 2);
            if(write){
                s("i", field + ".length");
                cont("for(int INDEX = 0; INDEX < $L.length; INDEX ++)", field);
                io(rawType, field + "[INDEX]", network);
            }else{
                var fieldName = field.replace(" = ", "").replace("this.", "");
                var lenf = fieldName + "_LENGTH";
                s("i", "int " + lenf + " = ");
                if(!field.isEmpty()){
                    st("$Lnew $L[$L]", field, type.replace("[]", ""), lenf);
                }
                cont("for(int INDEX = 0; INDEX < $L; INDEX ++)", lenf);
                io(rawType, field.replace(" = ", "[INDEX] = "), network);
            }

            econt();
        }else if(type.startsWith("arc.struct") && type.contains("<")){
            var struct = type.substring(0, type.indexOf("<"));
            var generic = type.substring(type.indexOf("<") + 1, type.indexOf(">"));

            if(struct.equals("arc.struct.Queue") || struct.equals("arc.struct.Seq")){
                if(write){
                    s("i", field + ".size");
                    cont("for(int INDEX = 0; INDEX < $L.size; INDEX ++)", field);
                    io(generic, field + ".get(INDEX)", network);
                }else{
                    var fieldName = field.replace(" = ", "").replace("this.", "");
                    var lenf = fieldName + "_LENGTH";
                    s("i", "int " + lenf + " = ");
                    if(!field.isEmpty()){
                        st("$L.clear()", field.replace(" = ", ""));
                    }

                    cont("for(int INDEX = 0; INDEX < $L; INDEX ++)", lenf);
                    io(generic, field.replace(" = ", "_ITEM = ").replace("this.", generic + " "), network);
                    if(!field.isEmpty()){
                        var temp = field.replace(" = ", "_ITEM").replace("this.", "");
                        st("if($L != null) $L.add($L)", temp, field.replace(" = ", ""), temp);
                    }
                }

                econt();
            }else{
                Log.warn("Missing serialization code for collection '@' in '@'", type, name);
            }
        }else{
            Log.warn("Missing serialization code for type '@' in '@'", type, name);
        }
    }

    private void cont(String text, Object... fmt){
        method.beginControlFlow(text, fmt);
    }

    private void econt(){
        method.endControlFlow();
    }

    private void ncont(String text, Object... fmt){
        method.nextControlFlow(text, fmt);
    }

    private void st(String text, Object... args){
        method.addStatement(text, args);
    }

    private void s(String type, String field){
        if(write){
            method.addStatement("write.$L($L)", type, field);
        }else{
            method.addStatement("$Lread.$L()", field, type);
        }
    }

    public static class Revision{
        public int version;
        public Seq<RevisionField> fields;

        protected transient BaseProcessor proc;

        public Revision(int version, Seq<RevisionField> fields){
            this.version = version;
            this.fields = fields;
        }

        @SuppressWarnings("unused")
        public Revision(){}

        public Revision proc(BaseProcessor proc){
            this.proc = proc;
            return this;
        }

        public boolean equal(Seq<FieldSpec> specs){
            if(fields.size != specs.size) return false;
            for(int i = 0; i < fields.size; i++){
                var field = fields.get(i);
                var spec = specs.get(i);
                if(!field.type.replace(proc.packageName + ".", "").equals(
                    spec.type.toString().replace(proc.packageName + ".", "")
                )) return false;
            }

            return true;
        }
    }

    public static class RevisionField{
        public String name, type;

        public RevisionField(String name, String type){
            this.name = name;
            this.type = type;
        }

        @SuppressWarnings("unused")
        public RevisionField(){}
    }
}
