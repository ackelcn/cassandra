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
package org.apache.cassandra.schema;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.db.SystemKeyspace.BootstrapState;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.FailureDetector;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.net.RequestCallback;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.WrappedRunnable;

import static org.apache.cassandra.net.NoPayload.noPayload;
import static org.apache.cassandra.net.Verb.SCHEMA_PULL_REQ;

final class MigrationTask extends WrappedRunnable
{
    private static final Logger logger = LoggerFactory.getLogger(MigrationTask.class);

    private static final ConcurrentLinkedQueue<CountDownLatch> inflightTasks = new ConcurrentLinkedQueue<>();

    private static final Set<BootstrapState> monitoringBootstrapStates = EnumSet.of(BootstrapState.NEEDS_BOOTSTRAP, BootstrapState.IN_PROGRESS);

    private final InetAddressAndPort endpoint;

//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7544
    MigrationTask(InetAddressAndPort endpoint)
    {
        this.endpoint = endpoint;
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13457
        SchemaMigrationDiagnostics.taskCreated(endpoint);
    }

    static ConcurrentLinkedQueue<CountDownLatch> getInflightTasks()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-10731
        return inflightTasks;
    }

    public void runMayThrow() throws Exception
    {
        if (!FailureDetector.instance.isAlive(endpoint))
        {
            logger.warn("Can't send schema pull request: node {} is down.", endpoint);
            SchemaMigrationDiagnostics.taskSendAborted(endpoint);
            return;
        }

        // There is a chance that quite some time could have passed between now and the MM#maybeScheduleSchemaPull(),
        // potentially enough for the endpoint node to restart - which is an issue if it does restart upgraded, with
        // a higher major.
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8462
        if (!MigrationManager.shouldPullSchemaFrom(endpoint))
        {
            logger.info("Skipped sending a migration request: node {} has a higher major version now.", endpoint);
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13457
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13457
            SchemaMigrationDiagnostics.taskSendAborted(endpoint);
            return;
        }

        Message message = Message.out(SCHEMA_PULL_REQ, noPayload);
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15066

        final CountDownLatch completionLatch = new CountDownLatch(1);
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-10731

        RequestCallback<Collection<Mutation>> cb = msg ->
        {
            try
            {
                Schema.instance.mergeAndAnnounceVersion(msg.payload);
            }
            catch (ConfigurationException e)
            {
                logger.error("Configuration exception merging remote schema", e);
            }
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-10731
            finally
            {
                completionLatch.countDown();
            }
        };

        // Only save the latches if we need bootstrap or are bootstrapping
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-10731
        if (monitoringBootstrapStates.contains(SystemKeyspace.getBootstrapState()))
            inflightTasks.offer(completionLatch);

        MessagingService.instance().sendWithCallback(message, endpoint, cb);
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15066

//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13457
        SchemaMigrationDiagnostics.taskRequestSend(endpoint);
    }
}
