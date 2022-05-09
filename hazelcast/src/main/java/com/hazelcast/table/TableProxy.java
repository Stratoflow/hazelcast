package com.hazelcast.table;

import com.hazelcast.bulktransport.BulkTransport;
import com.hazelcast.cluster.Address;
import com.hazelcast.spi.impl.AbstractDistributedObject;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.tpc.engine.frame.ParallelFrameAllocator;
import com.hazelcast.tpc.engine.frame.Frame;
import com.hazelcast.tpc.engine.frame.FrameAllocator;
import com.hazelcast.tpc.requestservice.OpCodes;
import com.hazelcast.tpc.requestservice.RequestService;
import com.hazelcast.bulktransport.impl.BulkTransportImpl;
import com.hazelcast.table.impl.PipelineImpl;
import com.hazelcast.table.impl.TableService;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static com.hazelcast.internal.util.HashUtil.hashToIndex;
import static com.hazelcast.internal.util.Preconditions.checkPositive;
import static com.hazelcast.tpc.engine.frame.Frame.OFFSET_RES_PAYLOAD;
import static com.hazelcast.tpc.requestservice.OpCodes.GET;
import static com.hazelcast.tpc.requestservice.OpCodes.NOOP;
import static com.hazelcast.tpc.requestservice.OpCodes.QUERY;
import static com.hazelcast.tpc.requestservice.OpCodes.SET;
import static com.hazelcast.tpc.requestservice.OpCodes.TABLE_UPSERT;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TableProxy<K, V> extends AbstractDistributedObject implements Table<K, V> {

    private final RequestService requestService;
    private final String name;
    private final int partitionCount;
    private final FrameAllocator frameAllocator;
    private final int requestTimeoutMs;

    public TableProxy(NodeEngineImpl nodeEngine, TableService tableService, String name) {
        super(nodeEngine, tableService);
        this.requestService = nodeEngine.getRequestService();
        this.name = name;
        this.partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        this.frameAllocator = new ParallelFrameAllocator(128, true);
        this.requestTimeoutMs = requestService.getRequestTimeoutMs();
    }

    @Override
    public Pipeline newPipeline() {
        return new PipelineImpl(requestService, frameAllocator);
    }

    @Override
    public void upsert(V v) {
        CompletableFuture<Frame> f = asyncUpsert(v);
        try {
            Frame frame = f.get(23, SECONDS);
            frame.release();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void randomLoad(byte[] key) {
        int partitionId = hashToIndex(Arrays.hashCode(key), partitionCount);

        Frame request = frameAllocator.allocate(60)
                .newFuture()
                .writeRequestHeader(partitionId, OpCodes.RANDOM_LOAD)
                .writeSizedBytes(key)
                .writeComplete();
        CompletableFuture<Frame> f = requestService.invokeOnPartition(request, partitionId);
        try {
            Frame frame = f.get(requestTimeoutMs, MILLISECONDS);
            frame.release();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void concurrentRandomLoad(byte[] key, int concurrency) {
        int partitionId = hashToIndex(Arrays.hashCode(key), partitionCount);

        CompletableFuture[] futures = new CompletableFuture[concurrency];

        for (int k = 0; k < concurrency; k++) {
            Frame request = frameAllocator.allocate(60)
                    .newFuture()
                    .writeRequestHeader(partitionId, OpCodes.RANDOM_LOAD)
                    .writeSizedBytes(key)
                    .writeComplete();
            futures[k] = requestService.invokeOnPartition(request, partitionId);
        }

        for (CompletableFuture<Frame> f : futures) {
            try {
                Frame frame = f.get(requestTimeoutMs, MILLISECONDS);
                frame.release();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }


    private CompletableFuture asyncUpsert(V v) {
        Item item = (Item) v;

        int partitionId = hashToIndex(Long.hashCode(item.key), partitionCount);
        Frame request = frameAllocator.allocate(60)
                .newFuture()
                .writeRequestHeader(partitionId, TABLE_UPSERT)
                .writeString(name)
                .writeLong(item.key)
                .writeInt(item.a)
                .writeInt(item.b)
                .writeComplete();
        return requestService.invokeOnPartition(request, partitionId);
    }

    @Override
    public void concurrentNoop(int concurrency) {
        checkPositive("concurrency", concurrency);

        if (concurrency == 1) {
            try {
                Frame frame = asyncNoop().get(23, SECONDS);
                frame.release();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            CompletableFuture[] futures = new CompletableFuture[concurrency];
            for (int k = 0; k < futures.length; k++) {
                futures[k] = asyncNoop();
            }

            for (CompletableFuture<Frame> f : futures) {
                try {
                    Frame frame = f.get(requestTimeoutMs, MILLISECONDS);
                    frame.release();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public void noop() {
        CompletableFuture<Frame> f = asyncNoop();
        try {
            Frame frame = f.get(requestTimeoutMs, MILLISECONDS);
            frame.release();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CompletableFuture<Frame> asyncNoop() {
        int partitionId = ThreadLocalRandom.current().nextInt(271);
        Frame request = frameAllocator.allocate(32)
                .newFuture()
                .writeRequestHeader(partitionId, NOOP)
                .writeComplete();
        return requestService.invokeOnPartition(request, partitionId);
    }

    // better pipelining support
    @Override
    public void upsertAll(V[] values) {
        CompletableFuture[] futures = new CompletableFuture[values.length];
        for (int k = 0; k < futures.length; k++) {
            futures[k] = asyncUpsert(values[k]);
        }

        for (CompletableFuture<Frame> f : futures) {
            try {
                Frame frame = f.get(requestTimeoutMs, MILLISECONDS);
                frame.release();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public BulkTransport newBulkTransport(Address address, int parallelism) {
        return new BulkTransportImpl(requestService, address, parallelism);
    }

    @Override
    public void set(byte[] key, byte[] value) {
        int partitionId = hashToIndex(Arrays.hashCode(key), partitionCount);

        Frame request = frameAllocator.allocate(60)
                .newFuture()
                .writeRequestHeader(partitionId, SET)
                .writeSizedBytes(key)
                .writeSizedBytes(value)
                .writeComplete();
        CompletableFuture<Frame> f = requestService.invokeOnPartition(request, partitionId);
        try {
            Frame frame = f.get(requestTimeoutMs, MILLISECONDS);
            frame.release();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void bogusQuery() {
        CompletableFuture[] futures = new CompletableFuture[partitionCount];
        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            Frame request = frameAllocator.allocate(60)
                    .newFuture()
                    .writeRequestHeader(partitionId, QUERY)
                    .writeComplete();
            futures[partitionId] = requestService.invokeOnPartition(request, partitionId);
        }

        for (CompletableFuture future : futures) {
            try {
                future.get(requestTimeoutMs, MILLISECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public byte[] get(byte[] key) {
        int partitionId = hashToIndex(Arrays.hashCode(key), partitionCount);

        Frame request = frameAllocator.allocate(60)
                .newFuture()
                .writeRequestHeader(partitionId, GET)
                .writeSizedBytes(key)
                .writeComplete();
        CompletableFuture<Frame> f = requestService.invokeOnPartition(request, partitionId);
        Frame frame = null;
        try {
            frame = f.get(requestTimeoutMs, MILLISECONDS);
            frame.position(OFFSET_RES_PAYLOAD);
            int length = frame.readInt();

            if (length == -1) {
                return null;
            } else {
                byte[] bytes = new byte[length];
                frame.readBytes(bytes, length);
                return bytes;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (frame != null) {
                frame.release();
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getServiceName() {
        return TableService.SERVICE_NAME;
    }
}
