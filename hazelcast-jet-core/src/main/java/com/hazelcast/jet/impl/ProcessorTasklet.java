/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.impl;

import com.hazelcast.jet.Inbox;
import com.hazelcast.jet.Processor;
import com.hazelcast.util.Preconditions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;

import static com.hazelcast.jet.impl.DoneItem.DONE_ITEM;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toCollection;

public class ProcessorTasklet implements Tasklet {

    private static final int DEFAULT_HIGH_WATER_MARK = 2048;

    private final ArrayDequeInbox inbox = new ArrayDequeInbox();
    private final ProgressTracker progTracker = new ProgressTracker();
    private final Processor processor;
    private final Queue<ArrayList<InboundEdgeStream>> instreamGroupQueue;
    private CircularCursor<InboundEdgeStream> instreamCursor;
    private final ArrayDequeOutbox outbox;
    private final OutboundEdgeStream[] outstreams;

    private InboundEdgeStream currInstream;
    private boolean currInstreamExhausted;
    private boolean processorCompleted;

    public ProcessorTasklet(
            Processor processor, List<InboundEdgeStream> instreams, List<OutboundEdgeStream> outstreams
    ) {
        Preconditions.checkNotNull(processor, "processor");
        this.processor = processor;
        this.instreamGroupQueue = instreams
                .stream()
                .collect(groupingBy(InboundEdgeStream::priority, TreeMap::new, toCollection(ArrayList::new)))
                .entrySet().stream()
                .map(Entry::getValue)
                .collect(toCollection(ArrayDeque::new));
        this.outbox = new ArrayDequeOutbox(outstreams.size(), DEFAULT_HIGH_WATER_MARK);
        this.outstreams = outstreams.stream()
                                    .sorted(comparing(OutboundEdgeStream::ordinal))
                                    .toArray(OutboundEdgeStream[]::new);
        this.instreamCursor = popInstreamGroup();
    }

    @Override
    public void init() {
        processor.init(outbox);
    }

    @Override
    public boolean isBlocking() {
        return processor.isBlocking();
    }

    @Override
    public ProgressState call() {
        progTracker.reset();
        tryFillInbox();
        if (progTracker.isDone()) {
            completeIfNeeded();
        } else if (!inbox.isEmpty()) {
            tryProcessInbox();
        } else if (currInstreamExhausted) {
            progTracker.madeProgress(true);
            if (processor.complete(currInstream.ordinal())) {
                currInstream = null;
            }
        }
        tryFlushOutbox();
        return progTracker.toProgressState();
    }

    private CircularCursor<InboundEdgeStream> popInstreamGroup() {
        return Optional.ofNullable(instreamGroupQueue.poll()).map(CircularCursor::new).orElse(null);
    }

    private void tryFillInbox() {
        // we have more items in inbox, or current inbound stream is exhausted but its processing hasn't completed
        if (!inbox.isEmpty() || currInstream != null && currInstreamExhausted) {
            progTracker.notDone();
            return;
        }
        if (instreamCursor == null) {
            return;
        }
        progTracker.notDone();
        final InboundEdgeStream first = instreamCursor.value();
        ProgressState result;
        do {
            currInstream = instreamCursor.value();
            result = currInstream.drainTo(inbox);
            progTracker.madeProgress(result.isMadeProgress());
            currInstreamExhausted = result.isDone();
            if (currInstreamExhausted) {
                instreamCursor.remove();
            }
            if (!instreamCursor.advance()) {
                instreamCursor = popInstreamGroup();
                return;
            }
        } while (!result.isMadeProgress() && instreamCursor.value() != first);
    }

    private void tryProcessInbox() {
        if (outbox.isHighWater()) {
            progTracker.notDone();
            return;
        }
        progTracker.madeProgress(true);
        final int inboundOrdinal = currInstream.ordinal();
        processor.process(inboundOrdinal, inbox);
        if (!inbox.isEmpty()) {
            progTracker.notDone();
        }
    }

    private void completeIfNeeded() {
        if (processorCompleted) {
            return;
        }
        progTracker.madeProgress(true);
        if (!processor.complete()) {
            progTracker.notDone();
            return;
        }
        processorCompleted = true;
        for (OutboundEdgeStream outstream : outstreams) {
            outbox.add(outstream.ordinal(), DONE_ITEM);
        }
    }

    private void tryFlushOutbox() {
        nextOutstream:
        for (int i = 0; i < outbox.queueCount(); i++) {
            final Queue q = outbox.queueWithOrdinal(i);
            for (Object item; (item = q.peek()) != null; ) {
                final ProgressState state =
                        item != DONE_ITEM ? outstreams[i].getCollector().offer(item)
                                : outstreams[i].getCollector().close();
                progTracker.madeProgress(state.isMadeProgress());
                if (!state.isDone()) {
                    progTracker.notDone();
                    continue nextOutstream;
                }
                q.remove();
            }
        }
    }

    @Override
    public String toString() {
        return "ProcessorTasklet{processor=" + processor + '}';
    }

    private static final class ArrayDequeInbox extends ArrayDeque<Object> implements Inbox {
    }
}
