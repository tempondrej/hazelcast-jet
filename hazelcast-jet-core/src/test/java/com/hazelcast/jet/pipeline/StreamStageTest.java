/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.pipeline;

import com.hazelcast.core.IMap;
import com.hazelcast.core.ReplicatedMap;
import com.hazelcast.jet.Traversers;
import com.hazelcast.jet.Util;
import com.hazelcast.jet.accumulator.LongAccumulator;
import com.hazelcast.jet.aggregate.AggregateOperation;
import com.hazelcast.jet.aggregate.AggregateOperation1;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.processor.Processors;
import com.hazelcast.jet.datamodel.ItemsByTag;
import com.hazelcast.jet.datamodel.Tag;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.datamodel.Tuple3;
import com.hazelcast.jet.function.BiFunctionEx;
import com.hazelcast.jet.function.FunctionEx;
import com.hazelcast.jet.function.PredicateEx;
import com.hazelcast.jet.function.TriFunction;
import com.hazelcast.jet.impl.JetEvent;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.hazelcast.jet.Traversers.traverseStream;
import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.aggregate.AggregateOperations.counting;
import static com.hazelcast.jet.datamodel.Tuple2.tuple2;
import static com.hazelcast.jet.datamodel.Tuple3.tuple3;
import static com.hazelcast.jet.function.Functions.wholeItem;
import static com.hazelcast.jet.impl.JetEvent.jetEvent;
import static com.hazelcast.jet.impl.pipeline.AbstractStage.transformOf;
import static com.hazelcast.jet.pipeline.JoinClause.joinMapEntries;
import static com.hazelcast.jet.pipeline.WindowDefinition.tumbling;
import static java.util.Collections.emptyList;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertEquals;

public class StreamStageTest extends PipelineStreamTestSupport {

    private static BiFunction<String, Integer, String> ENRICHING_FORMAT_FN =
            (prefix, i) -> String.format("%s-%04d", prefix, i);

    @Test
    public void setName() {
        // Given
        String stageName = randomName();

        // When
        StreamStage<Integer> stage = streamStageFromList(emptyList());
        stage.setName(stageName);

        // Then
        assertEquals(stageName, stage.name());
    }

    @Test
    public void setLocalParallelism() {
        // Given
        int localParallelism = 10;

        // When
        StreamStage<Integer> stage = streamStageFromList(emptyList());
        stage.setLocalParallelism(localParallelism);

        // Then
        assertEquals(localParallelism, transformOf(stage).localParallelism());
    }

    @Test
    public void map() {
        // Given
        List<Integer> input = sequence(itemCount);
        FunctionEx<Integer, String> mapFn = item -> String.format("%04d-x", item);

        // When
        StreamStage<String> mapped = streamStageFromList(input).map(mapFn);

        // Then
        mapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().map(mapFn), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void filter() {
        // Given
        List<Integer> input = sequence(itemCount);
        PredicateEx<Integer> filterFn = i -> i % 2 == 1;
        Function<Integer, String> formatFn = i -> String.format("%04d", i);

        // When
        StreamStage<Integer> filtered = streamStageFromList(input).filter(filterFn);

        // Then
        filtered.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().filter(filterFn), formatFn),
                streamToString(sinkStreamOf(Integer.class), formatFn));
    }

    @Test
    public void flatMap() {
        // Given
        List<Integer> input = sequence(itemCount);
        FunctionEx<Integer, Stream<String>> flatMapFn =
                i -> Stream.of("A", "B").map(s -> String.format("%04d-%s", i, s));

        // When
        StreamStage<String> flatMapped = streamStageFromList(input)
                .flatMap(o -> traverseStream(flatMapFn.apply(o)));

        // Then
        flatMapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().flatMap(flatMapFn), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void fusing_map() {
        test_fusing(
                stage -> stage
                        .map(item -> String.format("%04d", item))
                        .map(item -> item + "-x"),
                item -> Stream.of(String.format("%04d-x", item))
        );
    }

    @Test
    public void fusing_flatMap() {
        test_fusing(
                stage -> stage
                        .flatMap(i -> Traversers.traverseItems(String.format("%04d-a", i), String.format("%04d-b", i)))
                        .flatMap(item -> Traversers.traverseItems(item + "1", item + "2")),
                item -> Stream.of(String.format("%04d-a1", item), String.format("%04d-a2", item),
                        String.format("%04d-b1", item), String.format("%04d-b2", item))
        );
    }

    @Test
    public void fusing_filter() {
        test_fusing(
                stage -> stage
                        .filter(i -> i % 2 == 0)
                        .filter(i -> i % 3 == 0)
                        .map(Objects::toString),
                item -> item % 2 == 0 && item % 3 == 0 ? Stream.of(item.toString()) : Stream.empty()
        );
    }

    @Test
    public void fusing_flatMap_with_inputMap() {
        test_fusing(
                stage -> stage
                        .map(Objects::toString)
                        .flatMap(item -> Traversers.traverseItems(item + "-1", item + "-2")),
                item -> Stream.of(item.toString() + "-1", item.toString() + "-2")
        );
    }

    @Test
    public void fusing_flatMap_with_outputMap() {
        test_fusing(
                stage -> stage
                        .flatMap(item -> Traversers.traverseItems(item + "-1", item + "-2"))
                        .map(item -> item + "x"),
                item -> Stream.of(item.toString() + "-1x", item.toString() + "-2x")
        );
    }

    @Test
    public void fusing_flatMapComplex() {
        test_fusing(
                stage -> stage
                        .filter(item -> item % 2 == 0)
                        .map(item -> item + "-x")
                        .flatMap(item -> Traversers.traverseItems(item + "1", item + "2"))
                        .map(item -> item + "y")
                        .flatMap(item -> Traversers.traverseItems(item + "3", item + "4"))
                        .map(item -> item + "z"),
                item -> item % 2 == 0
                        ? Stream.of(item + "-x1y3z", item + "-x2y3z", item + "-x1y4z", item + "-x2y4z")
                        : Stream.empty()
        );
    }

    @Test
    public void fusing_mapToNull_leading() {
        test_fusing(
                stage -> stage
                        .map(item -> (String) null)
                        .flatMap(Traversers::traverseItems),
                item -> Stream.empty()
        );
    }

    @Test
    public void fusing_mapToNull_inside() {
        test_fusing(
                stage -> stage
                        .flatMap(Traversers::traverseItems)
                        .map(item -> (String) null)
                        .flatMap(Traversers::traverseItems),
                item -> Stream.empty()
        );
    }

    @Test
    public void fusing_mapToNull_trailing() {
        test_fusing(
                stage -> stage
                        .flatMap(Traversers::traverseItems)
                        .map(item -> (String) null),
                item -> Stream.empty()
        );
    }

    private void test_fusing(Function<GeneralStage<Integer>, GeneralStage<String>> addToPipelineFn,
                             Function<Integer, Stream<String>> plainFlatMapFn) {
        // Given
        List<Integer> input = sequence(itemCount);

        // When
        StreamStage<Integer> sourceStage = streamStageFromList(input);
        GeneralStage<String> mappedStage = addToPipelineFn.apply(sourceStage);

        // Then
        mappedStage.drainTo(sink);
        assertVertexCount(p.toDag(), 4);
        assertContainsFused(true);
        execute();
        assertEquals(
                streamToString(input.stream().flatMap(plainFlatMapFn), Objects::toString),
                streamToString(sinkList.stream(), Object::toString));
    }

    private void assertVertexCount(DAG dag, int expectedCount) {
        int[] count = {0};
        dag.iterator().forEachRemaining(v -> count[0]++);
        assertEquals("unexpected vertex count in DAG:\n" + dag.toDotString(), expectedCount, count[0]);
    }

    @Test
    public void fusing_testWithBranch() {
        // Given
        List<Integer> input = sequence(itemCount);
        StreamStage<String> mappedSource = streamStageFromList(input)
                .map(item -> item + "-x");

        // When
        StreamStage<String> mapped1 = mappedSource.map(item -> item + "-branch1");
        StreamStage<String> mapped2 = mappedSource.map(item -> item + "-branch2");
        p.drainTo(sink, mapped1, mapped2);

        // Then
        assertContainsFused(false);
        assertVertexCount(p.toDag(), 6);
        execute();
        assertEquals(
                streamToString(input.stream().flatMap(t -> Stream.of(t + "-x-branch1", t + "-x-branch2")), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void fusing_when_localParallelismDifferent_then_notFused() {
        // Given
        List<Integer> input = sequence(itemCount);

        // When
        streamStageFromList(input)
                .map(item -> item + "-a")
                .setLocalParallelism(1)
                .map(item -> item + "b")
                .setLocalParallelism(2)
                .drainTo(sink);

        // Then
        assertContainsFused(false);
        assertVertexCount(p.toDag(), 5);
        execute();
        assertEquals(
                streamToString(input.stream().map(t -> t  + "-ab"), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    private void assertContainsFused(boolean expectedContains) {
        String dotString = p.toDag().toDotString();
        assertEquals(dotString, expectedContains, dotString.contains("fused"));
    }

    @Test
    public void mapUsingContext() {
        // Given
        List<Integer> input = sequence(itemCount);
        BiFunctionEx<String, Integer, String> formatFn = (suffix, i) -> String.format("%04d%s", i, suffix);
        String suffix = "-context";

        // When
        StreamStage<String> mapped = streamStageFromList(input).mapUsingContext(
                ContextFactory.withCreateFn(x -> suffix),
                formatFn);

        // Then
        mapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().map(i -> formatFn.apply(suffix, i)), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void mapUsingContext_keyed() {
        // Given
        List<Integer> input = sequence(itemCount);
        BiFunctionEx<String, Integer, String> formatFn = (suffix, i) -> String.format("%04d%s", i, suffix);
        String suffix = "-keyed-context";

        // When
        StreamStage<String> mapped = streamStageFromList(input)
                .groupingKey(i -> i)
                .mapUsingContext(ContextFactory.withCreateFn(i -> suffix), (suff, k, i) -> formatFn.apply(suff, i));

        // Then
        mapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().map(i -> formatFn.apply(suffix, i)), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void filterUsingContext() {
        // Given
        List<Integer> input = sequence(itemCount);
        int acceptedRemainder = 1;
        Function<Integer, String> formatFn = i -> String.format("%04d", i);

        // When
        StreamStage<Integer> mapped = streamStageFromList(input)
                .filterUsingContext(ContextFactory.withCreateFn(i -> acceptedRemainder), (rem, i) -> i % 2 == rem);

        // Then
        mapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().filter(i -> i % 2 == acceptedRemainder), formatFn),
                streamToString(sinkStreamOf(Integer.class), formatFn));
    }

    @Test
    public void filterUsingContext_keyed() {
        // Given
        List<Integer> input = sequence(itemCount);
        Function<Integer, String> formatFn = i -> String.format("%04d", i);
        int acceptedRemainder = 1;

        // When
        StreamStage<Integer> mapped = streamStageFromList(input)
                .groupingKey(i -> i)
                .filterUsingContext(
                        ContextFactory.withCreateFn(i -> acceptedRemainder),
                        (rem, k, i) -> i % 2 == rem);

        // Then
        mapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().filter(r -> r % 2 == acceptedRemainder), formatFn),
                streamToString(sinkStreamOf(Integer.class), formatFn));
    }

    @Test
    public void flatMapUsingContext() {
        // Given
        List<Integer> input = sequence(itemCount);
        FunctionEx<Integer, Stream<String>> flatMapFn =
                i -> Stream.of("A", "B").map(s -> String.format("%04d-%s", i, s));

        // When
        StreamStage<String> flatMapped = streamStageFromList(input)
                .flatMapUsingContext(
                        ContextFactory.withCreateFn(x -> flatMapFn),
                        (fn, i) -> traverseStream(fn.apply(i))
                );

        // Then
        flatMapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().flatMap(flatMapFn), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void flatMapUsingContext_keyed() {
        // Given
        List<Integer> input = sequence(itemCount);
        FunctionEx<Integer, Stream<String>> flatMapFn =
                i -> Stream.of("A", "B").map(s -> String.format("%04d-%s", i, s));

        // When
        StreamStage<String> flatMapped = streamStageFromList(input)
                .groupingKey(i -> i)
                .flatMapUsingContext(
                        ContextFactory.withCreateFn(x -> flatMapFn),
                        (fn, k, i) -> traverseStream(fn.apply(i))
                );

        // Then
        flatMapped.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().flatMap(flatMapFn), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void mapUsingReplicatedMap() {
        // Given
        List<Integer> input = sequence(itemCount);
        String valuePrefix = "value-";
        ReplicatedMap<Integer, String> map = member.getReplicatedMap(randomMapName());
        // ReplicatedMap: {0 -> "value-0000, 1 -> "value-0001", ...}
        for (int i : input) {
            map.put(i, String.format("%s%04d", valuePrefix, i));
        }

        // When
        StreamStage<Entry<Integer, String>> mapped = streamStageFromList(input)
                .mapUsingReplicatedMap(map, FunctionEx.identity(), Util::entry);

        // Then
        mapped.drainTo(sink);
        execute();
        // sinkList: entry(0, "value-0000"), entry(1, "value-0001"), ...
        assertEquals(
                streamToString(
                        input.stream().map(i -> String.format("(%04d, %s%04d)", i, valuePrefix, i)),
                        identity()),
                streamToString(
                        this.<Integer, String>sinkStreamOfEntry(),
                        e -> String.format("(%04d, %s)", e.getKey(), e.getValue())));
    }

    @Test
    public void mapUsingIMapAsync() {
        // Given
        List<Integer> input = sequence(itemCount);
        String valuePrefix = "value-";
        IMap<Integer, String> map = member.getMap(randomMapName());
        // IMap: {0 -> "value-0000, 1 -> "value-0001", ...}
        for (int i : input) {
            map.put(i, String.format("%s%04d", valuePrefix, i));
        }

        // When
        StreamStage<Entry<Integer, String>> mapped = streamStageFromList(input)
                .mapUsingIMap(map, FunctionEx.identity(), Util::entry);

        // Then
        mapped.drainTo(sink);
        execute();
        // sinkList: entry(0, "value-0000"), entry(1, "value-0001"), ...
        assertEquals(
                streamToString(
                        input.stream().map(i -> String.format("(%04d, %s%04d)", i, valuePrefix, i)),
                        identity()),
                streamToString(
                        this.<Integer, String>sinkStreamOfEntry(),
                        e -> String.format("(%04d, %s)", e.getKey(), e.getValue())));
    }

    @Test
    public void mapUsingIMapAsync_keyed() {
        // Given
        List<Integer> input = sequence(itemCount);
        String valuePrefix = "value-";
        IMap<Integer, String> map = member.getMap(randomMapName());
        // IMap: {0 -> "value-0000, 1 -> "value-0001", ...}
        for (int i : input) {
            map.put(i, String.format("%s%04d", valuePrefix, i));
        }

        // When
        StreamStage<Entry<Integer, String>> mapped = streamStageFromList(input)
                .groupingKey(i -> i)
                .mapUsingIMap(map, Util::entry);

        // Then
        mapped.drainTo(sink);
        execute();
        // sinkList: entry(0, "value-0000"), entry(1, "value-0001"), ...
        assertEquals(
                streamToString(
                        input.stream().map(i -> String.format("(%04d, %s%04d)", i, valuePrefix, i)),
                        identity()),
                streamToString(
                        this.<Integer, String>sinkStreamOfEntry(),
                        e -> String.format("(%04d, %s)", e.getKey(), e.getValue())));
    }

    @Test
    public void rollingAggregate() {
        // Given
        List<Integer> input = sequence(itemCount);

        // When
        StreamStage<Long> rolled = streamStageFromList(input)
                .rollingAggregate(counting());

        // Then
        rolled.drainTo(sink);
        execute();
        Function<Object, String> formatFn = i -> String.format("%04d", (Long) i);
        assertEquals(
                streamToString(LongStream.rangeClosed(1, itemCount).boxed(), formatFn),
                streamToString(sinkList.stream(), formatFn));
    }

    @Test
    public void rollingAggregate_keyed() {
        // Given
        List<Integer> input = sequence(itemCount);

        // When
        StreamStage<Entry<Integer, Long>> mapped = streamStageFromList(input)
                .groupingKey(i -> i % 2)
                .rollingAggregate(counting());

        // Then
        mapped.drainTo(sink);
        execute();
        Function<Entry<Integer, Long>, String> formatFn = e -> String.format("(%d, %04d)", e.getKey(), e.getValue());
        assertEquals(
                streamToString(
                        IntStream.range(2, itemCount + 2).mapToObj(i -> entry(i % 2, (long) i / 2)),
                        formatFn),
                streamToString(sinkStreamOfEntry(), formatFn));
    }

    @Test
    public void when_rollingAggregateWithTimestamps_then_timestampsPropagated() {
        // Given
        List<Integer> input = sequence(itemCount);
        AggregateOperation1<Integer, LongAccumulator, Integer> identity = AggregateOperation
                .withCreate(LongAccumulator::new)
                .<Integer>andAccumulate((acc, i) -> acc.set((long) i))
                .andExportFinish(acc -> (int) acc.get());

        // When
        StreamStage<Integer> rolling = streamStageFromList(input).rollingAggregate(identity);

        // Then
        rolling.window(tumbling(1))
               .aggregate(identity)
               .drainTo(sink);
        execute();
        assertEquals(
                LongStream.range(0, itemCount)
                          .mapToObj(i -> String.format("(%04d %04d)", i + 1, i))
                          .collect(joining("\n")),
                streamToString(
                        this.<Long>sinkStreamOfWinResult(),
                        wr -> String.format("(%04d %04d)", wr.end(), wr.result()))
        );
    }

    @Test
    public void merge() {
        // Given
        List<Integer> input = sequence(itemCount);
        Function<Integer, String> formatFn = i -> String.format("%04d", i);
        StreamStage<Integer> srcStage0 = streamStageFromList(input);
        StreamStage<Integer> srcStage1 = streamStageFromList(input);

        // When
        StreamStage<Integer> merged = srcStage0.merge(srcStage1);

        // Then
        merged.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().flatMap(i -> Stream.of(i, i)), formatFn),
                streamToString(sinkStreamOf(Integer.class), formatFn));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void hashJoin() {
        // Given
        List<Integer> input = sequence(itemCount);
        String prefixA = "A";
        // entry(0, "A-0000"), entry(1, "A-0001"), ...
        BatchStage<Entry<Integer, String>> enrichingStage = enrichingStage(input, prefixA);

        // When
        @SuppressWarnings("Convert2MethodRef")
        // there's a method ref bug in JDK
        StreamStage<Tuple2<Integer, String>> hashJoined = streamStageFromList(input).hashJoin(
                enrichingStage,
                joinMapEntries(wholeItem()),
                (i, valueA) -> tuple2(i, valueA)
        );

        // Then
        hashJoined.drainTo(sink);
        execute();
        BiFunction<Integer, String, String> formatFn = (i, value) -> String.format("(%04d, %s)", i, value);
        // sinkList: tuple2(0, "A-0000"), tuple2(1, "A-0001"), ...
        assertEquals(
                streamToString(
                        input.stream().map(i -> formatFn.apply(i, ENRICHING_FORMAT_FN.apply(prefixA, i))),
                        identity()),
                streamToString(
                        sinkList.stream().map(t2 -> (Tuple2<Integer, String>) t2),
                        t2 -> formatFn.apply(t2.f0(), t2.f1()))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    public void hashJoin2() {
        // Given
        List<Integer> input = sequence(itemCount);
        String prefixA = "A";
        String prefixB = "B";
        // entry(0, "A-0000"), entry(1, "A-0001"), ...
        BatchStage<Entry<Integer, String>> enrichingStage1 = enrichingStage(input, prefixA);
        // entry(0, "B-0000"), entry(1, "B-0001"), ...
        BatchStage<Entry<Integer, String>> enrichingStage2 = enrichingStage(input, prefixB);

        // When
        @SuppressWarnings("Convert2MethodRef")
        // there's a method ref bug in JDK
        StreamStage<Tuple3<Integer, String, String>> hashJoined = streamStageFromList(input).hashJoin2(
                enrichingStage1, joinMapEntries(wholeItem()),
                enrichingStage2, joinMapEntries(wholeItem()),
                (i, valueA, valueB) -> tuple3(i, valueA, valueB)
        );

        // Then
        hashJoined.drainTo(sink);
        execute();

        TriFunction<Integer, String, String, String> formatFn =
                (i, valueA, valueB) -> String.format("(%04d, %s, %s)", i, valueA, valueB);
        // sinkList: tuple3(0, "A-0000", "B-0000"), tuple3(1, "A-0001", "B-0001"), ...
        assertEquals(
                streamToString(
                        input.stream().map(i -> formatFn.apply(i,
                                ENRICHING_FORMAT_FN.apply(prefixA, i),
                                ENRICHING_FORMAT_FN.apply(prefixB, i))),
                        identity()),
                streamToString(sinkList.stream().map(t3 -> (Tuple3<Integer, String, String>) t3),
                        t3 -> formatFn.apply(t3.f0(), t3.f1(), t3.f2()))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    public void hashJoinBuilder() {
        // Given
        List<Integer> input = sequence(itemCount);
        String prefixA = "A";
        String prefixB = "B";
        // entry(0, "A-0000"), entry(1, "A-0001"), ...
        BatchStage<Entry<Integer, String>> enrichingStage1 = enrichingStage(input, prefixA);
        // entry(0, "B-0000"), entry(1, "B-0001"), ...
        BatchStage<Entry<Integer, String>> enrichingStage2 = enrichingStage(input, prefixB);

        // When
        StreamHashJoinBuilder<Integer> builder = streamStageFromList(input).hashJoinBuilder();
        Tag<String> tagA = builder.add(enrichingStage1, joinMapEntries(wholeItem()));
        Tag<String> tagB = builder.add(enrichingStage2, joinMapEntries(wholeItem()));
        @SuppressWarnings("Convert2MethodRef")
        // there's a method ref bug in JDK
        StreamStage<Tuple2<Integer, ItemsByTag>> joined = builder.build((a, b) -> tuple2(a, b));

        // Then
        joined.drainTo(sink);
        execute();

        TriFunction<Integer, String, String, String> formatFn =
                (i, valueA, valueB) -> String.format("(%04d, %s, %s)", i, valueA, valueB);
        // sinkList: tuple2(0, ibt(tagA: "A-0000", tagB: "B-0000")), tuple2(1, ibt(tagA: "A-0001", tagB: "B-0001"))
        assertEquals(
                streamToString(
                        input.stream().map(i -> formatFn.apply(i,
                                ENRICHING_FORMAT_FN.apply(prefixA, i),
                                ENRICHING_FORMAT_FN.apply(prefixB, i))),
                        identity()),
                streamToString(sinkList.stream().map(t2 -> (Tuple2<Integer, ItemsByTag>) t2),
                        t2 -> formatFn.apply(t2.f0(), t2.f1().get(tagA), t2.f1().get(tagB)))
        );
    }

    private BatchStage<Entry<Integer, String>> enrichingStage(List<Integer> input, String prefix) {
        return p.drawFrom(SourceBuilder.batch("data", x -> null)
                .<Entry<Integer, String>>fillBufferFn((x, buf) -> {
                    input.forEach(i -> buf.add(entry(i, ENRICHING_FORMAT_FN.apply(prefix, i))));
                    buf.close();
                }).build());
    }

    @Test
    public void apply() {
        // Given
        List<Integer> input = sequence(itemCount);

        // When
        StreamStage<String> mapped = streamStageFromList(input)
                .apply(s -> s.map(i -> i + 1)
                             .map(String::valueOf));

        // Then
        mapped.drainTo(sink);
        execute();
        assertEquals(streamToString(input.stream(), i -> String.valueOf(i + 1)),
                streamToString(sinkStreamOf(String.class), identity()));
    }

    @Test
    public void customTransform() {
        // Given
        List<Integer> input = sequence(itemCount);
        FunctionEx<Integer, String> mapFn = item -> String.format("%04d-x", item);

        // When
        StreamStage<String> custom = streamStageFromList(input).customTransform("map",
                Processors.mapP(o -> {
                    @SuppressWarnings("unchecked")
                    JetEvent<Integer> jetEvent = (JetEvent<Integer>) o;
                    return jetEvent(jetEvent.timestamp(), mapFn.apply(jetEvent.payload()));
                }));

        // Then
        custom.drainTo(sink);
        execute();
        assertEquals(
                streamToString(input.stream().map(mapFn), identity()),
                streamToString(sinkList.stream(), Object::toString));
    }

    @Test
    public void customTransform_keyed() {
        // Given
        List<Integer> input = sequence(itemCount);
        FunctionEx<Integer, Integer> extractKeyFn = i -> i % 2;

        // When
        StreamStage<Object> custom = streamStageFromList(input)
                .groupingKey(extractKeyFn)
                .customTransform("map", Processors.mapUsingContextP(
                        ContextFactory.withCreateFn(jet -> new HashSet<>()),
                        (Set<Integer> seen, JetEvent<Integer> jetEvent) -> {
                            Integer key = extractKeyFn.apply(jetEvent.payload());
                            return seen.add(key) ? jetEvent(jetEvent.timestamp(), key) : null;
                        }));

        // Then
        custom.drainTo(sink);
        execute();

        // Each processor emitted distinct keys it observed. If groupingKey isn't
        // correctly partitioning, multiple processors will observe the same keys.
        assertEquals("0\n1", streamToString(sinkStreamOf(Integer.class), Object::toString));
    }

    @Test
    public void peek_when_addedTimestamp_then_unwrapsJetEvent() {
        // Given
        List<Integer> input = sequence(itemCount);

        // When
        StreamStage<Integer> peeked = streamStageFromList(input).peek();

        // Then
        peeked.drainTo(sink);
        execute();
        Function<Integer, String> formatFn = i -> String.format("%04d", i);
        assertEquals(
                streamToString(input.stream(), formatFn),
                streamToString(sinkStreamOf(Integer.class), formatFn));
    }

    @Test
    public void peekWithToStringFunctionIsTransparent() {
        // Given
        List<Integer> input = sequence(itemCount);
        PredicateEx<Integer> filterFn = i -> i % 2 == 1;
        Function<Integer, String> formatFn = i -> String.format("%04d", i);

        // When
        streamStageFromList(input)
         .filter(filterFn)
         .peek(Object::toString)
         .drainTo(sink);

        // Then
        execute();
        assertEquals(
                streamToString(input.stream().filter(filterFn), formatFn),
                streamToString(sinkStreamOf(Integer.class), formatFn));
    }
}
