/*
 * Copyright 2026 The Kaos Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
