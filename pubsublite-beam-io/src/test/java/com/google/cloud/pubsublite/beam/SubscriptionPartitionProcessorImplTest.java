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

import static com.google.cloud.pubsublite.beam.SubscriberOptions.DEFAULT_FLOW_CONTROL;
import static com.google.cloud.pubsublite.internal.testing.UnitTestExamples.example;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode.Code;
import com.google.cloud.pubsublite.Offset;
import com.google.cloud.pubsublite.internal.CheckedApiException;
import com.google.cloud.pubsublite.internal.testing.FakeApiService;
import com.google.cloud.pubsublite.internal.wire.Subscriber;
import com.google.cloud.pubsublite.proto.Cursor;
import com.google.cloud.pubsublite.proto.FlowControlRequest;
import com.google.cloud.pubsublite.proto.SequencedMessage;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.util.Timestamps;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.beam.sdk.io.range.OffsetRange;
import org.apache.beam.sdk.transforms.DoFn.OutputReceiver;
import org.apache.beam.sdk.transforms.DoFn.ProcessContinuation;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;

@RunWith(JUnit4.class)
@SuppressWarnings("initialization.fields.uninitialized")
public class SubscriptionPartitionProcessorImplTest {
  @Spy RestrictionTracker<OffsetByteRange, OffsetByteProgress> tracker;
  @Mock OutputReceiver<SequencedMessage> receiver;
  @Mock Function<Consumer<List<SequencedMessage>>, Subscriber> subscriberFactory;

  abstract static class FakeSubscriber extends FakeApiService implements Subscriber {}

  @Spy FakeSubscriber subscriber;

  Consumer<List<SequencedMessage>> leakedConsumer;
  SubscriptionPartitionProcessor processor;

  private static SequencedMessage messageWithOffset(long offset) {
    return SequencedMessage.newBuilder()
        .setCursor(Cursor.newBuilder().setOffset(offset))
        .setPublishTime(Timestamps.fromMillis(10000 + offset))
        .setSizeBytes(1024)
        .build();
  }

  private OffsetByteRange initialRange() {
    return OffsetByteRange.of(new OffsetRange(example(Offset.class).value(), Long.MAX_VALUE));
  }

  @Before
  public void setUp() {
    initMocks(this);
    when(subscriberFactory.apply(any()))
        .then(
            args -> {
              leakedConsumer = args.getArgument(0);
              return subscriber;
            });
    processor =
        new SubscriptionPartitionProcessorImpl(
            tracker, receiver, subscriberFactory, DEFAULT_FLOW_CONTROL);
    assertNotNull(leakedConsumer);
  }

  @Test
  public void lifecycle() throws Exception {
    when(tracker.currentRestriction()).thenReturn(initialRange());
    processor.start();
    verify(subscriber).startAsync();
    verify(subscriber).awaitRunning();
    verify(subscriber)
        .allowFlow(
            FlowControlRequest.newBuilder()
                .setAllowedBytes(DEFAULT_FLOW_CONTROL.bytesOutstanding())
                .setAllowedMessages(DEFAULT_FLOW_CONTROL.messagesOutstanding())
                .build());
    processor.close();
    verify(subscriber).stopAsync();
    verify(subscriber).awaitTerminated();
  }

  @Test
  public void lifecycleFlowControlThrows() throws Exception {
    when(tracker.currentRestriction()).thenReturn(initialRange());
    doThrow(new CheckedApiException(Code.OUT_OF_RANGE)).when(subscriber).allowFlow(any());
    assertThrows(CheckedApiException.class, () -> processor.start());
  }

  @Test
  public void lifecycleSubscriberAwaitThrows() throws Exception {
    when(tracker.currentRestriction()).thenReturn(initialRange());
    processor.start();
    doThrow(new CheckedApiException(Code.INTERNAL).underlying).when(subscriber).awaitTerminated();
    assertThrows(ApiException.class, () -> processor.close());
    verify(subscriber).stopAsync();
    verify(subscriber).awaitTerminated();
  }

  @Test
  public void subscriberFailureFails() throws Exception {
    when(tracker.currentRestriction()).thenReturn(initialRange());
    processor.start();
    subscriber.fail(new CheckedApiException(Code.OUT_OF_RANGE));
    ApiException e =
        assertThrows(ApiException.class, () -> processor.waitForCompletion(Duration.millis(10)));
    assertEquals(Code.OUT_OF_RANGE, e.getStatusCode().getCode());
  }

  @Test
  public void allowFlowFailureFails() throws Exception {
    when(tracker.currentRestriction()).thenReturn(initialRange());
    processor.start();
    when(tracker.tryClaim(any())).thenReturn(true);
    doThrow(new CheckedApiException(Code.OUT_OF_RANGE)).when(subscriber).allowFlow(any());
    leakedConsumer.accept(ImmutableList.of(messageWithOffset(1)));
    ApiException e =
        assertThrows(ApiException.class, () -> processor.waitForCompletion(Duration.ZERO));
    assertEquals(Code.OUT_OF_RANGE, e.getStatusCode().getCode());
  }

  @Test
  public void timeoutReturnsResume() {
    assertEquals(ProcessContinuation.resume(), processor.waitForCompletion(Duration.millis(10)));
    assertFalse(processor.lastClaimed().isPresent());
  }

  @Test
  public void failedClaimCausesStop() {
    when(tracker.tryClaim(any())).thenReturn(false);
    leakedConsumer.accept(ImmutableList.of(messageWithOffset(1)));
    verify(tracker, times(1)).tryClaim(any());
    assertEquals(ProcessContinuation.stop(), processor.waitForCompletion(Duration.millis(10)));
    assertFalse(processor.lastClaimed().isPresent());
    // Future calls to process don't try to claim.
    leakedConsumer.accept(ImmutableList.of(messageWithOffset(2)));
    verify(tracker, times(1)).tryClaim(any());
  }

  @Test
  public void successfulClaimThenTimeout() throws Exception {
    when(tracker.tryClaim(any())).thenReturn(true);
    SequencedMessage message1 = messageWithOffset(1);
    SequencedMessage message3 = messageWithOffset(3);
    leakedConsumer.accept(ImmutableList.of(message1, message3));
    InOrder order = inOrder(tracker, receiver, subscriber);
    order
        .verify(tracker)
        .tryClaim(
            OffsetByteProgress.of(Offset.of(3), message1.getSizeBytes() + message3.getSizeBytes()));
    order
        .verify(receiver)
        .outputWithTimestamp(message1, new Instant(Timestamps.toMillis(message1.getPublishTime())));
    order
        .verify(receiver)
        .outputWithTimestamp(message3, new Instant(Timestamps.toMillis(message3.getPublishTime())));
    order
        .verify(subscriber)
        .allowFlow(
            FlowControlRequest.newBuilder()
                .setAllowedMessages(2)
                .setAllowedBytes(message1.getSizeBytes() + message3.getSizeBytes())
                .build());
    assertEquals(ProcessContinuation.resume(), processor.waitForCompletion(Duration.millis(10)));
    assertEquals(processor.lastClaimed().get(), Offset.of(3));
  }
}
