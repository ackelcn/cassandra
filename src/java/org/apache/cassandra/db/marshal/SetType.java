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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cassandra.cql3.Json;
import org.apache.cassandra.cql3.Sets;
import org.apache.cassandra.cql3.Term;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.cassandra.serializers.SetSerializer;
import org.apache.cassandra.transport.ProtocolVersion;

public class SetType<T> extends CollectionType<Set<T>>
{
    // interning instances
    private static final ConcurrentHashMap<AbstractType<?>, SetType> instances = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<AbstractType<?>, SetType> frozenInstances = new ConcurrentHashMap<>();

    private final AbstractType<T> elements;
    private final SetSerializer<T> serializer;
    private final boolean isMultiCell;

    public static SetType<?> getInstance(TypeParser parser) throws ConfigurationException, SyntaxException
    {
        List<AbstractType<?>> l = parser.getTypeParameters();
        if (l.size() != 1)
            throw new ConfigurationException("SetType takes exactly 1 type parameter");

        return getInstance(l.get(0), true);
    }

    public static <T> SetType<T> getInstance(AbstractType<T> elements, boolean isMultiCell)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13426
        ConcurrentHashMap<AbstractType<?>, SetType> internMap = isMultiCell ? instances : frozenInstances;
        SetType<T> t = internMap.get(elements);
        return null == t
             ? internMap.computeIfAbsent(elements, k -> new SetType<>(k, isMultiCell))
             : t;
    }

    public SetType(AbstractType<T> elements, boolean isMultiCell)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-9901
        super(ComparisonType.CUSTOM, Kind.SET);
        this.elements = elements;
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-10162
        this.serializer = SetSerializer.getInstance(elements.getSerializer(), elements);
        this.isMultiCell = isMultiCell;
    }

    @Override
    public boolean referencesUserType(ByteBuffer name)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13426
        return elements.referencesUserType(name);
    }

    @Override
    public SetType<?> withUpdatedUserType(UserType udt)
    {
        if (!referencesUserType(udt.name))
            return this;

        (isMultiCell ? instances : frozenInstances).remove(elements);

        return getInstance(elements.withUpdatedUserType(udt), isMultiCell);
    }

    @Override
    public AbstractType<?> expandUserTypes()
    {
        return getInstance(elements.expandUserTypes(), isMultiCell);
    }

    public AbstractType<T> getElementsType()
    {
        return elements;
    }

    public AbstractType<T> nameComparator()
    {
        return elements;
    }

    public AbstractType<?> valueComparator()
    {
        return EmptyType.instance;
    }

    @Override
    public boolean isMultiCell()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7859
        return isMultiCell;
    }

    @Override
    public AbstractType<?> freeze()
    {
        if (isMultiCell)
            return getInstance(this.elements, false);
        else
            return this;
    }

    @Override
    public AbstractType<?> freezeNestedMulticellTypes()
    {
        if (!isMultiCell())
            return this;

        if (elements.isFreezable() && elements.isMultiCell())
            return getInstance(elements.freeze(), isMultiCell);

        return getInstance(elements.freezeNestedMulticellTypes(), isMultiCell);
    }

    @Override
    public boolean isCompatibleWithFrozen(CollectionType<?> previous)
    {
        assert !isMultiCell;
        return this.elements.isCompatibleWith(((SetType) previous).elements);
    }

    @Override
    public boolean isValueCompatibleWithFrozen(CollectionType<?> previous)
    {
        // because sets are ordered, any changes to the type must maintain the ordering
        return isCompatibleWithFrozen(previous);
    }

    @Override
    public int compareCustom(ByteBuffer o1, ByteBuffer o2)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-6783
        return ListType.compareListOrSet(elements, o1, o2);
    }

    public SetSerializer<T> getSerializer()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5744
        return serializer;
    }

    @Override
    public String toString(boolean ignoreFreezing)
    {
        boolean includeFrozenType = !ignoreFreezing && !isMultiCell();

//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7859
        StringBuilder sb = new StringBuilder();
        if (includeFrozenType)
            sb.append(FrozenType.class.getName()).append("(");
        sb.append(getClass().getName());
        sb.append(TypeParser.stringifyTypeParameters(Collections.<AbstractType<?>>singletonList(elements), ignoreFreezing || !isMultiCell));
        if (includeFrozenType)
            sb.append(")");
        return sb.toString();
    }

    public List<ByteBuffer> serializedValues(Iterator<Cell> cells)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8099
        List<ByteBuffer> bbs = new ArrayList<ByteBuffer>();
        while (cells.hasNext())
            bbs.add(cells.next().path().get(0));
        return bbs;
    }

    @Override
    public Term fromJSONObject(Object parsed) throws MarshalException
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-9190
        if (parsed instanceof String)
            parsed = Json.decodeJson((String) parsed);

//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7970
        if (!(parsed instanceof List))
            throw new MarshalException(String.format(
                    "Expected a list (representing a set), but got a %s: %s", parsed.getClass().getSimpleName(), parsed));

        List list = (List) parsed;
        Set<Term> terms = new HashSet<>(list.size());
        for (Object element : list)
        {
            if (element == null)
                throw new MarshalException("Invalid null element in set");
            terms.add(elements.fromJSONObject(element));
        }

        return new Sets.DelayedValue(elements, terms);
    }

    @Override
    public String toJSONString(ByteBuffer buffer, ProtocolVersion protocolVersion)
    {
        return ListType.setOrListToJsonString(buffer, elements, protocolVersion);
    }
}
