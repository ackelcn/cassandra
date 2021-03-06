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
import org.apache.cassandra.cql3.Lists;
import org.apache.cassandra.cql3.Term;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.serializers.CollectionSerializer;
import org.apache.cassandra.serializers.ListSerializer;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.cassandra.transport.ProtocolVersion;

public class ListType<T> extends CollectionType<List<T>>
{
    // interning instances
    private static final ConcurrentHashMap<AbstractType<?>, ListType> instances = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<AbstractType<?>, ListType> frozenInstances = new ConcurrentHashMap<>();

    private final AbstractType<T> elements;
    public final ListSerializer<T> serializer;
    private final boolean isMultiCell;

    public static ListType<?> getInstance(TypeParser parser) throws ConfigurationException, SyntaxException
    {
        List<AbstractType<?>> l = parser.getTypeParameters();
        if (l.size() != 1)
            throw new ConfigurationException("ListType takes exactly 1 type parameter");

        return getInstance(l.get(0), true);
    }

    public static <T> ListType<T> getInstance(AbstractType<T> elements, boolean isMultiCell)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13426
        ConcurrentHashMap<AbstractType<?>, ListType> internMap = isMultiCell ? instances : frozenInstances;
        ListType<T> t = internMap.get(elements);
        return null == t
             ? internMap.computeIfAbsent(elements, k -> new ListType<>(k, isMultiCell))
             : t;
    }

    private ListType(AbstractType<T> elements, boolean isMultiCell)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-9901
        super(ComparisonType.CUSTOM, Kind.LIST);
        this.elements = elements;
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5744
        this.serializer = ListSerializer.getInstance(elements.getSerializer());
        this.isMultiCell = isMultiCell;
    }

    @Override
    public boolean referencesUserType(ByteBuffer name)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13426
        return elements.referencesUserType(name);
    }

    @Override
    public ListType<?> withUpdatedUserType(UserType udt)
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

    @Override
    public boolean referencesDuration()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-11873
        return getElementsType().referencesDuration();
    }

    public AbstractType<T> getElementsType()
    {
        return elements;
    }

    public AbstractType<UUID> nameComparator()
    {
        return TimeUUIDType.instance;
    }

    public AbstractType<T> valueComparator()
    {
        return elements;
    }

    public ListSerializer<T> getSerializer()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-5744
        return serializer;
    }

    @Override
    public AbstractType<?> freeze()
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7859
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
    public boolean isMultiCell()
    {
        return isMultiCell;
    }

    @Override
    public boolean isCompatibleWithFrozen(CollectionType<?> previous)
    {
        assert !isMultiCell;
        return this.elements.isCompatibleWith(((ListType) previous).elements);
    }

    @Override
    public boolean isValueCompatibleWithFrozen(CollectionType<?> previous)
    {
        assert !isMultiCell;
        return this.elements.isValueCompatibleWithInternal(((ListType) previous).elements);
    }

    @Override
    public int compareCustom(ByteBuffer o1, ByteBuffer o2)
    {
        return compareListOrSet(elements, o1, o2);
    }

    static int compareListOrSet(AbstractType<?> elementsComparator, ByteBuffer o1, ByteBuffer o2)
    {
        // Note that this is only used if the collection is frozen
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-6934
        if (!o1.hasRemaining() || !o2.hasRemaining())
            return o1.hasRemaining() ? 1 : o2.hasRemaining() ? -1 : 0;

        ByteBuffer bb1 = o1.duplicate();
        ByteBuffer bb2 = o2.duplicate();

        int size1 = CollectionSerializer.readCollectionSize(bb1, ProtocolVersion.V3);
        int size2 = CollectionSerializer.readCollectionSize(bb2, ProtocolVersion.V3);

        for (int i = 0; i < Math.min(size1, size2); i++)
        {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-12838
            ByteBuffer v1 = CollectionSerializer.readValue(bb1, ProtocolVersion.V3);
            ByteBuffer v2 = CollectionSerializer.readValue(bb2, ProtocolVersion.V3);
            int cmp = elementsComparator.compare(v1, v2);
            if (cmp != 0)
                return cmp;
        }

        return size1 == size2 ? 0 : (size1 < size2 ? -1 : 1);
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
        assert isMultiCell;
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-8099
        List<ByteBuffer> bbs = new ArrayList<ByteBuffer>();
        while (cells.hasNext())
            bbs.add(cells.next().value());
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
                    "Expected a list, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));

        List list = (List) parsed;
        List<Term> terms = new ArrayList<>(list.size());
        for (Object element : list)
        {
            if (element == null)
                throw new MarshalException("Invalid null element in list");
            terms.add(elements.fromJSONObject(element));
        }

        return new Lists.DelayedValue(terms);
    }

    public static String setOrListToJsonString(ByteBuffer buffer, AbstractType elementsType, ProtocolVersion protocolVersion)
    {
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-13592
        ByteBuffer value = buffer.duplicate();
        StringBuilder sb = new StringBuilder("[");
        int size = CollectionSerializer.readCollectionSize(value, protocolVersion);
        for (int i = 0; i < size; i++)
        {
            if (i > 0)
                sb.append(", ");
            sb.append(elementsType.toJSONString(CollectionSerializer.readValue(value, protocolVersion), protocolVersion));
        }
        return sb.append("]").toString();
    }

    public ByteBuffer getSliceFromSerialized(ByteBuffer collection, ByteBuffer from, ByteBuffer to)
    {
        // We don't support slicing on lists so we don't need that function
//IC see: https://issues.apache.org/jira/browse/CASSANDRA-7396
        throw new UnsupportedOperationException();
    }

    @Override
    public String toJSONString(ByteBuffer buffer, ProtocolVersion protocolVersion)
    {
        return setOrListToJsonString(buffer, elements, protocolVersion);
    }
}
