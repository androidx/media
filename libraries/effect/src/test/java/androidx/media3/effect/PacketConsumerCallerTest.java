/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static org.junit.Assert.assertThrows;

import androidx.media3.effect.PacketConsumer.Packet;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlinx.coroutines.channels.ClosedSendChannelException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit test for {@link PacketConsumerCaller}.
 *
 * <p>This test is implemented in Java because {@link PacketConsumerCaller} is designed to allow
 * components written in Java to interact seamlessly with {@link PacketConsumer} implementations in
 * kotlin.
 */
@RunWith(AndroidJUnit4.class)
public class PacketConsumerCallerTest {
  private static final long TEST_TIMEOUT_MS = 1000L;

  private FakePacketConsumer<String> fakePacketConsumer;
  private PacketConsumerCaller<String> packetConsumerCaller;

  @Before
  public void setUp() {
    fakePacketConsumer = new FakePacketConsumer<>();
    packetConsumerCaller =
        PacketConsumerCaller.create(
            fakePacketConsumer,
            newDirectExecutorService(),
            /* errorConsumer= */ e -> {
              throw new IllegalStateException(e);
            });
  }

  @After
  public void tearDown() {
    if (packetConsumerCaller != null) {
      packetConsumerCaller.release();
    }
  }

  @Test
  public void tryQueuePacket_whenChannelHasCapacity_succeedsAndSendsPacket() throws Exception {
    packetConsumerCaller.run();
    Packet<String> packet1 = Packet.of("packet1");

    assertThat(packetConsumerCaller.tryQueuePacket(packet1)).isTrue();

    Packet<String> receivedPacket =
        fakePacketConsumer.packet1Received.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(receivedPacket).isSameInstanceAs(packet1);
  }

  @Test
  public void tryQueuePacket_returnsFalseWhenChannelIsAtCapacity() throws Exception {
    packetConsumerCaller.run();
    Packet<String> packet1 = Packet.of("packet1");
    Packet<String> packet2 = Packet.of("packet2");

    // The Channel has size zero, as long as packet1 is accepted by the PacketConsumer, packet2
    // cannot be queued into the channel.
    assertThat(packetConsumerCaller.tryQueuePacket(packet1)).isTrue();
    assertThat(packetConsumerCaller.tryQueuePacket(packet2)).isFalse();

    assertThat(fakePacketConsumer.packet1Received.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .isSameInstanceAs(packet1);
    // Unblock the consumer to allow it to process packet2.
    fakePacketConsumer.continueProcessing();
    assertThat(packetConsumerCaller.tryQueuePacket(packet2)).isTrue();
    assertThat(fakePacketConsumer.packet2Received.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .isSameInstanceAs(packet2);
  }

  @Test
  public void tryQueuePacket_afterTheCallerIsReleased_throws() {
    packetConsumerCaller.release();

    Packet<String> packet1 = Packet.of("packet1");
    assertThrows(
        ClosedSendChannelException.class, () -> packetConsumerCaller.tryQueuePacket(packet1));
  }

  @Test
  public void tryQueuePacket_throwsException_exceptionPropagated() {
    ThrowingPacketConsumer<String> throwingPacketConsumer = new ThrowingPacketConsumer<>();
    AtomicReference<Exception> thrownException = new AtomicReference<>();
    packetConsumerCaller =
        PacketConsumerCaller.create(
            throwingPacketConsumer,
            newDirectExecutorService(),
            /* errorConsumer= */ thrownException::set);
    packetConsumerCaller.run();
    Packet<String> packet1 = Packet.of("packet1");

    assertThat(packetConsumerCaller.tryQueuePacket(packet1)).isTrue();

    assertThat(thrownException.get()).isNotNull();
  }
}
