package io.netty.incubator.channel.uring;

import com.hazelcast.internal.nio.Connection;
import com.hazelcast.spi.impl.reactor.Channel;
import com.hazelcast.spi.impl.reactor.SocketConfig;
import com.hazelcast.spi.impl.reactor.CircularQueue;
import com.hazelcast.spi.impl.reactor.ConnectRequest;
import com.hazelcast.spi.impl.reactor.Frame;
import com.hazelcast.spi.impl.reactor.Reactor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.unix.Buffer;
import io.netty.channel.unix.FileDescriptor;
import io.netty.channel.unix.IovArray;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.internal.PlatformDependent;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hazelcast.internal.nio.Bits.INT_SIZE_IN_BYTES;
import static com.hazelcast.spi.impl.reactor.Frame.FLAG_OP_RESPONSE;
import static io.netty.incubator.channel.uring.Native.DEFAULT_IOSEQ_ASYNC_THRESHOLD;
import static io.netty.incubator.channel.uring.Native.DEFAULT_RING_SIZE;
import static io.netty.incubator.channel.uring.Native.IORING_OP_ACCEPT;
import static io.netty.incubator.channel.uring.Native.IORING_OP_READ;
import static io.netty.incubator.channel.uring.Native.IORING_OP_WRITEV;


/**
 * To build io uring:
 * <p>
 * sudo yum install autoconf
 * sudo yum install automake
 * sudo yum install libtool
 * <p>
 * Good read:
 * https://unixism.net/2020/04/io-uring-by-example-part-3-a-web-server-with-io-uring/
 * <p>
 * Another example (blocking socket)
 * https://github.com/ddeka0/AsyncIO/blob/master/src/asyncServer.cpp
 * <p>
 * no syscalls:
 * https://wjwh.eu/posts/2021-10-01-no-syscall-server-iouring.html
 * <p>
 * Error codes:
 * https://www.thegeekstuff.com/2010/10/linux-error-codes/
 * <p>
 * <p>
 * https://github.com/torvalds/linux/blob/master/include/uapi/linux/io_uring.h
 * IORING_OP_NOP               0
 * IORING_OP_READV             1
 * IORING_OP_WRITEV            2
 * IORING_OP_FSYNC             3
 * IORING_OP_READ_FIXED        4
 * IORING_OP_WRITE_FIXED       5
 * IORING_OP_POLL_ADD          6
 * IORING_OP_POLL_REMOVE       7
 * IORING_OP_SYNC_FILE_RANGE   8
 * IORING_OP_SENDMSG           9
 * IORING_OP_RECVMSG           10
 * IORING_OP_TIMEOUT,          11
 * IORING_OP_TIMEOUT_REMOVE,   12
 * IORING_OP_ACCEPT,           13
 * IORING_OP_ASYNC_CANCEL,     14
 * IORING_OP_LINK_TIMEOUT,     15
 * IORING_OP_CONNECT,          16
 * IORING_OP_FALLOCATE,        17
 * IORING_OP_OPENAT,
 * IORING_OP_CLOSE,
 * IORING_OP_FILES_UPDATE,
 * IORING_OP_STATX,
 * IORING_OP_READ,
 * IORING_OP_WRITE,
 * IORING_OP_FADVISE,
 * IORING_OP_MADVISE,
 * IORING_OP_SEND,
 * IORING_OP_RECV,
 * IORING_OP_OPENAT2,
 * IORING_OP_EPOLL_CTL,
 * IORING_OP_SPLICE,
 * IORING_OP_PROVIDE_BUFFERS,
 * IORING_OP_REMOVE_BUFFERS,
 * IORING_OP_TEE,
 * IORING_OP_SHUTDOWN,
 * IORING_OP_RENAMEAT,
 * IORING_OP_UNLINKAT,
 * IORING_OP_MKDIRAT,
 * IORING_OP_SYMLINKAT,
 * IORING_OP_LINKAT,
 * IORING_OP_MSG_RING,
 */
public class IO_UringReactor extends Reactor implements IOUringCompletionQueueCallback {

    private final boolean spin;
    private final RingBuffer ringBuffer;
    private final FileDescriptor eventfd;
    private final AcceptMemory acceptMemory = new AcceptMemory();
    private final IOUringSubmissionQueue sq;
    private final IOUringCompletionQueue cq;
    public final AtomicBoolean wakeupNeeded = new AtomicBoolean(true);
    private final long eventfdReadBuf = PlatformDependent.allocateMemory(8);

    // we could use an array.
    private final IntObjectMap<IO_UringChannel> channelMap = new IntObjectHashMap<>(4096);
    private final byte[] inet4AddressArray = new byte[SockaddrIn.IPV4_ADDRESS_LENGTH];
    private final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(true);

    public IO_UringReactor(IO_UringReactorConfig config) {
        super(config);
        this.spin = config.spin;
        this.ringBuffer = Native.createRingBuffer(DEFAULT_RING_SIZE, DEFAULT_IOSEQ_ASYNC_THRESHOLD);
        this.sq = ringBuffer.ioUringSubmissionQueue();
        this.cq = ringBuffer.ioUringCompletionQueue();
        this.eventfd = Native.newBlockingEventFd();
    }

    @Override
    public void wakeup() {
        if (spin || Thread.currentThread() == this) {
            return;
        }

        if (wakeupNeeded.get() && wakeupNeeded.compareAndSet(true, false)) {
            // write to the evfd which will then wake-up epoll_wait(...)
            Native.eventFdWrite(eventfd.intValue(), 1L);
        }
    }

    public void registerAccept(InetSocketAddress serverAddress, SocketConfig socketConfig) throws IOException {
        LinuxSocket serverSocket = LinuxSocket.newSocketStream(false);
        serverSocket.setBlocking();
        serverSocket.setReuseAddress(true);
        System.out.println(getName() + " serverSocket.fd:" + serverSocket.intValue());

        serverSocket.bind(serverAddress);
        System.out.println(getName() + " Bind success " + serverAddress);
        serverSocket.listen(10);
        System.out.println(getName() + " Listening on " + serverAddress);
    }

    private void configure(LinuxSocket socket, SocketConfig socketConfig) throws IOException {
        socket.setTcpNoDelay(socketConfig.tcpNoDelay);
        socket.setSendBufferSize(socketConfig.sendBufferSize);
        socket.setReceiveBufferSize(socketConfig.receiveBufferSize);
        socket.setTcpQuickAck(socketConfig.tcpQuickAck);

        String id = socket.localAddress() + "->" + socket.remoteAddress();
        System.out.println(getName() + " " + id + " tcpNoDelay: " + socket.isTcpNoDelay());
        System.out.println(getName() + " " + id + " tcpQuickAck: " + socket.isTcpQuickAck());
        System.out.println(getName() + " " + id + " receiveBufferSize: " + socket.getReceiveBufferSize());
        System.out.println(getName() + " " + id + " sendBufferSize: " + socket.getSendBufferSize());
    }

    @NotNull
    private IO_UringChannel newChannel(LinuxSocket socket, Connection connection) {
        IO_UringChannel channel = new IO_UringChannel();
        channel.socket = socket;
        channel.localAddress = socket.localAddress();
        channel.remoteAddress = socket.remoteAddress();
        channel.reactor = this;
        //channel.receiveBuff = allocator.directBuffer(socketConfig.receiveBufferSize);
        channel.connection = connection;
        channels.add(channel);
        return channel;
    }

    @Override
    protected void eventLoop() {
        sq_addEventRead();

        //todo
       // sq_addAccept();

        while (running) {
            runTasks();

            boolean moreWork = scheduler.tick();

            flushDirtyChannels();

            if (!cq.hasCompletions()) {
                if (spin || moreWork) {
                    sq.submit();
                } else {
                    wakeupNeeded.set(true);
                    if (publicRunQueue.isEmpty()) {
                        sq.submitAndWait();
                    } else {
                        sq.submit();
                    }
                    wakeupNeeded.set(false);
                }
            } else {
                int processed = cq.process(this);
                if (processed > 0) {
                    //     System.out.println(getName() + " processed " + processed);
                }
            }
        }
    }

    private void sq_addEventRead() {
        sq.addEventFdRead(eventfd.intValue(), eventfdReadBuf, 0, 8, (short) 0);
    }

    private void sq_addAccept(int serverSocketFd) {
        sq.addAccept(serverSocketFd,
                acceptMemory.acceptedAddressMemoryAddress,
                acceptMemory.acceptedAddressLengthMemoryAddress, (short) 0);
    }

    private void sq_addRead(IO_UringChannel channel) {
        ByteBuf b = channel.receiveBuff;
        //System.out.println("sq_addRead writerIndex:" + b.writerIndex() + " capacity:" + b.capacity());
        sq.addRead(channel.socket.intValue(), b.memoryAddress(), b.writerIndex(), b.capacity(), (short) 0);
    }

    private void sq_addWrite(IO_UringChannel channel, ByteBuf buf, short index) {
        sq.addWrite(channel.socket.intValue(), buf.memoryAddress(), buf.readerIndex(), buf.writerIndex(), index);
    }

    @Override
    public void handle(int fd, int res, int flags, byte op, short data) {
        //System.out.println(getName() + " handle called: opcode:" + op);

        if (op == IORING_OP_READ) {
            handle_IORING_OP_READ(fd, res, flags, data);
//        } else if (op == IORING_OP_WRITE) {
//            handle_IORING_OP_WRITE(fd, res, flags, data);
        } else if (op == IORING_OP_WRITEV) {
            handle_IORING_OP_WRITEV(fd, res, flags, data);
        } else if (op == IORING_OP_ACCEPT) {
            handle_IORING_OP_ACCEPT(fd, res, flags, data);
        } else {
            System.out.println(this + " handle Unknown opcode:" + op);
        }
    }

    private void handle_IORING_OP_ACCEPT(int fd, int res, int flags, short data) {
        //todo: we need to look at fd because we don't know which server socket did it.
        sq_addAccept(-1);

//        System.out.println(getName() + " handle IORING_OP_ACCEPT fd:" + fd + " serverFd:" + serverSocket.intValue() + "res:" + res);

        if (res < 0) {
            return;
        }

        SocketAddress address = SockaddrIn.readIPv4(acceptMemory.acceptedAddressMemoryAddress, inet4AddressArray);
        System.out.println(this + " new connected accepted: " + address);
        LinuxSocket socket = new LinuxSocket(res);
        try {
            configure(socket, null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        IO_UringChannel channel = newChannel(socket, null);
        channel.remoteAddress = address;
        channelMap.put(res, channel);
        sq_addRead(channel);
    }

    private long handle_IORING_OP_WRITE = 0;

    private void handle_IORING_OP_READ(int fd, int res, int flags, short data) {
        // res is the number of bytes read
        //todo: we need to deal with res=0 and res<0

        if (fd == eventfd.intValue()) {
            //System.out.println(getName() + " handle IORING_OP_READ from eventFd res:" + res);
            sq_addEventRead();
            return;
        }

        System.out.println(getName() + " handle IORING_OP_READ from fd:" + fd + " res:" + res + " flags:" + flags);

        IO_UringChannel channel = channelMap.get(fd);
        channel.readEvents.inc();
        channel.bytesRead.inc(res);

        ByteBuf receiveBuff = channel.receiveBuff;
        // we need to update the writerIndex; not done automatically.
        //int oldLimit = channel.readBuffer.limit();

        //channel.readBuffer.limit(res);
        receiveBuff.writerIndex(receiveBuff.writerIndex() + res);
        //channel.receiveBuff.readBytes(channel.readBuffer);
        //channel.receiveBuff.clear();

        //channel.readBuffer.limit(oldLimit);
        sq_addRead(channel);

        Frame responseChain = null;
        for (; ; ) {
            Frame frame = channel.inboundFrame;
            if (frame == null) {
                if (receiveBuff.readableBytes() < INT_SIZE_IN_BYTES + INT_SIZE_IN_BYTES) {
                    break;
                }

                int frameSize = receiveBuff.readInt();
                int frameFlags = receiveBuff.readInt();
                //todo: we don't know if we have request or response.
                frame = requestFrameAllocator.allocate(frameSize);
                channel.inboundFrame = frame;
                frame.writeInt(frameSize);
                frame.writeInt(frameFlags);
                frame.connection = channel.connection;
                frame.channel = channel;
            }

            int size = frame.size();
            int remaining = size - frame.position();

            // todo: we need to cap the
            receiveBuff.readBytes(frame.byteBuffer());
            //channel.inboundFrame.write(readBuf, remaining);

            if (!frame.isComplete()) {
                break;
            }

            frame.complete();
            channel.inboundFrame = null;
            channel.framesRead.inc();

            if (frame.isFlagRaised(FLAG_OP_RESPONSE)) {
                frame.next = responseChain;
                responseChain = frame;
            } else {
                handleRequest(frame);
            }
        }

        receiveBuff.discardReadBytes();

        if (responseChain != null) {
            System.out.println("frontend.handleRespons");
            frontend.handleResponse(responseChain);
        }
    }

    private final PooledByteBufAllocator iovArrayBufferAllocator = new PooledByteBufAllocator();
    private final UnpooledByteBufAllocator unpooledByteBufAllocator = new UnpooledByteBufAllocator(true);

    @Override
    protected void handleWrite(Channel c) {
        IO_UringChannel channel = (IO_UringChannel) c;
        if (!channel.flushed.get()) {
            throw new RuntimeException("Channel should be in flushed state");
        }

        CircularQueue<Frame> flushedFrames = channel.flushedFrames;
        flushedFrames.fill(channel.unflushedFrames);

        int flushedFrameSize = flushedFrames.size();

        ByteBuf iovArrayBuffer = iovArrayBufferAllocator.directBuffer(flushedFrameSize * IovArray.IOV_SIZE);
        IovArray iovArray = new IovArray(iovArrayBuffer);
        channel.iovArray = iovArray;
        int offset = iovArray.count();

        for (int k = 0; k < flushedFrameSize; k++) {
            Frame frame = flushedFrames.poll();
            ByteBuffer byteBuffer = frame.byteBuffer();
            ByteBuf buf = unpooledByteBufAllocator.directBuffer(byteBuffer.limit());
            buf.writeBytes(byteBuffer);
            iovArray.add(buf, 0, buf.readableBytes());
        }

        // todo: optimize for single write.
        sq.addWritev(channel.socket.intValue(), iovArray.memoryAddress(offset), iovArray.count() - offset, (short) 0);

        channel.resetFlushed();
    }

    private void handle_IORING_OP_WRITEV(int fd, int res, int flags, short data) {
        //todo: deal with negative res.

        // we need to release the iovArray

        System.out.println("handle_IORING_OP_WRITEV fd:" + fd + " bytes written: " + res);
        IO_UringChannel channel = channelMap.get(fd);
        channel.iovArray.release();


//
//        ByteBuf buf = channel.writeBufs[data];
//        if (buf.readableBytes() != res) {
//            throw new RuntimeException("Readable bytes doesn't match res. Buffer:" + buf + " res:" + res);
//        }
//        channel.writeBufsInUse[data] = false;
    }

//    @Override
//    protected void handleOutbound(Channel c) {
//        IO_UringChannel channel = (IO_UringChannel) c;
//
//        channel.handleOutboundCalls.inc();
//        //System.out.println(getName() + " process channel " + channel.remoteAddress);
//
//        short bufIndex = -1;
//        for (short k = 0; k < channel.writeBufsInUse.length; k++) {
//            System.out.println(k);
//            if (!channel.writeBufsInUse[k]) {
//                bufIndex = k;
//                break;
//            }
//        }
//
//        channel.writeBufsInUse[bufIndex] = true;
//        ByteBuf buf = channel.writeBufs[bufIndex];
//        buf.clear();
//
//        int bytesWritten = 0;
//
//        for (; ; ) {
//            ByteBuffer buffer = channel.localPending.peek();
//            if (buffer == null) {
//                for (; ; ) {
//                    if (channel.localPending.isFull()) {
//                        break;
//                    }
//
//                    Frame frame = channel.publicPending.poll();
//                    if (frame == null) {
//                        break;
//                    }
//                    channel.localPending.offer(frame.getByteBuffer());
//                }
//                buffer = channel.localPending.peek();
//                if (buffer == null) {
//                    break;
//                }
//            }
//
//            channel.packetsWritten.inc();
//            //todo: not correct when not all bytes are written.
//            channel.bytesWritten.inc(buffer.remaining());
//            bytesWritten += buffer.remaining();
//            buf.writeBytes(buffer);
//
//            if (!buffer.hasRemaining()) {
//                channel.localPending.poll();
//            }
//        }
//
//        if (buf.readableBytes() != bytesWritten) {
//            throw new RuntimeException("Data lost: " + buf + " bytes written:" + bytesWritten);
//        }
//
//        sq_addWrite(channel, buf, bufIndex);
//
//        channel.unschedule();
//    }

    @Override
    protected void handleConnect(ConnectRequest request) {
        try {
            SocketAddress address = request.address;
            System.out.println(getName() + " connectRequest to address:" + address);

            LinuxSocket socket = LinuxSocket.newSocketStream(false);
            socket.setBlocking();
            configure(socket, request.socketConfig);

            if (!socket.connect(request.address)) {
                request.future.completeExceptionally(new IOException("Could not connect to " + request.address));
                return;
            }
            logger.info(getName() + "Socket connected to " + address);
            IO_UringChannel channel = newChannel(socket, request.connection);
            channel.remoteAddress = request.address;
            channelMap.put(socket.intValue(), channel);
            request.future.complete(channel);
            sq_addRead(channel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class AcceptMemory {
    final ByteBuffer acceptedAddressMemory;
    final long acceptedAddressMemoryAddress;
    final ByteBuffer acceptedAddressLengthMemory;
    final long acceptedAddressLengthMemoryAddress;

    AcceptMemory() {
        this.acceptedAddressMemory = Buffer.allocateDirectWithNativeOrder(Native.SIZEOF_SOCKADDR_STORAGE);
        this.acceptedAddressMemoryAddress = Buffer.memoryAddress(acceptedAddressMemory);
        this.acceptedAddressLengthMemory = Buffer.allocateDirectWithNativeOrder(Long.BYTES);
        // Needs to be initialized to the size of acceptedAddressMemory.
        // See https://man7.org/linux/man-pages/man2/accept.2.html
        this.acceptedAddressLengthMemory.putLong(0, Native.SIZEOF_SOCKADDR_STORAGE);
        this.acceptedAddressLengthMemoryAddress = Buffer.memoryAddress(acceptedAddressLengthMemory);
    }
}
