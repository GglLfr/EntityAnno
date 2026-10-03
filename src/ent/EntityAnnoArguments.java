package ent;

import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.process.*;

/** Command-line compiler argument provider for the annotation processor. */
public abstract class EntityAnnoArguments implements CommandLineArgumentProvider{
    /** @return Compiler arguments for the annotation processor. */
    public abstract @Input ListProperty<String> getArguments();

    @Override
    public Iterable<String> asArguments(){
        return getArguments().get();
    }
}
