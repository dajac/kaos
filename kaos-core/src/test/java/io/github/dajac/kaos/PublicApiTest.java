package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Checks the rules of the public package on the types of the spec and validation model. */
class PublicApiTest {
    private static final List<Class<?>> PUBLIC_TYPES = List.of(
        Simulator.class,
        SimulatorSpec.class,
        TopicSpec.class,
        ProducerSpec.class,
        GroupSpec.class,
        ConsumerSpec.class,
        ConsumerSettings.class,
        ConsumerOverrides.class,
        ProducerOptions.class,
        GroupOptions.class,
        ConsumerOptions.class,
        ConfigMetadata.class,
        ConfigMetadata.Field.class,
        ConfigMetadata.Range.class,
        ConfigMetadata.NameRule.class,
        ConfigMetadata.AssignorOptions.class,
        Fault.class,
        Protocol.class,
        Violation.class,
        SimulationException.class,
        InvalidConfigException.class);

    @Test
    void signaturesExposeNeitherKafkaNorInternalTypes() {
        for (Class<?> type : PUBLIC_TYPES) {
            for (String signatureType : signatureTypes(type)) {
                assertThat(signatureType).as(type.getSimpleName())
                    .doesNotContain("org.apache.kafka")
                    .doesNotContain("io.github.dajac.kaos.internal");
            }
        }
    }

    @Test
    void noTypeNameContainsTheProjectName() {
        for (Class<?> type : PUBLIC_TYPES) {
            assertThat(type.getSimpleName()).doesNotContainIgnoringCase("kaos");
        }
    }

    private static List<String> signatureTypes(Class<?> type) {
        List<String> types = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (isApi(method.getModifiers())) {
                types.add(method.getGenericReturnType().getTypeName());
                for (Type parameter : method.getGenericParameterTypes()) {
                    types.add(parameter.getTypeName());
                }
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isApi(constructor.getModifiers())) {
                for (Type parameter : constructor.getGenericParameterTypes()) {
                    types.add(parameter.getTypeName());
                }
            }
        }
        return types;
    }

    private static boolean isApi(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }
}
