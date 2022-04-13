package io.netty.channel.epoll;

import com.hazelcast.spi.impl.reactor.Channel;
import com.hazelcast.spi.impl.reactor.frame.Frame;
import org.jctools.queues.MpmcArrayQueue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicReference;

import static com.hazelcast.internal.nio.IOUtil.closeResource;


// add padding around Nio channel
public class EpollChannel extends Channel {

    // immutable state
    protected LinuxSocket socket;
    public EpollReactor reactor;
    protected boolean writeThrough;

    // ======================================================
    // reading side of the channel.
    // ======================================================
    protected Frame inboundFrame;
    protected ByteBuffer receiveBuffer;

    // ======================================================
    // writing side of the channel.
    // ======================================================
    // private
    public final IOVector ioVector = new IOVector();

    //  concurrent
    public final AtomicReference<Thread> flushThread = new AtomicReference<>();
    public final MpmcArrayQueue<Frame> unflushedFrames = new MpmcArrayQueue<>(4096);
    //public final ConcurrentLinkedQueue<Frame> unflushedFrames = new ConcurrentLinkedQueue<>();

    protected int flags = Native.EPOLLET;

    void setFlag(int flag) throws IOException {
        if (!isFlagSet(flag)) {
            flags |= flag;
            modifyEvents();
        }
    }

    void clearFlag(int flag) throws IOException {
        if (isFlagSet(flag)) {
            flags &= ~flag;
            modifyEvents();
        }
    }

    private void modifyEvents() throws IOException {
//        if (socket.isOpen() && isRegistered()) {
//            ((EpollEventLoop) eventLoop()).modify(this);
//        }

        if(socket.isOpen()) {
            Native.epollCtlMod(reactor.epollFd.intValue(), socket.intValue(), flags);
        }
    }

    boolean isFlagSet(int flag) {
        return (flags & flag) != 0;
    }


    @Override
    public void flush() {
//        if (flushThread.get() != null) {
//            return;
//        }
//
//        if (flushThread.compareAndSet(null, Thread.currentThread())) {
//            reactor.schedule(this);
//        }

        Thread currentThread = Thread.currentThread();
        if (flushThread.compareAndSet(null, currentThread)) {
            if (currentThread == reactor) {
                reactor.dirtyChannels.add(this);
            } else if (writeThrough) {
                reactor.handleWrite(this);
            } else {
                reactor.schedule(this);
            }
        }
    }

    public void resetFlushed() {
        flushThread.set(null);

        if (!unflushedFrames.isEmpty()) {
            if (flushThread.compareAndSet(null, Thread.currentThread())) {
                reactor.schedule(this);
            }
        }
    }

    @Override
    public void write(Frame frame) {
        unflushedFrames.add(frame);
    }

    @Override
    public void writeAndFlush(Frame frame) {
        write(frame);
        flush();
    }

    @Override
    public void unsafeWriteAndFlush(Frame frame) {
        Thread currentFlushThread = flushThread.get();
        Thread currentThread = Thread.currentThread();

        assert currentThread == reactor;

        if (currentFlushThread == null) {
            if (flushThread.compareAndSet(null, currentThread)) {
                reactor.dirtyChannels.add(this);
                if (!ioVector.add(frame)) {
                    unflushedFrames.add(frame);
                }
            } else {
                unflushedFrames.add(frame);
            }
        } else if (currentFlushThread == reactor) {
            if (!ioVector.add(frame)) {
                unflushedFrames.add(frame);
            }
        } else {
            unflushedFrames.add(frame);
            flush();
        }
    }

    @Override
    public void close() {
        if(socket!=null){
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        reactor.removeChannel(this);
    }
}
