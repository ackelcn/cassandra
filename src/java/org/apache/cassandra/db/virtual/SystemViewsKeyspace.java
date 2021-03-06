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
package org.apache.cassandra.db.virtual;

import com.google.common.collect.ImmutableList;

public final class SystemViewsKeyspace extends VirtualKeyspace
{
    private static final String NAME = "system_views";

    public static SystemViewsKeyspace instance = new SystemViewsKeyspace();

    private SystemViewsKeyspace()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-14670
        super(NAME, new ImmutableList.Builder<VirtualTable>()
                    .add(new CachesTable(NAME))
                    .add(new ClientsTable(NAME))
                    .add(new SettingsTable(NAME))
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-15616
                    .add(new SystemPropertiesTable(NAME))
                    .add(new SSTableTasksTable(NAME))
                    .add(new ThreadPoolsTable(NAME))
                    .add(new InternodeOutboundTable(NAME))
                    .add(new InternodeInboundTable(NAME))
                    .addAll(TableMetricTables.getAll(NAME))
                    .build());
    }
}
