/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.logbuffer;

import io.aeron.ReservedValueSupplier;
import org.agrona.BitUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static io.aeron.logbuffer.FrameDescriptor.BEGIN_FRAG_FLAG;
import static io.aeron.logbuffer.FrameDescriptor.END_FRAG_FLAG;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static io.aeron.logbuffer.FrameDescriptor.PADDING_FRAME_TYPE;
import static io.aeron.logbuffer.FrameDescriptor.flagsOffset;
import static io.aeron.logbuffer.FrameDescriptor.typeOffset;
import static io.aeron.logbuffer.LogBufferDescriptor.TERM_TAIL_COUNTERS_OFFSET;
import static io.aeron.logbuffer.LogBufferDescriptor.rawTailVolatile;
import static io.aeron.logbuffer.TermAppender.FAILED;
import static io.aeron.logbuffer.TermAppender.TRIPPED;
import static io.aeron.logbuffer.TermAppender.pack;
import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static io.aeron.protocol.DataHeaderFlyweight.RESERVED_VALUE_OFFSET;
import static io.aeron.protocol.DataHeaderFlyweight.createDefaultHeader;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.BitUtil.align;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

public class TermAppenderTest
{
    private static final int TERM_BUFFER_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int META_DATA_BUFFER_LENGTH = LogBufferDescriptor.LOG_META_DATA_LENGTH;
    private static final int MAX_FRAME_LENGTH = 1024;
    private static final int MAX_PAYLOAD_LENGTH = MAX_FRAME_LENGTH - HEADER_LENGTH;
    private static final int PARTITION_INDEX = 0;
    private static final int TERM_TAIL_COUNTER_OFFSET = TERM_TAIL_COUNTERS_OFFSET + (PARTITION_INDEX * SIZE_OF_LONG);
    private static final int TERM_ID = 7;
    private static final long RV = 7777L;
    private static final ReservedValueSupplier RVS = (termBuffer, termOffset, frameLength) -> RV;
    private static final UnsafeBuffer DEFAULT_HEADER = new UnsafeBuffer(allocateDirect(HEADER_LENGTH));

    private final UnsafeBuffer termBuffer = spy(new UnsafeBuffer(allocateDirect(TERM_BUFFER_LENGTH)));
    private final UnsafeBuffer logMetaDataBuffer = new UnsafeBuffer(allocateDirect(META_DATA_BUFFER_LENGTH));
    private final HeaderWriter headerWriter = spy(new HeaderWriter(createDefaultHeader(0, 0, TERM_ID)));

    private TermAppender termAppender;

    @Before
    public void setUp()
    {
        termAppender = new TermAppender(termBuffer, logMetaDataBuffer, PARTITION_INDEX);
    }

    @Test
    public void shouldPackResult()
    {
        final int termId = 7;
        final int termOffset = -1;

        final long result = pack(termId, termOffset);

        assertThat(TermAppender.termId(result), is(termId));
        assertThat(TermAppender.termOffset(result), is(termOffset));
    }

    @Test
    public void shouldAppendFrameToEmptyLog()
    {
        final int headerLength = DEFAULT_HEADER.capacity();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);
        final int tail = 0;

        logMetaDataBuffer.putLong(TERM_TAIL_COUNTER_OFFSET, pack(TERM_ID, tail));

        assertThat(termAppender.appendUnfragmentedMessage(
            headerWriter, buffer, 0, msgLength, RVS), is((long)alignedFrameLength));

        assertThat(rawTailVolatile(logMetaDataBuffer, PARTITION_INDEX),
            is(pack(TERM_ID, tail + alignedFrameLength)));

        final InOrder inOrder = inOrder(termBuffer, headerWriter);
        inOrder.verify(headerWriter, times(1)).write(termBuffer, tail, frameLength, TERM_ID);
        inOrder.verify(termBuffer, times(1)).putBytes(headerLength, buffer, 0, msgLength);
        inOrder.verify(termBuffer, times(1)).putLong(tail + RESERVED_VALUE_OFFSET, RV, LITTLE_ENDIAN);
        inOrder.verify(termBuffer, times(1)).putIntOrdered(tail, frameLength);
    }

    @Test
    public void shouldAppendFrameTwiceToLog()
    {
        final int headerLength = DEFAULT_HEADER.capacity();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);
        int tail = 0;

        logMetaDataBuffer.putLong(TERM_TAIL_COUNTER_OFFSET, pack(TERM_ID, tail));

        assertThat(termAppender.appendUnfragmentedMessage(
            headerWriter, buffer, 0, msgLength, RVS), is((long)alignedFrameLength));
        assertThat(termAppender.appendUnfragmentedMessage(
            headerWriter, buffer, 0, msgLength, RVS), is((long)alignedFrameLength * 2));

        assertThat(rawTailVolatile(logMetaDataBuffer, PARTITION_INDEX),
            is(pack(TERM_ID, tail + (alignedFrameLength * 2))));

        final InOrder inOrder = inOrder(termBuffer, headerWriter);
        inOrder.verify(headerWriter, times(1)).write(termBuffer, tail, frameLength, TERM_ID);
        inOrder.verify(termBuffer, times(1)).putBytes(headerLength, buffer, 0, msgLength);
        inOrder.verify(termBuffer, times(1)).putLong(tail + RESERVED_VALUE_OFFSET, RV, LITTLE_ENDIAN);
        inOrder.verify(termBuffer, times(1)).putIntOrdered(tail, frameLength);

        tail = alignedFrameLength;
        inOrder.verify(headerWriter, times(1)).write(termBuffer, tail, frameLength, TERM_ID);
        inOrder.verify(termBuffer, times(1)).putBytes(tail + headerLength, buffer, 0, msgLength);
        inOrder.verify(termBuffer, times(1)).putLong(tail + RESERVED_VALUE_OFFSET, RV, LITTLE_ENDIAN);
        inOrder.verify(termBuffer, times(1)).putIntOrdered(tail, frameLength);
    }

    @Test
    public void shouldPadLogAndTripWhenAppendingWithInsufficientRemainingCapacity()
    {
        final int msgLength = 120;
        final int headerLength = DEFAULT_HEADER.capacity();
        final int requiredFrameSize = align(headerLength + msgLength, FRAME_ALIGNMENT);
        final int tailValue = TERM_BUFFER_LENGTH - align(msgLength, FRAME_ALIGNMENT);
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final int frameLength = TERM_BUFFER_LENGTH - tailValue;

        logMetaDataBuffer.putLong(TERM_TAIL_COUNTER_OFFSET, pack(TERM_ID, tailValue));

        final long expectResult = pack(TERM_ID, TRIPPED);
        assertThat(termAppender.appendUnfragmentedMessage(headerWriter, buffer, 0, msgLength, RVS), is(expectResult));

        assertThat(rawTailVolatile(logMetaDataBuffer, PARTITION_INDEX),
            is(pack(TERM_ID, tailValue + requiredFrameSize)));

        final InOrder inOrder = inOrder(termBuffer, headerWriter);
        inOrder.verify(headerWriter, times(1)).write(termBuffer, tailValue, frameLength, TERM_ID);
        inOrder.verify(termBuffer, times(1)).putShort(typeOffset(tailValue), (short)PADDING_FRAME_TYPE, LITTLE_ENDIAN);
        inOrder.verify(termBuffer, times(1)).putIntOrdered(tailValue, frameLength);
    }

    @Test
    public void shouldFailAppendWithInsufficientRemainingCapacity() {
        final int msgLength = BitUtil.align(Integer.MAX_VALUE - HEADER_LENGTH, HEADER_LENGTH) - HEADER_LENGTH;
        final int tailValue = BitUtil.align(msgLength + HEADER_LENGTH, FRAME_ALIGNMENT);
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[1]);
        logMetaDataBuffer.putLong(TERM_TAIL_COUNTER_OFFSET, pack(TERM_ID, tailValue));
        final long expectFailedResult = pack(TERM_ID, FAILED);
        assertThat(termAppender.appendUnfragmentedMessage(headerWriter, buffer, 0, msgLength, RVS), is(expectFailedResult));
        assertThat(termAppender.appendUnfragmentedMessage(headerWriter, buffer, 0, HEADER_LENGTH, RVS), is(expectFailedResult));
        assertThat(termAppender.appendUnfragmentedMessage(headerWriter, buffer, 0, 1, RVS), is(expectFailedResult));
    }

    @Test
    public void shouldFragmentMessageOverTwoFrames()
    {
        final int msgLength = MAX_PAYLOAD_LENGTH + 1;
        final int headerLength = DEFAULT_HEADER.capacity();
        final int frameLength = headerLength + 1;
        final int requiredCapacity = align(headerLength + 1, FRAME_ALIGNMENT) + MAX_FRAME_LENGTH;
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[msgLength]);
        int tail = 0;

        logMetaDataBuffer.putLong(TERM_TAIL_COUNTER_OFFSET, pack(TERM_ID, tail));

        assertThat(termAppender.appendFragmentedMessage(
            headerWriter, buffer, 0, msgLength, MAX_PAYLOAD_LENGTH, RVS), is((long)requiredCapacity));

        assertThat(rawTailVolatile(logMetaDataBuffer, PARTITION_INDEX),
            is(pack(TERM_ID, tail + requiredCapacity)));

        final InOrder inOrder = inOrder(termBuffer, headerWriter);
        inOrder.verify(headerWriter, times(1)).write(termBuffer, tail, MAX_FRAME_LENGTH, TERM_ID);
        inOrder.verify(termBuffer, times(1)).putBytes(tail + headerLength, buffer, 0, MAX_PAYLOAD_LENGTH);
        inOrder.verify(termBuffer, times(1)).putByte(flagsOffset(tail), BEGIN_FRAG_FLAG);
        inOrder.verify(termBuffer, times(1)).putLong(tail + RESERVED_VALUE_OFFSET, RV, LITTLE_ENDIAN);
        inOrder.verify(termBuffer, times(1)).putIntOrdered(tail, MAX_FRAME_LENGTH);

        tail = MAX_FRAME_LENGTH;
        inOrder.verify(headerWriter, times(1)).write(termBuffer, tail, frameLength, TERM_ID);
        inOrder.verify(termBuffer, times(1)).putBytes(tail + headerLength, buffer, MAX_PAYLOAD_LENGTH, 1);
        inOrder.verify(termBuffer, times(1)).putByte(flagsOffset(tail), END_FRAG_FLAG);
        inOrder.verify(termBuffer, times(1)).putLong(tail + RESERVED_VALUE_OFFSET, RV, LITTLE_ENDIAN);
        inOrder.verify(termBuffer, times(1)).putIntOrdered(tail, frameLength);
    }

    @Test
    public void shouldClaimRegionForZeroCopyEncoding()
    {
        final int headerLength = DEFAULT_HEADER.capacity();
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);
        final int tail = 0;
        final BufferClaim bufferClaim = new BufferClaim();

        logMetaDataBuffer.putLong(TERM_TAIL_COUNTER_OFFSET, pack(TERM_ID, tail));

        assertThat(termAppender.claim(headerWriter, msgLength, bufferClaim), is((long)alignedFrameLength));

        assertThat(bufferClaim.offset(), is(tail + headerLength));
        assertThat(bufferClaim.length(), is(msgLength));

        assertThat(rawTailVolatile(logMetaDataBuffer, PARTITION_INDEX),
            is(pack(TERM_ID, tail + alignedFrameLength)));

        // Map flyweight or encode to buffer directly then call commit() when done
        bufferClaim.commit();

        final InOrder inOrder = inOrder(headerWriter);
        inOrder.verify(headerWriter, times(1)).write(termBuffer, tail, frameLength, TERM_ID);
    }
}
