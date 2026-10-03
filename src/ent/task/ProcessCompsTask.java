package ent.task;

import ent.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;

import javax.inject.*;
import java.io.*;
import java.nio.file.*;

/** {@code :processComps} task, processes Mindustry component sources as EntityAnno template inputs. */
public abstract class ProcessCompsTask extends DefaultTask{
    /** @return {@link FetchCompsTask#getOutputDirectory()}. */
    public abstract @InputFiles @PathSensitive(PathSensitivity.RELATIVE) ConfigurableFileCollection getSources();

    /** @return {@link EntityAnnoExtension#getFetchPackage()}. */
    public abstract @Input Property<String> getFetchPackage();

    /** @return The directory containing the processed entity component source files. */
    public abstract @OutputDirectory DirectoryProperty getOutputDirectory();

    protected abstract @Inject FileSystemOperations getFileSystemOperations();

    @Inject
    public ProcessCompsTask(ProjectLayout layout){
        getOutputDirectory().convention(layout.getBuildDirectory().dir("fetched"));
    }

    @TaskAction
    public void process(){
        var fetchPackage = getFetchPackage().get();

        var out = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(out));

        var fetchDir = out.toPath().resolve(fetchPackage.replace('.', '/'));
        try{
            Files.createDirectories(fetchDir);
        }catch(IOException e){
            throw new GradleException("Couldn't create `fetched` directory", e);
        }

        for(var file : getSources().getAsFileTree()){
            var name = file.getName();
            try{
                var contents = Files.readString(file.toPath());
                contents = contents.replace("mindustry.entities.comp", fetchPackage)
                    .replace("mindustry.annotations.Annotations.*", "ent.anno.Annotations.*")
                    .replaceAll("@Component\\((base = true|.)+\\)\n*", "@EntityComponent(base = true, vanilla = true)\n")
                    .replaceAll("@Component\n*", "@EntityComponent(vanilla = true)\n")
                    .replaceAll("@BaseComponent\n*", "@EntityBaseComponent\n")
                    .replaceAll("@CallSuper\n*", "")
                    .replaceAll("@Final\n*", "")
                    .replaceAll("@EntityDef\\(*.*\\)*\n*", "");

                Files.writeString(fetchDir.resolve(name), contents);
            }catch(IOException e){
                throw new GradleException(String.format("Couldn't process `%s`", name), e);
            }
        }
    }
}