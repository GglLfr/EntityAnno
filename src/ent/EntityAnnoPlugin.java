package ent;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.plugins.*;
import org.gradle.api.tasks.bundling.*;
import org.jetbrains.kotlin.gradle.internal.*;
import org.jetbrains.kotlin.gradle.plugin.*;
import org.jetbrains.kotlin.gradle.tasks.Kapt;
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile;

import java.io.*;
import java.util.concurrent.*;

/**
 * Gradle plugin for creating necessary entity component generation classes.
 * @author GlFolker
 */
public class EntityAnnoPlugin implements Plugin<Project>{
    public static final String defMindustryVersion = "v146";

    @Override
    public void apply(Project project){
        var plugins = project.getPlugins();
        var exts = project.getExtensions();
        var tasks = project.getTasks();

        var props = exts.getByType(ExtraPropertiesExtension.class);
        // Don't include Kotlin standard libraries, we absolutely do not need those bloats.
        props.set("kotlin.stdlib.default.dependency", "false");

        // Apply 'java', 'kotlin-jvm', and 'kotlin-kapt' plugins.
        plugins.apply("java");
        plugins.apply(KotlinPluginWrapper.class);
        plugins.apply(Kapt3GradleSubplugin.class);

        var ext = exts.create("entityAnno", EntityAnnoExtension.class);
        ext.getMindustryVersion().convention(defMindustryVersion);
        ext.getIsJitpack().convention(false);

        var fetchDir = project.getLayout().getBuildDirectory().dir("fetched");
        var fetchComps = tasks.register("fetchComps", t -> {
            t.getInputs().property("version", project.provider(ext.getMindustryVersion()::get));
            t.getOutputs().dir(fetchDir);

            t.doFirst(tt -> {
                var dir = fetchDir.get();
                var dirFi = new Fi(dir.getAsFile());
                dirFi.emptyDirectory();
                dirFi.mkdirs();

                var repository = ext.getIsJitpack().get() ? "MindustryJitpack" : "Mindustry";
                var version = ext.getMindustryVersion().get();

                Queue<Future<?>> fetches = new Queue<>();
                int[] remaining = {0, 0};

                Http.get("https://api.github.com/repos/Anuken/" + repository + "/contents/core/src/mindustry/entities/comp?ref=" + version)
                    .timeout(0)
                    .error(e -> { throw new RuntimeException(e); })
                    .block(res -> {
                        var list = Jval.read(res.getResultAsString()).asArray();
                        remaining[0] = remaining[1] = list.size;

                        var fetchPackage = ext.getFetchPackage().get();
                        var exec = Threads.executor("EntityAnno-Fetcher", list.size);

                        var loc = new Fi(new File(dir.getAsFile(), ext.getFetchPackage().get().replace('.', '/')));
                        loc.mkdirs();

                        for(var val : list){
                            fetches.addLast(exec.submit(() -> Http.get(val.getString("download_url"))
                                .timeout(0)
                                .error(e -> { throw new RuntimeException(e); })
                                .block(comp -> {
                                    var result = comp.getResultAsString();
                                    var name = val.getString("name");

                                    // Sanity checks, because this tends to happen to me.
                                    if(result.trim().replaceAll("\\s+", "").isEmpty()){
                                        throw new IllegalStateException("Couldn't write `" + name + "`, got an empty string; re-check your connection.");
                                    }

                                    loc
                                        .child(name)
                                        .writeString(procComp(result, fetchPackage), false);
                                })
                            ));
                        }

                        Threads.await(exec);
                    });

                while(!fetches.isEmpty()){
                    try{
                        fetches.removeFirst().get();
                        remaining[0]--;
                    }catch(InterruptedException | ExecutionException e){
                        throw new RuntimeException(e);
                    }
                }

                if(remaining[0] != 0) throw new IllegalStateException("Couldn't write all components; found " + remaining[0] + " unwritten.");
                tt.getLogger().lifecycle("Wrote {} components.", remaining[1]);
            });
        });

        tasks.register("procComps", t -> t.doFirst(tt -> {
            var fetchPackage = ext.getFetchPackage().get();
            var files = new Fi(new File(fetchDir.get().getAsFile(), fetchPackage.replace('.', '/'))).list();
            for(var file : files){
                if(!file.extEquals("java")) continue;
                file.writeString(procComp(file.readString("UTF-8"), fetchPackage), false, "UTF-8");
            }

            if(files.length == 0){
                tt.getLogger().warn("No fetched component files found. Either run `fetchComps`, or manually copy the files and run this task again.");
            }else{
                tt.getLogger().lifecycle("Processed {} components.", files.length);
            }
        }));

        project.afterEvaluate(p -> {
            // Configure KAPT extension and add annotation processor options.
            var kaptExt = exts.getByType(KaptExtension.class);
            kaptExt.setKeepJavacAnnotationProcessors(true);
            kaptExt.arguments(args -> {
                args.arg("modName", ext.getModName().get());
                args.arg("genPackage", ext.getGenPackage().get());
                args.arg("fetchPackage", ext.getFetchPackage().get());
                args.arg("revisionDir", ext.getRevisionDir().get().getAbsolutePath());
                return null;
            });

            // Add fetched sources as KAPT input, and enable compile avoidance.
            tasks.withType(Kapt.class, task -> {
                task.getInputs().files(fetchComps);
                task.getIncludeCompileClasspath().set(false);
            });

            // Add `fetchDir` as Java source sets.
            exts.getByType(JavaPluginExtension.class)
                .getSourceSets().getByName("main")
                .getJava().srcDirs(fetchDir);

            // Exclude fetched and generation source classes.
            tasks.withType(Jar.class, task -> {
                task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
                task.exclude(
                    ext.getFetchPackage().get().replace('.', '/') + "/**",
                    ext.getGenSrcPackage().get().replace('.', '/') + "/**"
                );
            });

            // Prevent running these tasks to speed up compile-time.
            var conf = tasks.findByPath("checkKotlinGradlePluginConfigurationErrors");
            if(conf != null) {
                conf.onlyIf(spec -> false);
            }

            tasks.withType(KotlinCompile.class, t -> t.onlyIf(spec -> false));
        });
    }

    public static String procComp(String source, String fetchPackage){
        return source
            .replace("mindustry.entities.comp", fetchPackage)
            .replace("mindustry.annotations.Annotations.*", "ent.anno.Annotations.*")
            .replaceAll("@Component\\((base = true|.)+\\)\n*", "@EntityComponent(base = true, vanilla = true)\n")
            .replaceAll("@Component\n*", "@EntityComponent(vanilla = true)\n")
            .replaceAll("@BaseComponent\n*", "@EntityBaseComponent\n")
            .replaceAll("@CallSuper\n*", "")
            .replaceAll("@Final\n*", "")
            .replaceAll("@EntityDef\\(*.*\\)*\n*", "");
    }
}
