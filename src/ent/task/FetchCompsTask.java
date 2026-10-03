package ent.task;

import arc.util.*;
import arc.util.serialization.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;

import javax.inject.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpClient.*;
import java.net.http.HttpResponse.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** {@code :fetchComps} task, downloads Mindustry component sources to a temporary directory. */
public abstract class FetchCompsTask extends DefaultTask{
    /** @return {@code build} property of {@code version.properties}. */
    public abstract @Input Property<String> getMindustryVersion();

    /** @return {@code type} property of {@code version.properties}. */
    public abstract @Input Property<String> getMindustryType();

    /** @return The directory containing the unprocessed entity component source files. */
    public abstract @OutputDirectory DirectoryProperty getOutputDirectory();

    protected abstract @Inject FileSystemOperations getFileSystemOperations();

    @Inject
    public FetchCompsTask(ProjectLayout layout){
        getOutputDirectory().convention(layout.getBuildDirectory().dir("fetched-raw"));
    }

    private static <T, B, E extends Exception> T get(HttpClient http, BodyHandler<B> handler, String uri, Get<T, B, E> get) throws Exception{
        var request = HttpRequest.newBuilder(URI.create(uri)).GET().build();
        var response = http.send(request, handler);

        var in = response.body();
        try{
            if(response.statusCode() != 200)
                throw new IOException(String.format("HTTP %d from %s", response.statusCode(), response.uri()));

            return get.get(in);
        }finally{
            if(in instanceof AutoCloseable c) c.close();
        }
    }

    @TaskAction
    public void fetch(){
        var mindustryVersion = getMindustryVersion().get();
        var mindustryType = getMindustryType().get();

        var out = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(out));

        var outPath = out.toPath();
        try{
            Files.createDirectories(outPath);
        }catch(IOException e){
            throw new GradleException("Couldn't create output directory", e);
        }

        var http = HttpClient.newBuilder().followRedirects(Redirect.NORMAL).build();
        String version = switch(mindustryType){
            case "official" -> String.format("v%s", mindustryVersion);
            case "bleeding-edge" -> {
                try{
                    yield get(http, BodyHandlers.ofString(), String.format("https://api.github.com/repos/Anuken/MindustryBuilds/releases/tags/%s", mindustryVersion),
                        in -> Jval.read(in).getString("body").trim()
                    );
                }catch(Exception e){
                    throw new GradleException("Couldn't fetch bleeding-edge commit hash", e);
                }
            }
            default -> throw new GradleException(String.format("Invalid Mindustry version type `%s`", mindustryType));
        };

        CompletableFuture<GradleException> error = new CompletableFuture<>();
        var exec = Threads.executor("EntityAnno-Fetcher", OS.cores);
        var count = new AtomicInteger(0);

        try{
            get(http, BodyHandlers.ofString(), String.format("https://api.github.com/repos/Anuken/Mindustry/contents/core/src/mindustry/entities/comp?ref=%s", version), in -> {
                var list = Jval.read(in).asArray();
                for(var val : list){
                    var name = val.getString("name");
                    exec.submit(() -> {
                        try{
                            get(http, BodyHandlers.ofString(), val.getString("download_url"), comp -> Files.writeString(outPath.resolve(name), comp));
                            count.incrementAndGet();
                        }catch(Exception e){
                            error.complete(new GradleException(String.format("Couldn't download `%s`", name), e));
                        }
                    });
                }
                return null;
            });
        }catch(Exception e){
            throw new GradleException("Couldn't fetch component directory contents", e);
        }

        Threads.await(exec);
        if(error.isDone()) throw error.join();

        getLogger().lifecycle("Wrote {} components.", count.get());
    }

    private interface Get<T, B, E extends Exception>{
        T get(B body) throws E;
    }
}
