package io.github.dajac.kaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Checks the rules of the public package on the types of the public API. */
class PublicApiTest {
    private static final List<Class<?>> PUBLIC_TYPES = List.of(
        Simulator.class,
        Topic.class,
        ProducerHandle.class,
        SnapshotListener.class,
        Registration.class,
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
        InvalidConfigException.class,
        SimulationStateException.class,
        SimulatorSnapshot.class,
        ProducerSnapshot.class,
        GroupSnapshot.class,
        AutopilotSnapshot.class,
        ConsumerSnapshot.class,
        ConsumerSnapshot.Raw.class,
        PartitionView.class,
        PartitionRef.class,
        Phase.class,
        GroupState.class,
        GroupType.class,
        PartitionState.class,
        ConsumerStatus.class,
        ProducerState.class
    );

    @Test
    void signaturesExposeNeitherKafkaNorInternalTypes() {
        for (var type : PUBLIC_TYPES) {
            for (var signatureType : signatureTypes(type)) {
                assertThat(signatureType).as(type.getSimpleName())
                    .doesNotContain("org.apache.kafka")
                    .doesNotContain("io.github.dajac.kaos.internal");
            }
        }
    }

    @Test
    void noTypeNameContainsTheProjectName() {
        for (var type : PUBLIC_TYPES) {
            assertThat(type.getSimpleName()).doesNotContainIgnoringCase("kaos");
        }
    }

    private static List<String> signatureTypes(Class<?> type) {
        var types = new ArrayList<String>();
        for (var method : type.getDeclaredMethods()) {
            if (isApi(method.getModifiers())) {
                types.add(method.getGenericReturnType().getTypeName());
                for (var parameter : method.getGenericParameterTypes()) {
                    types.add(parameter.getTypeName());
                }
            }
        }
        for (var constructor : type.getDeclaredConstructors()) {
            if (isApi(constructor.getModifiers())) {
                for (var parameter : constructor.getGenericParameterTypes()) {
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
