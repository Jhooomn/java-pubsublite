/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsublite.beam;

import com.google.cloud.pubsublite.Offset;
import com.google.cloud.pubsublite.internal.CheckedApiException;
import java.util.Optional;
import org.apache.beam.sdk.transforms.DoFn.ProcessContinuation;
import org.joda.time.Duration;

interface SubscriptionPartitionProcessor extends AutoCloseable {
  void start() throws CheckedApiException;

  ProcessContinuation waitForCompletion(Duration duration);

  Optional<Offset> lastClaimed();
}