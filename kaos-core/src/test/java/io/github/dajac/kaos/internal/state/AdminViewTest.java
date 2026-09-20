package io.github.dajac.kaos.internal.state;

import static io.github.dajac.kaos.internal.state.StatusDeriverTest.refs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dajac.kaos.GroupState;
import io.github.dajac.kaos.GroupType;
import io.github.dajac.kaos.PartitionRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class AdminViewTest {

    /** {@code target} is "none" when the coordinator reports no target assignment. */
    @ParameterizedTest(name = "{0} assignment={1} target={2} -> {3}")
    @CsvSource({
        "CONSUMER, t-0|t-1, t-0,  t-0",
        "CONSUMER, t-0|t-1, '',   ''",
        "CONSUMER, t-0|t-1, none, ''",
        "CLASSIC,  t-0|t-1, none, t-0|t-1",
        "CLASSIC,  '',      none, ''",
        "CLASSIC,  t-0|t-1, t-0,  t-0",
        "UNKNOWN,  t-0|t-1, none, t-0|t-1",
    })
    void targetAssignmentMirrorsTheAssignmentOutsideOfAConsumerGroup(
        GroupType type,
        String assignment,
        String target,
        String expected
    ) {
        var member = new AdminView.Member(
            "g-consumer-0",
            "member-0",
            "",
            3,
            refs(assignment),
            "none".equals(target) ? Optional.empty() : Optional.of(refs(target))
        );
        var view = new AdminView(type, GroupState.STABLE, 3, 3, List.of(member), Map.of());

        assertThat(view.targetAssignmentOf(member)).isEqualTo(refs(expected));
    }

    @Test
    void findsAMemberByClientId() {
        var member = new AdminView.Member("g-consumer-1", "member-1", null, 3, Set.of(), Optional.empty());
        var view = new AdminView(GroupType.CONSUMER, GroupState.STABLE, 3, 3, List.of(member), Map.of());

        assertThat(view.member("g-consumer-1")).contains(member);
        assertThat(view.member("g-consumer-2")).isEmpty();
        assertThat(member.instanceId()).isEmpty();
    }

    @Test
    void theUnknownViewHasNothing() {
        var view = AdminView.unknown();

        assertThat(view.type()).isEqualTo(GroupType.UNKNOWN);
        assertThat(view.state()).isEqualTo(GroupState.UNKNOWN);
        assertThat(view.epoch()).isEqualTo(-1);
        assertThat(view.targetAssignmentEpoch()).isEqualTo(-1);
        assertThat(view.members()).isEmpty();
        assertThat(view.lag()).isEmpty();
    }

    @Test
    void copiesItsCollections() {
        var assignment = new HashSet<>(Set.of(new PartitionRef("t", 0)));
        var target = new HashSet<>(Set.of(new PartitionRef("t", 0)));
        var member = new AdminView.Member("c", "m", "", 1, assignment, Optional.of(target));
        var members = new ArrayList<>(List.of(member));
        var lag = new HashMap<>(Map.of(new PartitionRef("t", 0), 4L));
        var view = new AdminView(GroupType.CONSUMER, GroupState.STABLE, 1, 1, members, lag);

        assignment.clear();
        target.clear();
        members.clear();
        lag.clear();

        assertThat(member.assignment()).hasSize(1);
        assertThat(member.targetAssignment().orElseThrow()).hasSize(1);
        assertThat(view.members()).hasSize(1);
        assertThat(view.lag()).hasSize(1);
        assertThatThrownBy(() -> view.members().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
