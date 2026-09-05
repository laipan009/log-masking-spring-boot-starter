package io.github.laipan009.logmasking.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

/**
 * Shared member-to-field naming rules for the annotation index and {@link LogMasker#field}, ensuring
 * consistent names.
 */
public final class MemberNames {

    private static final Logger LOG = LoggerFactory.getLogger(MemberNames.class);

    private MemberNames() {
    }

    public static String propertyName(Method method) {
        var name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2) {
            return decapitalize(name.substring(2));
        }
        return name;
    }

    public static String jsonProperty(AnnotatedElement element) {
        var jsonProperty = element.getAnnotation(JsonProperty.class);
        return jsonProperty == null || jsonProperty.value().isEmpty() ? null : jsonProperty.value();
    }

    public static PropertyNamingStrategy namingStrategy(Class<?> type, PropertyNamingStrategy fallback) {
        var jsonNaming = type.getAnnotation(JsonNaming.class);
        if (jsonNaming != null && jsonNaming.value() != PropertyNamingStrategy.class) {
            try {
                return jsonNaming.value().getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.debug("cannot instantiate @JsonNaming strategy of {}", type.getName(), e);
            }
        }
        return fallback;
    }

    public static String translate(PropertyNamingStrategy naming, String name) {
        return naming instanceof PropertyNamingStrategies.NamingBase namingBase ? namingBase.translate(name) : name;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
