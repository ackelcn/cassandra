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
package org.apache.cassandra.db.marshal;

import java.nio.ByteBuffer;

import org.apache.cassandra.cql3.CQL3Type;
import org.apache.cassandra.cql3.Constants;
import org.apache.cassandra.cql3.Term;
import org.apache.cassandra.serializers.TypeSerializer;
import org.apache.cassandra.serializers.LongSerializer;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.cassandra.transport.ProtocolVersion;
import org.apache.cassandra.utils.ByteBufferUtil;

public class LongType extends NumberType<Long>
{
    public static final LongType instance = new LongType();

    LongType() {super(ComparisonType.CUSTOM);} // singleton
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-9901

    public boolean isEmptyValueMeaningless()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-9457
        return true;
    }

    public int compareCustom(ByteBuffer o1, ByteBuffer o2)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5723
        return compareLongs(o1, o2);
    }

    public static int compareLongs(ByteBuffer o1, ByteBuffer o2)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-6934
        if (!o1.hasRemaining() || !o2.hasRemaining())
            return o1.hasRemaining() ? 1 : o2.hasRemaining() ? -1 : 0;

        int diff = o1.get(o1.position()) - o2.get(o2.position());
        if (diff != 0)
            return diff;

        return ByteBufferUtil.compareUnsigned(o1, o2);
    }

    public ByteBuffer fromString(String source) throws MarshalException
    {
        // Return an empty ByteBuffer for an empty string.
        if (source.isEmpty())
            return ByteBufferUtil.EMPTY_BYTE_BUFFER;

        long longType;

        try
        {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-1864
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-0
            longType = Long.parseLong(source);
        }
        catch (Exception e)
        {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7970
            throw new MarshalException(String.format("Unable to make long from '%s'", source), e);
        }

        return decompose(longType);
    }

    @Override
    public Term fromJSONObject(Object parsed) throws MarshalException
    {
        try
        {
            if (parsed instanceof String)
                return new Constants.Value(fromString((String) parsed));

            Number parsedNumber = (Number) parsed;
            if (!(parsedNumber instanceof Integer || parsedNumber instanceof Long))
                throw new MarshalException(String.format("Expected a bigint value, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));

            return new Constants.Value(getSerializer().serialize(parsedNumber.longValue()));
        }
        catch (ClassCastException exc)
        {
            throw new MarshalException(String.format(
                    "Expected a bigint value, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));
        }
    }

    @Override
    public String toJSONString(ByteBuffer buffer, ProtocolVersion protocolVersion)
    {
        return getSerializer().deserialize(buffer).toString();
    }

    @Override
    public boolean isValueCompatibleWithInternal(AbstractType<?> otherType)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-6766
        return this == otherType || otherType == DateType.instance || otherType == TimestampType.instance;
    }

    public CQL3Type asCQL3Type()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5198
        return CQL3Type.Native.BIGINT;
    }

    public TypeSerializer<Long> getSerializer()
    {
        return LongSerializer.instance;
    }

    @Override
    public int valueLengthIfFixed()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8099
        return 8;
    }

    @Override
    protected int toInt(ByteBuffer value)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-11935
        throw new UnsupportedOperationException();
    }

    @Override
    protected float toFloat(ByteBuffer value)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    protected long toLong(ByteBuffer value)
    {
        return ByteBufferUtil.toLong(value);
    }

    public ByteBuffer add(NumberType<?> leftType, ByteBuffer left, NumberType<?> rightType, ByteBuffer right)
    {
        return ByteBufferUtil.bytes(leftType.toLong(left) + rightType.toLong(right));
    }

    public ByteBuffer substract(NumberType<?> leftType, ByteBuffer left, NumberType<?> rightType, ByteBuffer right)
    {
        return ByteBufferUtil.bytes(leftType.toLong(left) - rightType.toLong(right));
    }

    public ByteBuffer multiply(NumberType<?> leftType, ByteBuffer left, NumberType<?> rightType, ByteBuffer right)
    {
        return ByteBufferUtil.bytes(leftType.toLong(left) * rightType.toLong(right));
    }

    public ByteBuffer divide(NumberType<?> leftType, ByteBuffer left, NumberType<?> rightType, ByteBuffer right)
    {
        return ByteBufferUtil.bytes(leftType.toLong(left) / rightType.toLong(right));
    }

    public ByteBuffer mod(NumberType<?> leftType, ByteBuffer left, NumberType<?> rightType, ByteBuffer right)
    {
        return ByteBufferUtil.bytes(leftType.toLong(left) % rightType.toLong(right));
    }

    public ByteBuffer negate(ByteBuffer input)
    {
        return ByteBufferUtil.bytes(-toLong(input));
    }
}
