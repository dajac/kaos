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
package io.github.dajac.kaos;

/**
 * The live view of the autopilot of a group. The autopilot flips the subscription of one random
 * consumer after a random pause between the two intervals. The intervals are the last ones it was
 * given, and are kept while it is off.
 *
 * @param enabled whether the autopilot is on
 * @param minIntervalMs the shortest pause between two flips
 * @param maxIntervalMs the longest pause between two flips
 */
public record AutopilotSnapshot(boolean enabled, int minIntervalMs, int maxIntervalMs) {}
