package ent;

import org.gradle.api.file.*;
import org.gradle.api.provider.*;

/**
 * Necessary extension data for {@link EntityAnnoPlugin}.
 * @author GlFolker
 */
public interface EntityAnnoExtension{
    /** @return The location to store entity revision data. */
    DirectoryProperty getRevisionDir();

    /** @return Package name for fetched vanilla component classes, typically {@code modname.fetched}. */
    Property<String> getFetchPackage();

    /** @return Package name for "origin" component classes, typically {@code modname.entities.comp}. */
    Property<String> getGenSrcPackage();

    /** @return Package name for root generated package, typically {@code modname.gen}. */
    Property<String> getGenPackage();
}
