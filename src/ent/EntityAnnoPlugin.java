package ent;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.plugins.*;
import org.gradle.api.tasks.bundling.*;
import org.gradle.api.tasks.compile.*;

import java.io.*;
import java.util.concurrent.*;

/**
 * Gradle plugin for creating necessary entity component generation classes.
 * @author GlFolker
 */
public class EntityAnnoPlugin implements Plugin<Project>{
    @Override
    public void apply(Project project){
        var plugins = project.getPlugins();
        var exts = project.getExtensions();
        var tasks = project.getTasks();

        var ext = exts.create("entityAnno", EntityAnnoExtension.class);
        // Apply 'java' plugin.
        plugins.apply("java");

        var fetchDir = project.getLayout().getBuildDirectory().dir("fetched");
        var srcCacheDir = project.getLayout().getBuildDirectory().dir("src-cache");

        var fetchComps = tasks.register("fetchComps", t -> {
            t.getInputs().property("version", ext.getMindustryVersion());
            t.getOutputs().dir(fetchDir);

            t.doFirst(tt -> {
                var dir = fetchDir.get();
                var dirFi = new Fi(dir.getAsFile());
                dirFi.emptyDirectory();
                dirFi.mkdirs();

                var versionSelect = ext.getMindustryVersion().get();
                String version = switch(versionSelect){
                    case "latest" -> {
                        String[] tag = {null};
                        Http.get("https://api.github.com/repos/Anuken/Mindustry/releases/latest")
                            .timeout(0)
                            .error(e -> {
                                throw new RuntimeException(e);
                            })
                            .block(res -> tag[0] = Jval.read(res.getResultAsString()).get("tag_name").asString());
                        yield tag[0];
                    }
                    case "be" -> {
                        String[] tag = {null};
                        Http.get("https://api.github.com/repos/Anuken/Mindustry/commits?per_page=1")
                            .timeout(0)
                            .error(e -> {
                                throw new RuntimeException(e);
                            })
                            .block(res -> tag[0] = Jval.read(res.getResultAsString()).asArray().get(0).get("sha").asString());
                        yield tag[0];
                    }
                    default -> versionSelect;
                };

                Queue<Future<?>> fetches = new Queue<>();
                int[] remaining = {0, 0};

                Http.get(String.format("https://api.github.com/repos/Anuken/Mindustry/contents/core/src/mindustry/entities/comp?ref=%s", version))
                    .timeout(0)
                    .error(e -> {
                        throw new RuntimeException(e);
                    })
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
                                .error(e -> {
                                    throw new RuntimeException(e);
                                })
                                .block(comp -> {
                                    var result = comp.getResultAsString();
                                    var name = val.getString("name");

                                    // Sanity checks, because this tends to happen to me.
                                    if(result.trim().replaceAll("\\s+", "").isEmpty()){
                                        throw new IllegalStateException(String.format("Couldn't write `%s`, got an empty string; re-check your connection.", name));
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

                if(remaining[0] != 0)
                    throw new IllegalStateException(String.format("Couldn't write all components; found %s unwritten.", remaining[0]));
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
            // Add `fetchDir` as Java source sets.
            exts.getByType(JavaPluginExtension.class)
                .getSourceSets().getByName("main")
                .getJava().srcDirs(fetchDir);

            // Add fetched sources as `compileJava` input.
            tasks.withType(JavaCompile.class, task -> {
                task.getInputs().files(fetchComps);

                var args = task.getOptions().getCompilerArgs();
                args.add("-implicit:none");
                args.add(String.format("-AmodName=%s", ext.getModName().get()));
                args.add(String.format("-AgenPackage=%s", ext.getGenPackage().get()));
                args.add(String.format("-AfetchPackage=%s", ext.getFetchPackage().get()));
                args.add(String.format("-AcacheDir=%s", srcCacheDir.get().getAsFile().getAbsolutePath()));
                args.add(String.format("-ArevisionDir=%s", ext.getRevisionDir().get().getAbsolutePath()));
            });

            // Exclude fetched and generation source classes.
            tasks.withType(Jar.class, task -> {
                task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
                task.exclude(
                    ext.getFetchPackage().get().replace('.', '/') + "/**",
                    ext.getGenSrcPackage().get().replace('.', '/') + "/**"
                );
            });
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
