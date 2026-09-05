package io.github.laipan009.logmasking.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;
import io.github.laipan009.logmasking.core.Mask;
import io.github.laipan009.logmasking.core.MaskFieldIndex;
import io.github.laipan009.logmasking.core.MaskScope;
import io.github.laipan009.logmasking.core.MaskStrategy;
import io.github.laipan009.logmasking.core.MemberNames;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link MaskFieldIndex} by scanning class files for the {@link Mask} descriptor before loading
 * matching classes for reflection.
 * <p>The byte filter avoids loading thousands of unrelated application classes when only a few are
 * annotated.
 */
public final class MaskFieldIndexScanner {

    private static final Logger LOG = LoggerFactory.getLogger(MaskFieldIndexScanner.class);

    private static final String MASK_DESCRIPTOR = "L" + Mask.class.getName().replace('.', '/') + ";";
    private static final int DESCRIBED_NAMES = 20;

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourceResolver;
    private final MetadataReaderFactory metadataReaderFactory;
    private final ClassLoader classLoader;

    public MaskFieldIndexScanner(ObjectMapper objectMapper, ClassLoader classLoader) {
        this.objectMapper = objectMapper;
        this.classLoader = classLoader;
        resourceResolver = new PathMatchingResourcePatternResolver(classLoader);
        metadataReaderFactory = new CachingMetadataReaderFactory(resourceResolver);
    }

    public MaskFieldIndex scan(Collection<String> packages) {
        if (packages == null || packages.isEmpty()) {
            LOG.debug("MaskFieldIndex: no packages to scan, @Mask will only work through LogMasker.field()");
            return MaskFieldIndex.EMPTY;
        }
        var started = System.nanoTime();
        var collected = new Collected();
        var scanned = 0;
        for (var candidatePackage : packages) {
            scanned += scanPackage(candidatePackage, collected);
        }
        var index = new MaskFieldIndex(collected.names, collected.strategies, scanned);
        LOG.info("MaskFieldIndex: scanned {} classes in {} in {} ms, {} field names with @Mask: {}",
                index.scannedClasses(), packages, (System.nanoTime() - started) / 1_000_000,
                index.fieldNames().size(), index.describe(DESCRIBED_NAMES));
        return index;
    }

    private int scanPackage(String basePackage, Collected collected) {
        var pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                + ClassUtils.convertClassNameToResourcePath(basePackage) + "/**/*.class";
        Resource[] resources;
        try {
            resources = resourceResolver.getResources(pattern);
        } catch (Exception e) {
            LOG.warn("MaskFieldIndex: cannot scan package {}, @Mask index stays incomplete", basePackage, e);
            return 0;
        }
        for (var resource : resources) {
            collectIfAnnotated(resource, collected);
        }
        return resources.length;
    }

    private void collectIfAnnotated(Resource resource, Collected collected) {
        try {
            if (!resource.isReadable() || !containsMaskDescriptor(resource)) {
                return;
            }
            var className = metadataReaderFactory.getMetadataReader(resource).getClassMetadata().getClassName();
            collect(ClassUtils.forName(className, classLoader), collected);
        } catch (Exception | LinkageError e) {
            LOG.debug("MaskFieldIndex: skipped unreadable class {}", resource, e);
        }
    }

    private boolean containsMaskDescriptor(Resource resource) throws java.io.IOException {
        // ISO-8859-1 preserves each byte, making string search equivalent to byte search.
        var bytecode = new String(resource.getContentAsByteArray(), StandardCharsets.ISO_8859_1);
        return bytecode.contains(MASK_DESCRIPTOR);
    }

    private void collect(Class<?> type, Collected collected) {
        if (type.isInterface() || type.isAnnotation() || type.isAnonymousClass() || type.isSynthetic()) {
            return;
        }
        var naming = namingStrategy(type);
        var recordComponents = type.getRecordComponents();
        if (recordComponents != null) {
            for (var component : recordComponents) {
                add(collected, type, component, component.getName(), naming);
            }
        }
        for (var field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                add(collected, type, field, field.getName(), naming);
            }
        }
        for (var method : type.getDeclaredMethods()) {
            if (!method.isSynthetic() && method.getParameterCount() == 0
                    && !Modifier.isStatic(method.getModifiers())) {
                add(collected, type, method, MemberNames.propertyName(method), naming);
            }
        }
    }

    /**
     * Always includes the Java field name as well as the wire name: JSON may contain {@code last_name},
     * while toString emits {@code lastName}. Indexing both favors additional coverage.
     */
    private void add(Collected collected, Class<?> type, AnnotatedElement element,
                     String rawName, PropertyNamingStrategy naming) {
        var mask = element.getAnnotation(Mask.class);
        if (mask == null) {
            return;
        }
        if (mask.scope() != MaskScope.ALL) {
            return;
        }
        var jsonProperty = MemberNames.jsonProperty(element);
        if (jsonProperty != null) {
            collected.add(jsonProperty, mask.strategy());
        }
        collected.add(rawName, mask.strategy());
        collected.add(MemberNames.translate(naming, rawName), mask.strategy());
    }

    private static final class Collected {

        private final Set<String> names = new LinkedHashSet<>();
        private final Map<String, MaskStrategy> strategies = new LinkedHashMap<>();

        /**
         * Each spelling (Java, {@code @JsonProperty}, {@code @JsonNaming}) receives the same strategy. Full
         * masking needs no explicit map entry because {@code maskedFields()} already implies it.
         */
        private void add(String name, MaskStrategy strategy) {
            names.add(name);
            if (strategy != MaskStrategy.FULL) {
                strategies.put(name, strategy);
            }
        }
    }

    /**
     * Resolves the JSON name using class-level {@code @JsonNaming}, falling back to the primary {@link
     * ObjectMapper}'s naming strategy.
     */
    private PropertyNamingStrategy namingStrategy(Class<?> type) {
        return MemberNames.namingStrategy(type, objectMapper.getSerializationConfig().getPropertyNamingStrategy());
    }
}
