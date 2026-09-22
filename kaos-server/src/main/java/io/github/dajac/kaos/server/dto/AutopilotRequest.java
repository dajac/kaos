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

/**
 * The body of {@code PUT /api/v1/simulator/groups/{groupId}/autopilot}. It has the shape of the
 * {@code autopilot} of a group snapshot.
 *
 * @param enabled whether the autopilot is on; required
 * @param minIntervalMs the shortest interval between two flips, or null to keep the current one
 * @param maxIntervalMs the longest interval between two flips, or null to keep the current one
 */
public record AutopilotRequest(Boolean enabled, Integer minIntervalMs, Integer maxIntervalMs) {}
