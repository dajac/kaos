package io.github.dajac.kaos;

import java.util.List;
import java.util.Set;

public class SimulatorGroupState {
    public int groupEpoch;
    public int targetAssignmentEpoch;
    public String groupState;
    public List<MemberState> members;

    public static class MemberState {
        public int slotIndex;
        public String clientId;
        public String memberId;
        public int memberEpoch;
        public String state; // "Stable", "Reconciling", "Stopped"
        public boolean subscribed;
        public int processingDelayMs;
        public Set<String> assignment;
        public Set<String> targetAssignment;
    }
}
