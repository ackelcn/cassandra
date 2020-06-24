/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Throwables;

import io.netty.util.concurrent.FastThreadLocalThread;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.SerializationHelper;
import org.apache.cassandra.db.rows.UnfilteredSerializer;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.utils.JVMStabilityInspector;

/**
 * A SSTable writer that doesn't assume rows are in sorted order.
 * <p>
 * This writer buffers rows in memory and then write them all in sorted order.
 * To avoid loading the entire data set in memory, the amount of rows buffered
 * is configurable. Each time the threshold is met, one SSTable will be
 * created (and the buffer be reseted).
 *
 * @see SSTableSimpleWriter
 */
class SSTableSimpleUnsortedWriter extends AbstractSSTableSimpleWriter
{
    private static final Buffer SENTINEL = new Buffer();

    private Buffer buffer = new Buffer();
    private final long bufferSize;
    private long currentSize;

    // Used to compute the row serialized size
    private final SerializationHeader header;
    private final SerializationHelper helper;

    private final BlockingQueue<Buffer> writeQueue = new SynchronousQueue<Buffer>();
    private final DiskWriter diskWriter = new DiskWriter();

    SSTableSimpleUnsortedWriter(File directory, TableMetadataRef metadata, RegularAndStaticColumns columns, long bufferSizeInMB)
    {
        super(directory, metadata, columns);
        this.bufferSize = bufferSizeInMB * 1024L * 1024L;
        this.header = new SerializationHeader(true, metadata.get(), columns, EncodingStats.NO_STATS);
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15389
        this.helper = new SerializationHelper(this.header);
        diskWriter.start();
    }

//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13867
    PartitionUpdate.Builder getUpdateFor(DecoratedKey key)
    {
        assert key != null;
        PartitionUpdate.Builder previous = buffer.get(key);
        if (previous == null)
        {
            // todo: inefficient - we create and serialize a PU just to get its size, then recreate it
            // todo: either allow PartitionUpdateBuilder to have .build() called several times or pre-calculate the size
            currentSize += PartitionUpdate.serializer.serializedSize(createPartitionUpdateBuilder(key).build(), formatType.info.getLatestVersion().correspondingMessagingVersion());
            previous = createPartitionUpdateBuilder(key);
            buffer.put(key, previous);
        }
        return previous;
    }

    private void countRow(Row row)
    {
        // Note that the accounting of a row is a bit inaccurate (it doesn't take some of the file format optimization into account)
        // and the maintaining of the bufferSize is in general not perfect. This has always been the case for this class but we should
        // improve that. In particular, what we count is closer to the serialized value, but it's debatable that it's the right thing
        // to count since it will take a lot more space in memory and the bufferSize if first and foremost used to avoid OOM when
        // using this writer.
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15389
        currentSize += UnfilteredSerializer.serializer.serializedSize(row, helper, 0, formatType.info.getLatestVersion().correspondingMessagingVersion());
    }

    private void maybeSync() throws SyncException
    {
        try
        {
            if (currentSize > bufferSize)
                sync();
        }
        catch (IOException e)
        {
            // addColumn does not throw IOException but we want to report this to the user,
            // so wrap it in a temporary RuntimeException that we'll catch in rawAddRow above.
            throw new SyncException(e);
        }
    }

    private PartitionUpdate.Builder createPartitionUpdateBuilder(DecoratedKey key)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13867
        return new PartitionUpdate.Builder(metadata.get(), key, columns, 4)
        {
            @Override
            public void add(Row row)
            {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-9705
                super.add(row);
                countRow(row);
                maybeSync();
            }
        };
    }

    @Override
    public void close() throws IOException
    {
        sync();
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8807
        put(SENTINEL);
        try
        {
            diskWriter.join();
            checkForWriterException();
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }

        checkForWriterException();
    }

    protected void sync() throws IOException
    {
        if (buffer.isEmpty())
            return;

//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8807
        put(buffer);
        buffer = new Buffer();
        currentSize = 0;
    }

    private void put(Buffer buffer) throws IOException
    {
        while (true)
        {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8984
            checkForWriterException();
            try
            {
                if (writeQueue.offer(buffer, 1, TimeUnit.SECONDS))
                    break;
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private void checkForWriterException() throws IOException
    {
        // slightly lame way to report exception from the writer, but that should be good enough
        if (diskWriter.exception != null)
        {
            if (diskWriter.exception instanceof IOException)
                throw (IOException) diskWriter.exception;
            else
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-2116
                throw Throwables.propagate(diskWriter.exception);
        }
    }

    static class SyncException extends RuntimeException
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8099
        SyncException(IOException ioe)
        {
            super(ioe);
        }
    }

    //// typedef
    static class Buffer extends TreeMap<DecoratedKey, PartitionUpdate.Builder> {}

    private class DiskWriter extends FastThreadLocalThread
    {
        volatile Throwable exception = null;
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-2116

        public void run()
        {
            while (true)
            {
                try
                {
                    Buffer b = writeQueue.take();
                    if (b == SENTINEL)
                        return;

//IC see: https://issues.apache.org/jira/browse/CASSANDRA-11844
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-11844
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-12551
                        try (SSTableTxnWriter writer = createWriter())
                    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13867
                        for (Map.Entry<DecoratedKey, PartitionUpdate.Builder> entry : b.entrySet())
                            writer.append(entry.getValue().build().unfilteredIterator());
                        writer.finish(false);
                    }
                }
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-2116
                catch (Throwable e)
                {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7507
                    JVMStabilityInspector.inspectThrowable(e);
                    // Keep only the first exception
                    if (exception == null)
                        exception = e;
                }
            }
        }
    }
}
