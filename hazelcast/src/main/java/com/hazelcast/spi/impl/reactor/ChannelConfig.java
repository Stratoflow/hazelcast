package com.hazelcast.spi.impl.reactor;

public class ChannelConfig {

    public int receiveBufferSize = 256 * 1024;
    public int sendBufferSize = 256 * 1024;
    public boolean tcpNoDelay = true;
    public boolean tcpQuickAck = true;

}
