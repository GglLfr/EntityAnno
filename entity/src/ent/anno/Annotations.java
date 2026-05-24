package ent.anno;

import java.lang.annotation.*;

/**
 * Source-level annotations to generate entity classes from components.
 * @author GlFolker
 * @author Anuke
 */
public final class Annotations{
    private Annotations(){
        throw new AssertionError();
    }

    /** Defines a class providing static entries of IO handlers. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface TypeIOHandler{}

    /** Indicates that this class is an entities component. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface EntityComponent{
        /** @return Whether this is a fetched component; in that case, do not generate interfaces. */
        boolean vanilla() default false;

        /** @return Whether the component should generate a base class for itself. */
        boolean base() default false;
    }

    /** All entities components will inherit from this. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface EntityBaseComponent{}

    /** Whether this interface wraps an entities component. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface EntityInterface{}

    /** Generates an entities definition from given components. */
    @Retention(RetentionPolicy.SOURCE)
    public @interface EntityDef{
        /** @return The interfaces that will be inherited by the generated entities class. */
        Class<?>[] value();

        /** @return Whether the class can serialize itself. */
        boolean serialize() default true;

        /** @return Whether the class can write/read to/from save files. */
        boolean genIO() default true;

        /** @return Whether the class is poolable. */
        boolean pooled() default false;
    }

    /** Indicates that this entities (!) class should be mapped. */
    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EntityPoint{}

    /** Indicates that a field will be interpolated when synced. */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncField{
        /** @return True if the field is linearly interpolated. Otherwise, it's interpolated as an angle. */
        boolean value();

        /** @return True if the field is clamped to 0-1. */
        boolean clamped() default false;
    }

    /** Indicates that a field will not be read from the server when syncing the local player state. */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncLocal{}

    /** Indicates that a field should not be synced to clients (but may still be non-transient) */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface NoSync{}

    /** Indicates that a field should be synced, but not serialized */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface NoSerialize{}

    /** Indicates that the field annotated with this came from another component class. */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Import{}

    /** Won't generate a setter for this field. */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReadOnly{}

    /** Whether this method replaces the actual method in the base class. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Replace{
        /** @return The priority of this replacer. */
        float value() default 0f;
    }

    /** Whether this method is implemented in compile-time. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface InternalImpl{}

    /** Used for method appender sorting. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface MethodPriority{
        /** @return The priority. */
        float value();
    }

    /** Appends this {@code add()}/{@code remove()} method before the {@code if([!]added)} check. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface BypassGroupCheck{}

    /** Will not replace {@code return;} to {@code break [block];}, hence breaking the entire method statement. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface BreakAll{}

    /** Removes a component-specific method implementation. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Remove{
        /** @return The component specification to remove. */
        Class<?> value();
    }

    /** Will only implement this method if the entities inherits these certain components. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Extend{
        /** @return The component specification to check. */
        Class<?>[] value();

        /** @return {@code true} if only one component needs to be extended, {@code false} otherwise. */
        boolean any() default false;
    }

    /** Inserts this parameter-less method into another void method. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Insert{
        /**
         * @return The target method described in {@link String} with the format {@code <methodName>(<paramType>...)}.
         * For example, when targeting {@code void call(String arg, int prior)}, the target descriptor must be
         * {@code call(java.lang.String, int)}
         */
        String value();

        /** @return The component-specific method implementation to target. */
        Class<?> block() default Void.class;

        /** @return Whether the call to this method is after the default or not. */
        boolean after() default true;
    }

    /** Wraps a component-specific method implementation with this boolean parameterless method. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.SOURCE)
    public @interface Wrap{
        /**
         * @return The target method described in {@link String} with the format {@code <methodName>(<paramType>...)}.
         * For example, when targeting {@code void call(String arg, int prior)}, the target descriptor must be
         * {@code call(java.lang.String, int)}
         */
        String value();

        /** @return The component-specific method implementation to target. */
        Class<?> block() default Void.class;
    }

    /** Prevents this component from getting added into an entities group, specified by the group's element type. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface ExcludeGroups{
        /** @return The excluded group's element type. */
        Class<?>[] value();
    }
}
