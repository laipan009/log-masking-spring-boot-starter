package io.github.laipan009.logmasking.core;

import com.fasterxml.jackson.databind.PropertyNamingStrategy;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Extracts the field name and strategy from a getter reference. Reflection is cached per lambda class,
 * effectively per call site.
 */
final class MethodReferenceResolver {

    record ResolvedMember(String name, MaskStrategy strategy, boolean unlisted) {
    }

    private final MaskingPolicyRegistry policy;
    private final PropertyNamingStrategy globalNaming;
    private final Consumer<String> onUnlisted;

    /**
     * Creates an empty holder without requiring a lambda instance. {@link ClassValue} allows unloading
     * with the class; the holder is populated on first use.
     */
    private final ClassValue<AtomicReference<ResolvedMember>> cache = new ClassValue<>() {
        @Override
        protected AtomicReference<ResolvedMember> computeValue(Class<?> type) {
            return new AtomicReference<>();
        }
    };

    MethodReferenceResolver(MaskingPolicyRegistry policy, PropertyNamingStrategy globalNaming,
                            Consumer<String> onUnlisted) {
        this.policy = policy;
        this.globalNaming = globalNaming;
        this.onUnlisted = onUnlisted;
    }

    ResolvedMember resolve(SerializableFunction<?, ?> getter) {
        var holder = cache.get(getter.getClass());
        var member = holder.get();
        if (member != null) {
            return member;
        }
        member = introspect(getter);
        if (holder.compareAndSet(null, member)) {
            if (member.unlisted()) {
                onUnlisted.accept(member.name());
            }
            return member;
        }
        return holder.get();
    }

    ResolvedMember introspect(SerializableFunction<?, ?> getter) {
        var lambda = serializedForm(getter);
        if (lambda == null || !isUnboundMethodReference(lambda)) {
            throw new IllegalArgumentException("field(): expected a method reference (Dto::getFio), not a lambda body");
        }
        var type = implClass(lambda, getter.getClass().getClassLoader());
        var method = findMethod(type, lambda.getImplMethodName());
        if (method.isSynthetic()) {
            throw new IllegalArgumentException("field(): expected a method reference (Dto::getFio), not a lambda body");
        }
        var rawName = MemberNames.propertyName(method);
        var field = findField(type, rawName);
        var component = firstNonNull(findRecordComponent(type, rawName), findRecordComponent(type, method.getName()));
        var naming = MemberNames.namingStrategy(type, globalNaming);

        var jsonName = firstNonNull(MemberNames.jsonProperty(method),
                field == null ? null : MemberNames.jsonProperty(field),
                component == null ? null : MemberNames.jsonProperty(component));
        var name = jsonName != null ? jsonName : MemberNames.translate(naming, rawName);

        var mask = firstNonNull(method.getAnnotation(Mask.class),
                field == null ? null : field.getAnnotation(Mask.class),
                component == null ? null : component.getAnnotation(Mask.class));
        if (mask != null) {
            return new ResolvedMember(name, mask.strategy(), false);
        }
        var spellings = new LinkedHashSet<String>();
        spellings.add(name);
        spellings.add(rawName);
        spellings.add(MemberNames.translate(naming, rawName));
        for (var spelling : spellings) {
            var strategy = policy.strategyFor(spelling);
            if (strategy != null) {
                return new ResolvedMember(name, strategy, false);
            }
            if (policy.isSecretHeader(spelling)) {
                return new ResolvedMember(name, MaskStrategy.FULL, false);
            }
        }
        return new ResolvedMember(name, MaskStrategy.FULL, true);
    }

    /**
     * The lambda class is hidden and {@code writeReplace} is private, so reflective access can fail in
     * named modules. Serialization invokes it from {@code java.base} and passes the result to {@code
     * replaceObject}; no bytes are written to the stream.
     */
    private static SerializedLambda serializedForm(Object lambda) {
        var captured = new SerializedLambda[1];
        try (var out = new ObjectOutputStream(OutputStream.nullOutputStream()) {
            {
                enableReplaceObject(true);
            }

            @Override
            protected Object replaceObject(Object obj) {
                if (obj instanceof SerializedLambda form) {
                    captured[0] = form;
                    return null;
                }
                return obj;
            }
        }) {
            out.writeObject(lambda);
        } catch (IOException e) {
            return null;
        }
        return captured[0];
    }

    /**
     * Rejects lambda bodies, bound references and static methods because a field name cannot be inferred
     * from them.
     */
    private static boolean isUnboundMethodReference(SerializedLambda lambda) {
        var kind = lambda.getImplMethodKind();
        return (kind == MethodHandleInfo.REF_invokeVirtual || kind == MethodHandleInfo.REF_invokeInterface)
                && !lambda.getImplMethodName().startsWith("lambda$")
                && lambda.getCapturedArgCount() == 0;
    }

    /**
     * The lambda's loader already resolved {@code implClass} during linking.
     */
    private static Class<?> implClass(SerializedLambda lambda, ClassLoader loader) {
        var className = lambda.getImplClass().replace('/', '.');
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("field(): class " + className + " not found", e);
        }
    }

    /**
     * Tries {@code getMethod} first to include inherited public and interface default methods. Walking
     * superclasses alone misses defaults because {@code implClass} is the invocation type, not necessarily
     * the declaring type.
     */
    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            for (var current = type; current != null; current = current.getSuperclass()) {
                try {
                    return current.getDeclaredMethod(name);
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        throw new IllegalArgumentException("field(): method " + name + "() not found in " + type.getName());
    }

    private static Field findField(Class<?> type, String name) {
        for (var current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static AnnotatedElement findRecordComponent(Class<?> type, String name) {
        var components = type.getRecordComponents();
        if (components == null) {
            return null;
        }
        for (var component : components) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (var value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
