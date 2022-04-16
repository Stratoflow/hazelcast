package com.hazelcast.spi.impl.engine.nio;

import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.engine.SocketConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.function.Supplier;

import static java.net.StandardSocketOptions.SO_RCVBUF;
import static java.nio.channels.SelectionKey.OP_ACCEPT;

public final class NioServerChannel implements NioSelectedKeyListener {

    public SocketConfig socketConfig;
    public ServerSocketChannel serverSocketChannel;
    public Supplier<NioChannel> channelSupplier;
    public InetSocketAddress address;

    private Selector selector;
    private ILogger logger;
    private NioReactor reactor;

    public void configure(NioReactor reactor) throws IOException {
        this.reactor = reactor;
        this.selector = reactor.selector;
        this.logger = reactor.logger;
        this.serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.setOption(SO_RCVBUF, socketConfig.receiveBufferSize);
        System.out.println(reactor.getName() + " Binding to " + address);
        serverSocketChannel.bind(address);
        serverSocketChannel.configureBlocking(false);
    }

    public void accept() throws Exception {
        serverSocketChannel.register(selector, OP_ACCEPT, this);
        System.out.println(reactor.getName() + " ServerSocket listening at " + serverSocketChannel.getLocalAddress());
    }

    @Override
    public void handle(SelectionKey key) {
        try {
            SocketChannel socketChannel = serverSocketChannel.accept();
            socketChannel.configureBlocking(false);

            NioChannel channel = channelSupplier.get();
            channel.configure(reactor, socketChannel, socketConfig);
            channel.onConnectionEstablished();
            reactor.registeredChannels.add(channel);
            logger.info("Connection Accepted: " + socketChannel.getLocalAddress());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
