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
package io.github.dajac.kaos.server.dto;

import io.github.dajac.kaos.Protocol;

/**
 * The body of {@code POST /api/v1/simulator/groups/{groupId}/consumers/{id}/actions}.
 *
 * @param type {@code join}, {@code leave} or {@code switch-protocol}
 * @param protocol the protocol to switch to; required by {@code switch-protocol} only
 */
public record ActionRequest(String type, Protocol protocol) {}
