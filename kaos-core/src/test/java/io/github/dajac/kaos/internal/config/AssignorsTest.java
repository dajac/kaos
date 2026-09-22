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
package io.github.dajac.kaos.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dajac.kaos.ConfigMetadata;
import io.github.dajac.kaos.Protocol;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssignorsTest {

    @Test
    void everyClassicAssignorOfTheMetadataMapsToAClientAssignor() throws ClassNotFoundException {
        for (var name : ConfigMetadata.get().assignors(Protocol.CLASSIC).names()) {
            var className = Assignors.classicAssignorClass(name).orElseThrow();

            assertThat(ConsumerPartitionAssignor.class).as(name).isAssignableFrom(Class.forName(className));
        }
    }

    @Test
    void classicAssignorClasses() {
        assertThat(Assignors.classicAssignorClass("range"))
            .contains("org.apache.kafka.clients.consumer.RangeAssignor");
        assertThat(Assignors.classicAssignorClass("roundrobin"))
            .contains("org.apache.kafka.clients.consumer.RoundRobinAssignor");
        assertThat(Assignors.classicAssignorClass("sticky"))
            .contains("org.apache.kafka.clients.consumer.StickyAssignor");
        assertThat(Assignors.classicAssignorClass("cooperative-sticky"))
            .contains("org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
    }

    @Test
    void theEmptyAndUnknownNamesHaveNoClassicClass() {
        assertThat(Assignors.classicAssignorClass("")).isEmpty();
        assertThat(Assignors.classicAssignorClass("uniform")).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "'',                 true",
        "range,              true",
        "roundrobin,         true",
        "sticky,             true",
        "cooperative-sticky, false",
    })
    void onlyCooperativeStickyIsNotEager(String assignor, boolean expected) {
        assertThat(Assignors.isEagerClassic(assignor)).isEqualTo(expected);
    }

    @Test
    void remoteAssignorIsTheNameUnlessEmpty() {
        assertThat(Assignors.remoteAssignor("uniform")).contains("uniform");
        assertThat(Assignors.remoteAssignor("my-custom-assignor")).contains("my-custom-assignor");
        assertThat(Assignors.remoteAssignor("")).isEmpty();
    }

    @Test
    void validityFollowsTheMetadata() {
        assertThat(Assignors.isValid(Protocol.CLASSIC, "sticky")).isTrue();
        assertThat(Assignors.isValid(Protocol.CLASSIC, "")).isTrue();
        assertThat(Assignors.isValid(Protocol.CLASSIC, "uniform")).isFalse();
        assertThat(Assignors.isValid(Protocol.CONSUMER, "anything")).isTrue();
    }
}
