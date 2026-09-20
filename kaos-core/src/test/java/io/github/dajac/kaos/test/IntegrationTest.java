package io.github.dajac.kaos.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class as an integration test.
 *
 * <p>The annotation tags the class with {@value #TAG}. The build uses the
 * tag, not the class name, to split the tests: surefire excludes it,
 * failsafe runs only the tests that carry it, and {@code -DskipITs} skips
 * them. It also registers {@link KafkaClusterExtension}, so the tests can
 * declare a {@link KafkaCluster} parameter. The broker starts only when a
 * test asks for it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag(IntegrationTest.TAG)
@ExtendWith(KafkaClusterExtension.class)
public @interface IntegrationTest {

    /** The JUnit tag that the parent POM gives to surefire and failsafe. */
    String TAG = "integration";
}
