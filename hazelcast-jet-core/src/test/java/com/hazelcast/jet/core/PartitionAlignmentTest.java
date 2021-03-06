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

package com.hazelcast.jet.core;

import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.core.TestProcessors.ListSource;
import com.hazelcast.jet.core.processor.SinkProcessors;
import com.hazelcast.jet.function.SupplierEx;
import com.hazelcast.test.HazelcastParallelClassRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.Edge.from;
import static com.hazelcast.jet.core.TestUtil.executeAndPeel;
import static com.hazelcast.jet.function.Functions.wholeItem;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
public class PartitionAlignmentTest extends JetTestSupport {

    private static final int ITEM_COUNT = 32;
    private static final int PARTITION_COUNT = ITEM_COUNT / 2;

    private JetInstance instance;

    @Before
    public void before() {
        final JetConfig cfg = new JetConfig();
        cfg.getHazelcastConfig().setProperty("hazelcast.partition.count", String.valueOf(PARTITION_COUNT));
        instance = createJetMembers(cfg, 2)[0];
    }

    @Test
    public void when_localAndDistributedEdges_thenPartitionsAligned() throws Throwable {
        final int localProcessorCount = PARTITION_COUNT / 4;
        final List<Integer> items = range(0, ITEM_COUNT).boxed().collect(toList());
        final SupplierEx<Processor> supplierOfListProducer = () -> new ListSource(items);
        final Partitioner<Integer> partitioner = (item, partitionCount) -> item % partitionCount;

        final DAG dag = new DAG();

        final Vertex distributedProducer = dag.newVertex("distributedProducer", supplierOfListProducer)
                                              .localParallelism(1);
        final Vertex localProducer = dag.newVertex("localProducer", supplierOfListProducer)
                                        .localParallelism(1);
        final Vertex processor = dag.newVertex("processor", Counter::new).localParallelism(localProcessorCount);
        final Vertex consumer = dag.newVertex("consumer", SinkProcessors.writeListP("numbers"))
                                   .localParallelism(1);

        dag.edge(between(distributedProducer, processor).partitioned(wholeItem(), partitioner).distributed())
           .edge(from(localProducer).to(processor, 1).partitioned(wholeItem(), partitioner))
           .edge(between(processor, consumer));

        executeAndPeel(instance.newJob(dag));
        assertEquals(
                items.stream()
                     .flatMap(i -> IntStream.of(0, 2)
                                            .mapToObj(count -> describe(i, new int[]{count, 1})))
                     .collect(toList()),
                instance.getHazelcastInstance().getList("numbers").stream().sorted().collect(toList())
        );
    }

    private static String describe(int i, int[] counts) {
        return String.format("%n%2d observed %s times", i, Arrays.toString(counts));
    }

    private static class Counter extends AbstractProcessor {
        // item -> [ordinal0_count, ordinal1_count]
        private final Map<Integer, int[]> counts = new HashMap<>();

        @Override
        protected boolean tryProcess(int ordinal, @Nonnull Object item) {
            counts.computeIfAbsent((Integer) item, x -> new int[2])[ordinal]++;
            return true;
        }

        @Override
        public boolean complete() {
            counts.forEach((key, value) -> assertTrue(tryEmit(describe(key, value))));
            return true;
        }
    }
}
