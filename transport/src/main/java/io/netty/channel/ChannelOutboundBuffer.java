/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Written by Josh Bloch of Google Inc. and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Recycler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 */
public class ChannelOutboundBuffer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final int INITIAL_CAPACITY = 32;

    private static final int threadLocalDirectBufferSize;

    static {
        threadLocalDirectBufferSize = SystemPropertyUtil.getInt("io.netty.threadLocalDirectBufferSize", 64 * 1024);
        logger.debug("-Dio.netty.threadLocalDirectBufferSize: {}", threadLocalDirectBufferSize);
    }

    private final AbstractChannel channel;

    private Entry first;
    private Entry last;
    private int flushed;
    private int messages;

    private ByteBuffer[] nioBuffers;
    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> WRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "writable");

    private volatile int writable = 1;

    public ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
        nioBuffers = new ByteBuffer[INITIAL_CAPACITY];
    }

    void addMessage(Object msg, ChannelPromise promise) {
        int size = channel.estimatorHandle().size(msg);
        if (size < 0) {
            size = 0;
        }

        Entry e = new Entry();
        if (last == null) {
            first = e;
            last = e;
        } else {
            last.next = e;
            e.prev = last;
            last = e;
        }
        e.msg = msg;
        e.pendingSize = size;
        e.promise = promise;
        e.total = total(msg);

        messages++;

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(size);
    }

    void addFlush() {
        flushed = messages;
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(int size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue + size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue + size;
        }

        int highWaterMark = channel.config().getWriteBufferHighWaterMark();

        if (newWriteBufferSize > highWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 1, 0)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(int size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue - size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue - size;
        }

        int lowWaterMark = channel.config().getWriteBufferLowWaterMark();

        if (newWriteBufferSize == 0 || newWriteBufferSize < lowWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 0, 1)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    public Object current() {
        return current(true);
    }

    public Object current(boolean preferDirect) {
        if (isEmpty()) {
            return null;
        } else {
            // TODO: Think of a smart way to handle ByteBufHolder messages
            Object msg = first.msg;
            if (threadLocalDirectBufferSize <= 0 || !preferDirect) {
                return msg;
            }
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                if (buf.isDirect()) {
                    return buf;
                } else {
                    int readableBytes = buf.readableBytes();
                    if (readableBytes == 0) {
                        return buf;
                    }

                    // Non-direct buffers are copied into JDK's own internal direct buffer on every I/O.
                    // We can do a better job by using our pooled allocator. If the current allocator does not
                    // pool a direct buffer, we use a ThreadLocal based pool.
                    ByteBufAllocator alloc = channel.alloc();
                    ByteBuf directBuf;
                    if (alloc.isDirectBufferPooled()) {
                        directBuf = alloc.directBuffer(readableBytes);
                    } else {
                        directBuf = ThreadLocalPooledByteBuf.newInstance();
                    }
                    directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
                    current(directBuf);
                    return directBuf;
                }
            }
            return msg;
        }
    }

    /**
     * Replace the current msg with the given one.
     * The replaced msg will automatically be released
     */
    public void current(Object msg) {
        Entry entry = first;
        safeRelease(entry.msg);
        entry.msg = msg;
    }

    public void progress(long amount) {
        Entry e = first;
        ChannelPromise p = e.promise;
        if (p instanceof ChannelProgressivePromise) {
            long progress = e.progress + amount;
            e.progress = progress;
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    public boolean remove() {
        if (isEmpty()) {
            return false;
        }

        Entry e = first;
        first = e.next;
        if (first == null) {
            last = null;
        }

        Object msg = e.msg;
        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        safeRelease(msg);

        messages--;
        flushed--;

        promise.trySuccess();
        decrementPendingOutboundBytes(size);

        return true;
    }

    public boolean remove(Throwable cause) {
        if (isEmpty()) {
            return false;
        }

        Entry e = first;
        first = e.next;
        if (first == null) {
            last = null;
        }

        Object msg = e.msg;
        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        messages--;
        flushed--;

        safeRelease(msg);

        safeFail(promise, cause);
        decrementPendingOutboundBytes(size);

        return true;
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@code null} is returned otherwise.  If this method returns a non-null array, {@link #nioBufferCount()} and
     * {@link #nioBufferSize()} will return the number of NIO buffers in the returned array and the total number
     * of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        long nioBufferSize = 0;
        int nioBufferCount = 0;

        if (!isEmpty()) {
            final ByteBufAllocator alloc = channel.alloc();
            ByteBuffer[] nioBuffers = this.nioBuffers;
            Entry entry = first;
            int i = flushed;

            for (;;) {
                Object m = entry.msg;
                if (!(m instanceof ByteBuf)) {
                    this.nioBufferCount = 0;
                    this.nioBufferSize = 0;
                    return null;
                }

                ByteBuf buf = (ByteBuf) m;
                final int readerIndex = buf.readerIndex();
                final int readableBytes = buf.writerIndex() - readerIndex;

                if (readableBytes > 0) {
                    nioBufferSize += readableBytes;
                    int count = entry.count;
                    if (count == -1) {
                        entry.count = count = buf.nioBufferCount();
                    }
                    int neededSpace = nioBufferCount + count;
                    if (neededSpace > nioBuffers.length) {
                        this.nioBuffers = nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                    }

                    if (buf.isDirect() || threadLocalDirectBufferSize <= 0) {
                        if (count == 1) {
                            ByteBuffer nioBuf = entry.buf;
                            if (nioBuf == null) {
                                // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                                // derived buffer
                                entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                            }
                            nioBuffers[nioBufferCount ++] = nioBuf;
                        } else {
                            ByteBuffer[] nioBufs = entry.buffers;
                            if (nioBufs == null) {
                                // cached ByteBuffers as they may be expensive to create in terms of Object allocation
                                entry.buffers = nioBufs = buf.nioBuffers();
                            }
                            nioBufferCount = fillBufferArray(nioBufs, nioBuffers, nioBufferCount);
                        }
                    } else {
                        nioBufferCount = fillBufferArrayNonDirect(entry, buf, readerIndex,
                                readableBytes, alloc, nioBuffers, nioBufferCount);
                    }
                }
                if (--i == 0) {
                    break;
                }
                entry = entry.next;
            }
        }

        this.nioBufferCount = nioBufferCount;
        this.nioBufferSize = nioBufferSize;

        return nioBuffers;
    }

    private static int fillBufferArray(ByteBuffer[] nioBufs, ByteBuffer[] nioBuffers, int nioBufferCount) {
        for (ByteBuffer nioBuf: nioBufs) {
            if (nioBuf == null) {
                break;
            }
            nioBuffers[nioBufferCount ++] = nioBuf;
        }
        return nioBufferCount;
    }

    private static int fillBufferArrayNonDirect(Entry entry, ByteBuf buf, int readerIndex, int readableBytes,
                                      ByteBufAllocator alloc, ByteBuffer[] nioBuffers, int nioBufferCount) {
        ByteBuf directBuf;
        if (alloc.isDirectBufferPooled()) {
            directBuf = alloc.directBuffer(readableBytes);
        } else {
            directBuf = ThreadLocalPooledByteBuf.newInstance();
        }
        directBuf.writeBytes(buf, readerIndex, readableBytes);
        buf.release();
        entry.msg = directBuf;
        // cache ByteBuffer
        ByteBuffer nioBuf = entry.buf = directBuf.internalNioBuffer(0, readableBytes);
        entry.count = 1;
        nioBuffers[nioBufferCount ++] = nioBuf;
        return nioBufferCount;
    }

    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    public int nioBufferCount() {
        return nioBufferCount;
    }

    public long nioBufferSize() {
        return nioBufferSize;
    }

    boolean getWritable() {
        return writable != 0;
    }

    public int size() {
        return flushed;
    }

    public boolean isEmpty() {
        return flushed == 0;
    }

    void failFlushed(Throwable cause) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove(cause)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    void close(final ClosedChannelException cause) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause);
                }
            });
            return;
        }

        inFail = true;

        if (channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        final int unflushedCount = messages - flushed;

        try {
            for (int i = 0; i < unflushedCount; i++) {
                Entry e = last;
                safeRelease(e.msg);
                e.msg = null;
                safeFail(e.promise, cause);
                e.promise = null;

                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                long oldValue = totalPendingSize;
                long newWriteBufferSize = oldValue - size;
                while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
                    oldValue = totalPendingSize;
                    newWriteBufferSize = oldValue - size;
                }

                e.pendingSize = 0;
                last = e.prev;
            }
        } finally {
            messages = flushed;
            inFail = false;
        }

        recycle();
    }

    private static void safeRelease(Object message) {
        try {
            ReferenceCountUtil.release(message);
        } catch (Throwable t) {
            logger.warn("Failed to release a message.", t);
        }
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
            logger.warn("Promise done already: {} - new exception is:", promise, cause);
        }
    }

    public void recycle() {

        if (nioBuffers.length > INITIAL_CAPACITY) {
            nioBuffers = new ByteBuffer[INITIAL_CAPACITY];
        } else {
            // null out the nio buffers array so the can be GC'ed
            // https://github.com/netty/netty/issues/1763
            Arrays.fill(nioBuffers, null);
        }

        // reset flushed, unflushed and tail
        // See https://github.com/netty/netty/issues/1772
        flushed = 0;
        messages = 0;
        first = null;
        last = null;
    }

    private static final class Entry {
        Object msg;
        ByteBuffer[] buffers;
        ByteBuffer buf;
        ChannelPromise promise;
        long progress;
        long total;
        int pendingSize;
        int count = -1;
        Entry next;
        Entry prev;
    }

    static final class ThreadLocalPooledByteBuf extends UnpooledDirectByteBuf {
        private final Recycler.Handle handle;

        private static final Recycler<ThreadLocalPooledByteBuf> RECYCLER = new Recycler<ThreadLocalPooledByteBuf>() {
            @Override
            protected ThreadLocalPooledByteBuf newObject(Handle handle) {
                return new ThreadLocalPooledByteBuf(handle);
            }
        };

        private ThreadLocalPooledByteBuf(Recycler.Handle handle) {
            super(UnpooledByteBufAllocator.DEFAULT, 256, Integer.MAX_VALUE);
            this.handle = handle;
        }

        static ThreadLocalPooledByteBuf newInstance() {
            ThreadLocalPooledByteBuf buf = RECYCLER.get();
            buf.setRefCnt(1);
            return buf;
        }

        @Override
        protected void deallocate() {
            if (capacity() > threadLocalDirectBufferSize) {
                super.deallocate();
            } else {
                clear();
                RECYCLER.recycle(this, handle);
            }
        }
    }
}
