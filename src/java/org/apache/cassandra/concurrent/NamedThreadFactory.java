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
package org.apache.cassandra.concurrent;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import org.apache.cassandra.utils.memory.BufferPool;

/**
 * This class is an implementation of the <i>ThreadFactory</i> interface. This
 * is useful to give Java threads meaningful names which is useful when using
 * a tool like JConsole.
 */

public class NamedThreadFactory implements ThreadFactory
{
    private static volatile String globalPrefix;
    public static void setGlobalPrefix(String prefix) { globalPrefix = prefix; }
    public static String globalPrefix() {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15650
        String prefix = globalPrefix;
        return prefix == null ? "" : prefix;
    }

    public final String id;
    private final int priority;
    private final ClassLoader contextClassLoader;
    private final ThreadGroup threadGroup;
    protected final AtomicInteger n = new AtomicInteger(1);

    public NamedThreadFactory(String id)
    {
        this(id, Thread.NORM_PRIORITY);
    }

    public NamedThreadFactory(String id, int priority)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-9402
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-14922
        this(id, priority, null, null);
    }

    public NamedThreadFactory(String id, int priority, ClassLoader contextClassLoader, ThreadGroup threadGroup)
    {
        this.id = id;
        this.priority = priority;
        this.contextClassLoader = contextClassLoader;
        this.threadGroup = threadGroup;
    }

    public Thread newThread(Runnable runnable)
    {
        String name = id + ':' + n.getAndIncrement();
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13034
        Thread thread = createThread(threadGroup, runnable, name, true);
        thread.setPriority(priority);
        if (contextClassLoader != null)
            thread.setContextClassLoader(contextClassLoader);
        return thread;
    }

    private static final AtomicInteger threadCounter = new AtomicInteger();

    @VisibleForTesting
    public static Thread createThread(Runnable runnable)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13034
        return createThread(null, runnable, "anonymous-" + threadCounter.incrementAndGet());
    }

    public static Thread createThread(Runnable runnable, String name)
    {
        return createThread(null, runnable, name);
    }

    public static Thread createThread(Runnable runnable, String name, boolean daemon)
    {
        return createThread(null, runnable, name, daemon);
    }

    public static Thread createThread(ThreadGroup threadGroup, Runnable runnable, String name)
    {
        return createThread(threadGroup, runnable, name, false);
    }

    public static Thread createThread(ThreadGroup threadGroup, Runnable runnable, String name, boolean daemon)
    {
        String prefix = globalPrefix;
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15008
        Thread thread = new FastThreadLocalThread(threadGroup, runnable, prefix != null ? prefix + name : name);
        thread.setDaemon(daemon);
        return thread;
    }
}
