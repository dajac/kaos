package io.github.dajac.kaos.server.dto;

import io.github.dajac.kaos.Protocol;

/**
 * The body of {@code POST /api/v1/simulator/groups/{groupId}/consumers/{id}/actions}.
 *
 * @param type {@code join}, {@code leave} or {@code switch-protocol}
 * @param protocol the protocol to switch to; required by {@code switch-protocol} only
 */
public record ActionRequest(String type, Protocol protocol) {}
