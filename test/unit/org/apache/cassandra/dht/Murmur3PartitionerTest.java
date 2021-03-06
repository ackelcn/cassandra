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
package org.apache.cassandra.dht;

import org.junit.Test;

public class Murmur3PartitionerTest extends PartitionerTestCase
{
    public void initPartitioner()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8244
        partitioner = Murmur3Partitioner.instance;
    }

    @Override
    protected void midpointMinimumTestCase()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8171
        Token mintoken = partitioner.getMinimumToken();
        assert mintoken.compareTo(partitioner.midpoint(mintoken, mintoken)) != 0;
        assertMidpoint(mintoken, tok("a"), 16);
        assertMidpoint(mintoken, tok("aaa"), 16);
        assertMidpoint(mintoken, mintoken, 62);
        assertMidpoint(tok("a"), mintoken, 16);
    }

    protected boolean shouldStopRecursion(Token left, Token right)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-12858
        return left.size(right) < Math.scalb(1, -48);
    }

    @Test
    public void testSplit()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-12777
        assertSplit(tok("a"), tok("b"), 16);
        assertSplit(tok("a"), tok("bbb"), 16);
    }

    @Test
    public void testSplitWrapping()
    {
        assertSplit(tok("b"), tok("a"), 16);
        assertSplit(tok("bbb"), tok("a"), 16);
    }

    @Test
    public void testSplitExceedMaximumCase()
    {
        Murmur3Partitioner.LongToken left = new Murmur3Partitioner.LongToken(Long.MAX_VALUE - 100);
        assertSplit(left, tok("a"), 16);
    }
}

