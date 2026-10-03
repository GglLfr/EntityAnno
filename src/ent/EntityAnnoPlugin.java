package ent;

import ent.task.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.plugins.*;
import org.gradle.api.tasks.bundling.*;
import org.gradle.api.tasks.compile.*;

import java.io.*;
import java.util.*;

/**
 * Gradle plugin for creating necessary entity component generation classes.
 * @author GlFolker
 */
public class EntityAnnoPlugin implements Plugin<Project>{
    @Override
    public void apply(Project project){
        var exts = project.getExtensions();
        var layout = project.getLayout();
        var objects = project.getObjects();
        var plugins = project.getPlugins();
        var tasks = project.getTasks();

        var ext = exts.create("entityAnno", EntityAnnoExtension.class);
        var revisionDir = ext.getRevisionDir();
        var fetchPackage = ext.getFetchPackage();
        var genSrcPackage = ext.getGenSrcPackage();
        var genPackage = ext.getGenPackage();

        // Apply 'java' plugin.
        plugins.apply("java");

        String mindustryVersion, mindustryType;
        try(var stream = EntityAnnoPlugin.class.getClassLoader().getResourceAsStream("version.properties")){
            if(stream == null)
                throw new IOException("Missing resource; fix your dependency specs in `build.gradle[.kts]`");

            var props = new Properties();
            props.load(stream);

            mindustryVersion = props.getProperty("build");
            mindustryType = props.getProperty("type");

            if(mindustryVersion == null || mindustryType == null)
                throw new IOException("Missing `build` or `type` properties");
        }catch(IOException e){
            throw new GradleException("Couldn't read `version.properties`", e);
        }

        var fetchComps = tasks.register("fetchComps", FetchCompsTask.class, t -> {
            t.setDescription("Fetches Mindustry raw entity sources.");

            t.getMindustryVersion().set(mindustryVersion);
            t.getMindustryType().set(mindustryType);
        });

        var processComps = tasks.register("processComps", ProcessCompsTask.class, t -> {
            t.setDescription("Processes Mindustry raw entity sources into a vanilla-source for EntityAnno.");

            t.getSources().from(fetchComps.flatMap(FetchCompsTask::getOutputDirectory));
            t.getFetchPackage().set(fetchPackage);
        });

        // Add `processComps`'s output as a Java source set.
        exts.getByType(JavaPluginExtension.class)
            .getSourceSets().named("main", sourceSet ->
                sourceSet.getJava().srcDir(processComps.flatMap(ProcessCompsTask::getOutputDirectory))
            );

        // Configure annotation processor inputs.
        tasks.withType(JavaCompile.class).configureEach(t -> {
            var cmd = objects.newInstance(EntityAnnoArguments.class);
            var srcCacheDir = layout.getBuildDirectory().dir("src-cache");

            cmd.getArguments().add(genPackage.map(p -> "-AgenPackage=" + p));
            cmd.getArguments().add(fetchPackage.map(p -> "-AfetchPackage=" + p));
            cmd.getArguments().add(srcCacheDir.map(p -> "-AcacheDir=" + p.getAsFile().getAbsolutePath()));
            cmd.getArguments().add(revisionDir.map(p -> "-ArevisionDir=" + p.getAsFile().getAbsolutePath()));

            t.getOptions().getCompilerArgumentProviders().add(cmd);
        });

        // Exclude fetched and generation source classes.
        tasks.withType(Jar.class).configureEach(t -> {
            t.getInputs().property("fetchPackage", fetchPackage);
            t.getInputs().property("genSrcPackage", genSrcPackage);

            t.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            t.exclude(e -> {
                var path = e.getRelativePath().getPathString();
                var fetch = fetchPackage.get().replace('.', '/');
                var generated = genSrcPackage.get().replace('.', '/');

                return path.equals(fetch) || path.startsWith(fetch + "/") || path.equals(generated) || path.startsWith(generated + "/");
            });
        });
    }
}