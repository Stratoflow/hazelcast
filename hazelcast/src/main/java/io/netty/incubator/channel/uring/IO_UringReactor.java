package io.netty.incubator.channel.uring;

import com.hazelcast.cluster.Address;
import com.hazelcast.internal.nio.Connection;
import com.hazelcast.spi.impl.reactor.Channel;
import com.hazelcast.spi.impl.reactor.ChannelConfig;
import com.hazelcast.spi.impl.reactor.Frame;
import com.hazelcast.spi.impl.reactor.Reactor;
import com.hazelcast.spi.impl.reactor.ReactorFrontEnd;
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
import static com.hazelcast.internal.nio.IOUtil.compactOrClear;
import static com.hazelcast.internal.nio.Packet.FLAG_OP_RESPONSE;
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
    private LinuxSocket serverSocket;
    private final IOUringSubmissionQueue sq;
    private final IOUringCompletionQueue cq;
    public final AtomicBoolean wakeupNeeded = new AtomicBoolean(true);
    private ByteBuffer acceptedAddressMemory;
    private long acceptedAddressMemoryAddress;
    private ByteBuffer acceptedAddressLengthMemory;
    private long acceptedAddressLengthMemoryAddress;
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
        this.acceptedAddressMemory = Buffer.allocateDirectWithNativeOrder(Native.SIZEOF_SOCKADDR_STORAGE);
        this.acceptedAddressMemoryAddress = Buffer.memoryAddress(acceptedAddressMemory);
        this.acceptedAddressLengthMemory = Buffer.allocateDirectWithNativeOrder(Long.BYTES);
        // Needs to be initialized to the size of acceptedAddressMemory.
        // See https://man7.org/linux/man-pages/man2/accept.2.html
        this.acceptedAddressLengthMemory.putLong(0, Native.SIZEOF_SOCKADDR_STORAGE);
        this.acceptedAddressLengthMemoryAddress = Buffer.memoryAddress(acceptedAddressLengthMemory);
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

    @Override
    public void setupServerSocket() throws Exception {
        this.serverSocket = LinuxSocket.newSocketStream(false);
        this.serverSocket.setBlocking();
        this.serverSocket.setReuseAddress(true);
        System.out.println(getName() + " serverSocket.fd:" + serverSocket.intValue());

        InetSocketAddress serverAddress = new InetSocketAddress(thisAddress.getInetAddress(), port);
        serverSocket.bind(serverAddress);
        System.out.println(getName() + " Bind success " + serverAddress);
        serverSocket.listen(10);
        System.out.println(getName() + " Listening on " + serverAddress);
    }

    private void configure(LinuxSocket socket) throws IOException {
        socket.setTcpNoDelay(channelConfig.tcpNoDelay);
        socket.setSendBufferSize(channelConfig.sendBufferSize);
        socket.setReceiveBufferSize(channelConfig.receiveBufferSize);
        socket.setTcpQuickAck(channelConfig.tcpQuickAck);

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
        channel.receiveBuff = allocator.directBuffer(channelConfig.receiveBufferSize);
//        channel.writeBufs = new ByteBuf[1024];
//        channel.writeBufsInUse = new boolean[channel.writeBufs.length];
//        for (int k = 0; k < channel.writeBufs.length; k++) {
//            channel.writeBufs[k] = allocator.directBuffer(8192);
//        }
        channel.readBuffer = ByteBuffer.allocate(channelConfig.sendBufferSize);
        channel.connection = connection;
        channels.add(channel);
        return channel;
    }

    @Override
    protected void eventLoop() {
        sq_addEventRead();
        sq_addAccept();

        while (!frontend.shuttingdown) {
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

    private void sq_addAccept() {
        sq.addAccept(serverSocket.intValue(), acceptedAddressMemoryAddress, acceptedAddressLengthMemoryAddress, (short) 0);
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
        sq_addAccept();

        System.out.println(getName() + " handle IORING_OP_ACCEPT fd:" + fd + " serverFd:" + serverSocket.intValue() + "res:" + res);

        if (res < 0) {
            return;
        }

        SocketAddress address = SockaddrIn.readIPv4(acceptedAddressMemoryAddress, inet4AddressArray);
        System.out.println(this + " new connected accepted: " + address);
        LinuxSocket socket = new LinuxSocket(res);
        try {
            configure(socket);
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
        // we need to update the writerIndex; not done automatically.
        int oldLimit = channel.readBuffer.limit();
        channel.readEvents.inc();
        channel.bytesRead.inc(res);
        channel.readBuffer.limit(res);
        channel.receiveBuff.writerIndex(channel.receiveBuff.writerIndex() + res);
        channel.receiveBuff.readBytes(channel.readBuffer);
        channel.receiveBuff.clear();
        channel.readBuffer.limit(oldLimit);
        sq_addRead(channel);
        ByteBuffer readBuf = channel.readBuffer;
        channel.readBuffer.flip();

        Frame responseChain = null;
        for (; ; ) {
            if (channel.inboundFrame == null) {
                if (readBuf.remaining() < INT_SIZE_IN_BYTES) {
                    break;
                }

                int size = readBuf.getInt();
                //todo: we don't know if we have request or response.
                channel.inboundFrame = requestFrameAllocator.allocate(size);
                channel.inboundFrame.writeInt(size);
                channel.inboundFrame.connection = channel.connection;
                channel.inboundFrame.channel = channel;
            }

            // todo: we need to copy.

            int size = channel.inboundFrame.size();
            int remaining = size - channel.inboundFrame.position();
            channel.inboundFrame.write(readBuf, remaining);

            if (!channel.inboundFrame.isComplete()) {
                break;
            }

            Frame frame = channel.inboundFrame;
            channel.inboundFrame.complete();
            channel.inboundFrame = null;
            channel.framesRead.inc();

            if (frame.isFlagRaised(FLAG_OP_RESPONSE)) {
                frame.next = responseChain;
                responseChain = frame;
            } else {
                handleRequest(frame);
            }
        }

        compactOrClear(channel.readBuffer);

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
        if(!channel.flushed.get()){
            throw new RuntimeException("Channel should be in flushed state");
        }

        //todo: we only neee

        //todo: currently there is no limit on what we are writing.
        int localPendingSize = channel.flushedFrames.size();
        int globalPendingSize = channel.unflushedFrames.size();
        int pendingSize = localPendingSize + globalPendingSize;
        ByteBuf iovArrayBuffer = iovArrayBufferAllocator.directBuffer(pendingSize * IovArray.IOV_SIZE);
        IovArray iovArray = new IovArray(iovArrayBuffer);
        channel.iovArray = iovArray;
        int offset = iovArray.count();

        for (int k = 0; k < localPendingSize; k++) {
            Frame frame = channel.flushedFrames.poll();
            ByteBuffer byteBuffer = frame.byteBuffer();
            ByteBuf buf = unpooledByteBufAllocator.directBuffer(byteBuffer.limit());
            buf.writeBytes(byteBuffer);
            iovArray.add(buf, 0, buf.readableBytes());
        }

        for (int k = 0; k < globalPendingSize; k++) {
            Frame frame = channel.unflushedFrames.poll();
            ByteBuffer byteBuffer = frame.byteBuffer();
            ByteBuf buf = unpooledByteBufAllocator.directBuffer(byteBuffer.limit());
            buf.writeBytes(byteBuffer);
            iovArray.add(buf, 0, buf.readableBytes());
        }

        sq.addWritev(channel.socket.intValue(), iovArray.memoryAddress(offset), iovArray.count() - offset, (short) 0);

        if(channel.resetFlushed()){

        }else{

        }
    }

    private void handle_IORING_OP_WRITEV(int fd, int res, int flags, short data) {
        //todo: deal with negative res.

        // we need to release the iovArray

        System.out.println("handle_IORING_OP_WRITEV fd:"+fd +" bytes written: "+res);
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
    protected void handleConnectRequest(ConnectRequest request) {
        try {
            SocketAddress address = request.address;
            System.out.println(getName() + " connectRequest to address:" + address);

            LinuxSocket socket = LinuxSocket.newSocketStream(false);
            socket.setBlocking();
            configure(socket);

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
