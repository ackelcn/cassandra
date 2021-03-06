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

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.metrics.ThreadPoolMetrics;
import org.apache.cassandra.utils.MBeanWrapper;

/**
 * This is a wrapper class for the <i>ScheduledThreadPoolExecutor</i>. It provides an implementation
 * for the <i>afterExecute()</i> found in the <i>ThreadPoolExecutor</i> class to log any unexpected
 * Runtime Exceptions.
 */

public class JMXEnabledThreadPoolExecutor extends DebuggableThreadPoolExecutor implements JMXEnabledThreadPoolExecutorMBean
{
    private final String mbeanName;
    public final ThreadPoolMetrics metrics;

    public JMXEnabledThreadPoolExecutor(String threadPoolName)
    {
        this(1, Integer.MAX_VALUE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new NamedThreadFactory(threadPoolName), "internal");
    }

    public JMXEnabledThreadPoolExecutor(String threadPoolName, String jmxPath)
    {
        this(1, Integer.MAX_VALUE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new NamedThreadFactory(threadPoolName), jmxPath);
    }

    public JMXEnabledThreadPoolExecutor(String threadPoolName, int priority)
    {
        this(1, Integer.MAX_VALUE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new NamedThreadFactory(threadPoolName, priority), "internal");
    }

    public JMXEnabledThreadPoolExecutor(NamedThreadFactory threadFactory, String jmxPath)
    {
        this(1, Integer.MAX_VALUE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), threadFactory, jmxPath);
    }

    public JMXEnabledThreadPoolExecutor(int corePoolSize,
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-1405
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            NamedThreadFactory threadFactory,
            String jmxPath)
    {
        this(corePoolSize, corePoolSize, keepAliveTime, unit, workQueue, threadFactory, jmxPath);
    }

    public JMXEnabledThreadPoolExecutor(int corePoolSize,
                                        int maxPoolSize,
                                        long keepAliveTime,
                                        TimeUnit unit,
                                        BlockingQueue<Runnable> workQueue,
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-1217
                                        NamedThreadFactory threadFactory,
                                        String jmxPath)
    {
        super(corePoolSize, maxPoolSize, keepAliveTime, unit, workQueue, threadFactory);
        super.prestartAllCoreThreads();
        metrics = new ThreadPoolMetrics(this, jmxPath, threadFactory.id).register();
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-14523

        mbeanName = "org.apache.cassandra." + jmxPath + ":type=" + threadFactory.id;
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-14821
        MBeanWrapper.instance.registerMBean(this, mbeanName);
    }

    public JMXEnabledThreadPoolExecutor(int corePoolSize,
                                        int maxPoolSize,
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13594
                                        long keepAliveTime,
                                        TimeUnit unit,
                                        BlockingQueue<Runnable> workQueue,
                                        NamedThreadFactory threadFactory,
                                        String jmxPath,
                                        RejectedExecutionHandler rejectedExecutionHandler)
    {
        this(corePoolSize, maxPoolSize, keepAliveTime, unit, workQueue, threadFactory, jmxPath);
        setRejectedExecutionHandler(rejectedExecutionHandler);
    }

    private void unregisterMBean()
    {
        MBeanWrapper.instance.unregisterMBean(mbeanName);
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-14821

        // release metrics
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-4009
        metrics.release();
    }

    @Override
    public synchronized void shutdown()
    {
        // synchronized, because there is no way to access super.mainLock, which would be
        // the preferred way to make this threadsafe
        if (!isShutdown())
        {
            unregisterMBean();
        }
        super.shutdown();
    }

    @Override
    public synchronized List<Runnable> shutdownNow()
    {
        // synchronized, because there is no way to access super.mainLock, which would be
        // the preferred way to make this threadsafe
        if (!isShutdown())
        {
            unregisterMBean();
        }
        return super.shutdownNow();
    }

    public int getTotalBlockedTasks()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5838
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5657
        return (int) metrics.totalBlocked.getCount();
    }

    public int getCurrentlyBlockedTasks()
    {
        return (int) metrics.currentBlocked.getCount();
    }

    @Deprecated
    public int getCoreThreads()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5044
        return getCorePoolSize();
    }

    @Deprecated
    public void setCoreThreads(int number)
    {
        setCorePoolSize(number);
    }

    @Deprecated
    public int getMaximumThreads()
    {
        return getMaximumPoolSize();
    }

    @Deprecated
    public void setMaximumThreads(int number)
    {
        setMaximumPoolSize(number);
    }

    @Override
    public void setMaximumPoolSize(int newMaximumPoolSize)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5044
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15277
        if (newMaximumPoolSize < getCorePoolSize())
            throw new IllegalArgumentException("maximum pool size cannot be less than core pool size");
        super.setMaximumPoolSize(newMaximumPoolSize);
    }

    @Override
    protected void onInitialRejection(Runnable task)
    {
        metrics.totalBlocked.inc();
        metrics.currentBlocked.inc();
    }

    @Override
    protected void onFinalAccept(Runnable task)
    {
        metrics.currentBlocked.dec();
    }

    @Override
    protected void onFinalRejection(Runnable task)
    {
        metrics.currentBlocked.dec();
    }
}
