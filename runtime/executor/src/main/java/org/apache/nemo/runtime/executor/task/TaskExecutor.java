/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.nemo.runtime.executor.task;

import com.google.common.collect.Lists;
import org.apache.nemo.common.Pair;
import org.apache.nemo.common.dag.DAG;
import org.apache.nemo.common.ir.OutputCollector;
import org.apache.nemo.common.ir.Readable;
import org.apache.nemo.common.ir.edge.executionproperty.AdditionalOutputTagProperty;
import org.apache.nemo.common.ir.edge.executionproperty.BroadcastVariableIdProperty;
import org.apache.nemo.common.ir.vertex.*;
import org.apache.nemo.common.ir.vertex.transform.AggregateMetricTransform;
import org.apache.nemo.common.ir.vertex.transform.Transform;
import org.apache.nemo.common.punctuation.Watermark;
import org.apache.nemo.common.punctuation.Finishmark;
import org.apache.nemo.runtime.common.RuntimeIdManager;
import org.apache.nemo.runtime.common.comm.ControlMessage;
import org.apache.nemo.runtime.common.message.MessageEnvironment;
import org.apache.nemo.runtime.common.message.PersistentConnectionToMasterMap;
import org.apache.nemo.runtime.common.plan.Task;
import org.apache.nemo.runtime.common.plan.StageEdge;
import org.apache.nemo.runtime.common.plan.RuntimeEdge;
import org.apache.nemo.runtime.common.state.TaskState;
import org.apache.nemo.runtime.executor.MetricMessageSender;
import org.apache.nemo.runtime.executor.TaskStateManager;
import org.apache.nemo.runtime.executor.TransformContextImpl;
import org.apache.nemo.runtime.executor.data.BroadcastManagerWorker;
import org.apache.nemo.runtime.executor.datatransfer.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.nemo.runtime.executor.datatransfer.DynOptDataOutputCollector;
import org.apache.nemo.runtime.executor.datatransfer.OperatorVertexOutputCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Executes a task.
 * Should be accessed by a single thread.
 */
@NotThreadSafe
public final class TaskExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(TaskExecutor.class.getName());

  // Essential information
  private boolean isExecuted;
  private final String taskId;
  private final TaskStateManager taskStateManager;
  private final List<DataFetcher> nonBroadcastDataFetchers;
  private final BroadcastManagerWorker broadcastManagerWorker;
  private final List<VertexHarness> sortedHarnesses;

  // Metrics information
  private long boundedSourceReadTime = 0;
  private long serializedReadBytes = 0;
  private long encodedReadBytes = 0;
  private final MetricMessageSender metricMessageSender;

  // Dynamic optimization
  private String idOfVertexPutOnHold;

  private final PersistentConnectionToMasterMap persistentConnectionToMasterMap;

  /**
   * Constructor.
   *
   * @param task                   Task with information needed during execution.
   * @param irVertexDag            A DAG of vertices.
   * @param taskStateManager       State manager for this Task.
   * @param dataTransferFactory    For reading from/writing to data to other tasks.
   * @param broadcastManagerWorker For broadcasts.
   * @param metricMessageSender    For sending metric with execution stats to Master.
   */
  public TaskExecutor(final Task task,
                      final DAG<IRVertex, RuntimeEdge<IRVertex>> irVertexDag,
                      final TaskStateManager taskStateManager,
                      final DataTransferFactory dataTransferFactory,
                      final BroadcastManagerWorker broadcastManagerWorker,
                      final MetricMessageSender metricMessageSender,
                      final PersistentConnectionToMasterMap persistentConnectionToMasterMap) {
    // Essential information
    this.isExecuted = false;
    this.taskId = task.getTaskId();
    this.taskStateManager = taskStateManager;
    this.broadcastManagerWorker = broadcastManagerWorker;

    // Metric sender
    this.metricMessageSender = metricMessageSender;

    // Dynamic optimization
    // Assigning null is very bad, but we are keeping this for now
    this.idOfVertexPutOnHold = null;

    this.persistentConnectionToMasterMap = persistentConnectionToMasterMap;

    // Prepare data structures
    final Pair<List<DataFetcher>, List<VertexHarness>> pair = prepare(task, irVertexDag, dataTransferFactory);
    this.nonBroadcastDataFetchers = pair.left();
    this.sortedHarnesses = pair.right();
  }

  /**
   * Converts the DAG of vertices into pointer-based DAG of vertex harnesses.
   * This conversion is necessary for constructing concrete data channels for each vertex's inputs and outputs.
   * <p>
   * - Source vertex read: Explicitly handled (SourceVertexDataFetcher)
   * - Sink vertex write: Implicitly handled within the vertex
   * <p>
   * - Parent-task read: Explicitly handled (ParentTaskDataFetcher)
   * - Children-task write: Explicitly handled (VertexHarness)
   * <p>
   * - Intra-task read: Implicitly handled when performing Intra-task writes
   * - Intra-task write: Explicitly handled (VertexHarness)
   * <p>
   * For element-wise data processing, we traverse vertex harnesses from the roots to the leaves for each element.
   * This means that overheads associated with jumping from one harness to the other should be minimal.
   * For example, we should never perform an expensive hash operation to traverse the harnesses.
   *
   * @param task        task.
   * @param irVertexDag dag.
   * @return fetchers and harnesses.
   */
  private Pair<List<DataFetcher>, List<VertexHarness>> prepare(final Task task,
                                                               final DAG<IRVertex, RuntimeEdge<IRVertex>> irVertexDag,
                                                               final DataTransferFactory dataTransferFactory) {
    final int taskIndex = RuntimeIdManager.getIndexFromTaskId(task.getTaskId());

    // Traverse in a reverse-topological order to ensure that each visited vertex's children vertices exist.
    final List<IRVertex> reverseTopologicallySorted = Lists.reverse(irVertexDag.getTopologicalSort());

    // Create a harness for each vertex
    final List<DataFetcher> nonBroadcastDataFetcherList = new ArrayList<>();
    final Map<String, VertexHarness> vertexIdToHarness = new HashMap<>();
    reverseTopologicallySorted.forEach(irVertex -> {
      final Optional<Readable> sourceReader = getSourceVertexReader(irVertex, task.getIrVertexIdToReadable());
      if (sourceReader.isPresent() != irVertex instanceof SourceVertex) {
        throw new IllegalStateException(irVertex.toString());
      }

      // Additional outputs
      final Map<String, List<OperatorVertex>> internalAdditionalOutputMap =
        getInternalAdditionalOutputMap(irVertex, irVertexDag);
      final Map<String, List<OutputWriter>> externalAdditionalOutputMap =
        getExternalAdditionalOutputMap(irVertex, task.getTaskOutgoingEdges(), dataTransferFactory);

      // Main outputs
      final List<OperatorVertex> internalMainOutputs = getInternalMainOutputs(irVertex, irVertexDag);
      final List<OutputWriter> externalMainOutputs =
        getExternalMainOutputs(irVertex, task.getTaskOutgoingEdges(), dataTransferFactory);

      final OutputCollector outputCollector;

      if (irVertex instanceof OperatorVertex
        && ((OperatorVertex) irVertex).getTransform() instanceof AggregateMetricTransform) {
        outputCollector = new DynOptDataOutputCollector(
          irVertex, persistentConnectionToMasterMap, this);
      } else {
        outputCollector = new OperatorVertexOutputCollector(
          irVertex, internalMainOutputs, internalAdditionalOutputMap,
          externalMainOutputs, externalAdditionalOutputMap);
      }

      // Create VERTEX HARNESS
      final VertexHarness vertexHarness = new VertexHarness(
        irVertex, outputCollector, new TransformContextImpl(broadcastManagerWorker),
        externalMainOutputs, externalAdditionalOutputMap);

      prepareTransform(vertexHarness);
      vertexIdToHarness.put(irVertex.getId(), vertexHarness);

      // Prepare data READ
      // Source read
      if (irVertex instanceof SourceVertex) {
        // Source vertex read
        nonBroadcastDataFetcherList.add(new SourceVertexDataFetcher(
          (SourceVertex) irVertex, sourceReader.get(), outputCollector));
      }

      // Parent-task read (broadcasts)
      final List<StageEdge> inEdgesForThisVertex = task.getTaskIncomingEdges()
        .stream()
        .filter(inEdge -> inEdge.getDstIRVertex().getId().equals(irVertex.getId()))
        .collect(Collectors.toList());
      final List<StageEdge> broadcastInEdges = inEdgesForThisVertex
        .stream()
        .filter(stageEdge -> stageEdge.getPropertyValue(BroadcastVariableIdProperty.class).isPresent())
        .collect(Collectors.toList());
      final List<InputReader> broadcastReaders =
        getParentTaskReaders(taskIndex, broadcastInEdges, dataTransferFactory);
      if (broadcastInEdges.size() != broadcastReaders.size()) {
        throw new IllegalStateException(broadcastInEdges.toString() + ", " + broadcastReaders.toString());
      }
      for (int i = 0; i < broadcastInEdges.size(); i++) {
        final StageEdge inEdge = broadcastInEdges.get(i);
        broadcastManagerWorker.registerInputReader(
          inEdge.getPropertyValue(BroadcastVariableIdProperty.class)
            .orElseThrow(() -> new IllegalStateException(inEdge.toString())),
          broadcastReaders.get(i));
      }
      // Parent-task read (non-broadcasts)
      final List<StageEdge> nonBroadcastInEdges = new ArrayList<>(inEdgesForThisVertex);
      nonBroadcastInEdges.removeAll(broadcastInEdges);
      final List<InputReader> nonBroadcastReaders =
        getParentTaskReaders(taskIndex, nonBroadcastInEdges, dataTransferFactory);
      nonBroadcastReaders.forEach(parentTaskReader -> nonBroadcastDataFetcherList.add(
        new ParentTaskDataFetcher(parentTaskReader.getSrcIrVertex(), parentTaskReader,
          new DataFetcherOutputCollector((OperatorVertex) irVertex))));
    });

    final List<VertexHarness> sortedHarnessList = irVertexDag.getTopologicalSort()
      .stream()
      .map(vertex -> vertexIdToHarness.get(vertex.getId()))
      .collect(Collectors.toList());

    return Pair.of(nonBroadcastDataFetcherList, sortedHarnessList);
  }

  /**
   * Process a data element down the DAG dependency.
   */
  private void processElement(final OutputCollector outputCollector, final Object dataElement) {
    outputCollector.emit(dataElement);
  }

  private void processWatermark(final OutputCollector outputCollector, final Watermark watermark) {
    // TODO #231: Add onWatermark() method to Transform and
    // TODO #231: fowards watermark to Transforms and OutputWriters
  }

  /**
   * Execute a task, while handling unrecoverable errors and exceptions.
   */
  public void execute() {
    try {
      doExecute();
    } catch (Throwable throwable) {
      // ANY uncaught throwable is reported to the master
      taskStateManager.onTaskStateChanged(TaskState.State.FAILED, Optional.empty(), Optional.empty());
      LOG.error(ExceptionUtils.getStackTrace(throwable));
    }
  }

  /**
   * The task is executed in the following two phases.
   * - Phase 1: Consume task-external input data (non-broadcasts)
   * - Phase 2: Finalize task-internal states and data elements
   */
  private void doExecute() {
    // Housekeeping stuff
    if (isExecuted) {
      throw new RuntimeException("Task {" + taskId + "} execution called again");
    }
    LOG.info("{} started", taskId);
    taskStateManager.onTaskStateChanged(TaskState.State.EXECUTING, Optional.empty(), Optional.empty());

    // Phase 1: Consume task-external input data. (non-broadcasts)
    if (!handleDataFetchers(nonBroadcastDataFetchers)) {
      return;
    }

    metricMessageSender.send("TaskMetric", taskId,
      "boundedSourceReadTime", SerializationUtils.serialize(boundedSourceReadTime));
    metricMessageSender.send("TaskMetric", taskId,
      "serializedReadBytes", SerializationUtils.serialize(serializedReadBytes));
    metricMessageSender.send("TaskMetric", taskId,
      "encodedReadBytes", SerializationUtils.serialize(encodedReadBytes));

    // Phase 2: Finalize task-internal states and elements
    for (final VertexHarness vertexHarness : sortedHarnesses) {
      finalizeVertex(vertexHarness);
    }

    if (idOfVertexPutOnHold == null) {
      taskStateManager.onTaskStateChanged(TaskState.State.COMPLETE, Optional.empty(), Optional.empty());
      LOG.info("{} completed", taskId);
    } else {
      taskStateManager.onTaskStateChanged(TaskState.State.ON_HOLD,
        Optional.of(idOfVertexPutOnHold),
        Optional.empty());
      LOG.info("{} on hold", taskId);
    }
  }

  private void finalizeVertex(final VertexHarness vertexHarness) {
    closeTransform(vertexHarness);
    finalizeOutputWriters(vertexHarness);
  }

  /**
   * Process an element generated from the dataFetcher.
   * If the element is an instance of Finishmark, we remove the dataFetcher from the current list.
   * @param element element
   * @param dataFetcher current data fetcher
   * @param dataFetchers current list
   */
  private void handleElement(final Object element,
                             final DataFetcher dataFetcher,
                             final List<DataFetcher> dataFetchers) {
    if (element instanceof Finishmark) {
      // We've consumed all the data from this data fetcher.
      if (dataFetcher instanceof SourceVertexDataFetcher) {
        boundedSourceReadTime += ((SourceVertexDataFetcher) dataFetcher).getBoundedSourceReadTime();
      } else if (dataFetcher instanceof ParentTaskDataFetcher) {
        serializedReadBytes += ((ParentTaskDataFetcher) dataFetcher).getSerializedBytes();
        encodedReadBytes += ((ParentTaskDataFetcher) dataFetcher).getEncodedBytes();
      }

      // remove current data fetcher from the list
      dataFetchers.remove(dataFetcher);
    } else if (element instanceof Watermark) {
      // Watermark
      processWatermark(dataFetcher.getOutputCollector(), (Watermark) element);
    } else {
      // Process data element
      processElement(dataFetcher.getOutputCollector(), element);
    }
  }

  /**
   * Check if it is time to poll pending fetchers' data.
   * @param pollingPeriod polling period
   * @param currentTime current time
   * @param prevTime prev time
   */
  private boolean isPollingTime(final long pollingPeriod,
                                final long currentTime,
                                final long prevTime) {
    return (currentTime - prevTime) >= pollingPeriod;
  }

  /**
   * This retrieves data from data fetchers and process them.
   * It maintains two lists:
   *  -- availableFetchers: maintain data fetchers that currently have data elements to retreive
   *  -- pendingFetchers: maintain data fetchers that currently do not have available elements.
   *     This can become available in the future, and therefore we check the pending fetchers every pollingInterval.
   *
   *  If a data fetcher finishes, we remove it from the two lists.
   *  If a data fetcher has no available element, we move the data fetcher to pendingFetchers
   *  If a pending data fetcher has element, we move it to availableFetchers
   *  If there are no available fetchers but pending fetchers, sleep for pollingPeriod
   *  and retry fetching data from the pendingFetchers.
   *
   * @param fetchers to handle.
   * @return false if IOException.
   */
  private boolean handleDataFetchers(final List<DataFetcher> fetchers) {
    final List<DataFetcher> availableFetchers = new LinkedList<>(fetchers);
    final List<DataFetcher> pendingFetchers = new LinkedList<>();

    // Polling interval.
    final long pollingInterval = 100; // ms

    // Previous polling time
    long prevPollingTime = System.currentTimeMillis();

    // empty means we've consumed all task-external input data
    while (!availableFetchers.isEmpty() || !pendingFetchers.isEmpty()) {
      // We first fetch data from available data fetchers
      final Iterator<DataFetcher> availableIterator = availableFetchers.iterator();

      while (availableIterator.hasNext()) {
        final DataFetcher dataFetcher = availableIterator.next();
        try {
          handleElement(dataFetcher.fetchDataElement(), dataFetcher, availableFetchers);
        } catch (final NoSuchElementException e) {
          // No element in current data fetcher, fetch data from next fetcher
          // move current data fetcher to pending.
          availableIterator.remove();
          pendingFetchers.add(dataFetcher);
        } catch (final IOException e) {
          // IOException means that this task should be retried.
          taskStateManager.onTaskStateChanged(TaskState.State.SHOULD_RETRY,
            Optional.empty(), Optional.of(TaskState.RecoverableTaskFailureCause.INPUT_READ_FAILURE));
          LOG.error("{} Execution Failed (Recoverable: input read failure)! Exception: {}", taskId, e);
          return false;
        }
      }

      final Iterator<DataFetcher> pendingIterator = pendingFetchers.iterator();
      final long currentTime = System.currentTimeMillis();
      // We check pending data every polling interval
      while (pendingIterator.hasNext()
        && isPollingTime(pollingInterval, currentTime, prevPollingTime)) {
        prevPollingTime = currentTime;

        final DataFetcher dataFetcher = pendingIterator.next();
        try {
          handleElement(dataFetcher.fetchDataElement(), dataFetcher, pendingFetchers);

          // We processed data. This means the data fetcher is now available.
          // Add current data fetcher to available
          pendingIterator.remove();
          availableFetchers.add(dataFetcher);

        } catch (final NoSuchElementException e) {
          // The current data fetcher is still pending.. try next data fetcher
        } catch (final IOException e) {
          // IOException means that this task should be retried.
          taskStateManager.onTaskStateChanged(TaskState.State.SHOULD_RETRY,
            Optional.empty(), Optional.of(TaskState.RecoverableTaskFailureCause.INPUT_READ_FAILURE));
          LOG.error("{} Execution Failed (Recoverable: input read failure)! Exception: {}", taskId, e);
          return false;
        }
      }

      // If there are no available fetchers,
      // Sleep and retry fetching element from pending fetchers every polling interval
      if (availableFetchers.isEmpty() && !pendingFetchers.isEmpty()) {
        try {
          Thread.sleep(pollingInterval);
        } catch (InterruptedException e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
      }
    }

    // Close all data fetchers
    fetchers.forEach(fetcher -> {
      try {
        fetcher.close();
      } catch (final Exception e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    });

    return true;
  }

  ////////////////////////////////////////////// Helper methods for setting up initial data structures
  private Map<String, List<OutputWriter>> getExternalAdditionalOutputMap(
    final IRVertex irVertex,
    final List<StageEdge> outEdgesToChildrenTasks,
    final DataTransferFactory dataTransferFactory) {
    // Add all inter-task additional tags to additional output map.
    final Map<String, List<OutputWriter>> map = new HashMap<>();

    outEdgesToChildrenTasks
      .stream()
      .filter(edge -> edge.getSrcIRVertex().getId().equals(irVertex.getId()))
      .filter(edge -> edge.getPropertyValue(AdditionalOutputTagProperty.class).isPresent())
      .map(edge ->
        Pair.of(edge.getPropertyValue(AdditionalOutputTagProperty.class).get(),
          dataTransferFactory.createWriter(taskId, edge.getDstIRVertex(), edge)))
      .forEach(pair -> {
        map.putIfAbsent(pair.left(), new ArrayList<>());
        map.get(pair.left()).add(pair.right());
      });

    return map;
  }

  private Map<String, List<OperatorVertex>> getInternalAdditionalOutputMap(
    final IRVertex irVertex,
    final DAG<IRVertex, RuntimeEdge<IRVertex>> irVertexDag) {
    // Add all intra-task additional tags to additional output map.
    final Map<String, List<OperatorVertex>> map = new HashMap<>();

    irVertexDag.getOutgoingEdgesOf(irVertex.getId())
      .stream()
      .filter(edge -> edge.getPropertyValue(AdditionalOutputTagProperty.class).isPresent())
      .map(edge ->
        Pair.of(edge.getPropertyValue(AdditionalOutputTagProperty.class).get(), (OperatorVertex) edge.getDst()))
      .forEach(pair -> {
        map.putIfAbsent(pair.left(), new ArrayList<>());
        map.get(pair.left()).add(pair.right());
      });

    return map;
  }

  private List<OperatorVertex> getInternalMainOutputs(final IRVertex irVertex,
                                                     final DAG<IRVertex, RuntimeEdge<IRVertex>> irVertexDag) {
    return irVertexDag.getOutgoingEdgesOf(irVertex.getId())
      .stream()
      .filter(edge -> !edge.getPropertyValue(AdditionalOutputTagProperty.class).isPresent())
      .map(edge -> (OperatorVertex) edge.getDst())
      .collect(Collectors.toList());
  }

  /**
   * Return inter-task OutputWriters, for single output or output associated with main tag.
   *
   * @param irVertex                source irVertex
   * @param outEdgesToChildrenTasks outgoing edges to child tasks
   * @param dataTransferFactory     dataTransferFactory
   * @return OutputWriters for main children tasks
   */
  private List<OutputWriter> getExternalMainOutputs(final IRVertex irVertex,
                                                   final List<StageEdge> outEdgesToChildrenTasks,
                                                   final DataTransferFactory dataTransferFactory) {
    return outEdgesToChildrenTasks
      .stream()
      .filter(edge -> edge.getSrcIRVertex().getId().equals(irVertex.getId()))
      .filter(edge -> !edge.getPropertyValue(AdditionalOutputTagProperty.class).isPresent())
      .map(outEdgeForThisVertex -> dataTransferFactory
        .createWriter(taskId, outEdgeForThisVertex.getDstIRVertex(), outEdgeForThisVertex))
      .collect(Collectors.toList());
  }


  private Optional<Readable> getSourceVertexReader(final IRVertex irVertex,
                                                   final Map<String, Readable> irVertexIdToReadable) {
    if (irVertex instanceof SourceVertex) {
      final Readable readable = irVertexIdToReadable.get(irVertex.getId());
      if (readable == null) {
        throw new IllegalStateException(irVertex.toString());
      }
      return Optional.of(readable);
    } else {
      return Optional.empty();
    }
  }

  private List<InputReader> getParentTaskReaders(final int taskIndex,
                                                 final List<StageEdge> inEdgesFromParentTasks,
                                                 final DataTransferFactory dataTransferFactory) {
    return inEdgesFromParentTasks
      .stream()
      .map(inEdgeForThisVertex -> dataTransferFactory
        .createReader(taskIndex, inEdgeForThisVertex.getSrcIRVertex(), inEdgeForThisVertex))
      .collect(Collectors.toList());
  }

  ////////////////////////////////////////////// Transform-specific helper methods

  private void prepareTransform(final VertexHarness vertexHarness) {
    final IRVertex irVertex = vertexHarness.getIRVertex();
    final Transform transform;
    if (irVertex instanceof OperatorVertex) {
      transform = ((OperatorVertex) irVertex).getTransform();
      transform.prepare(vertexHarness.getContext(), vertexHarness.getOutputCollector());
    }
  }

  private void closeTransform(final VertexHarness vertexHarness) {
    final IRVertex irVertex = vertexHarness.getIRVertex();
    final Transform transform;
    if (irVertex instanceof OperatorVertex) {
      transform = ((OperatorVertex) irVertex).getTransform();
      transform.close();
    }

    vertexHarness.getContext().getSerializedData().ifPresent(data ->
      persistentConnectionToMasterMap.getMessageSender(MessageEnvironment.RUNTIME_MASTER_MESSAGE_LISTENER_ID).send(
        ControlMessage.Message.newBuilder()
          .setId(RuntimeIdManager.generateMessageId())
          .setListenerId(MessageEnvironment.RUNTIME_MASTER_MESSAGE_LISTENER_ID)
          .setType(ControlMessage.MessageType.ExecutorDataCollected)
          .setDataCollected(ControlMessage.DataCollectMessage.newBuilder().setData(data).build())
          .build()));
  }

  ////////////////////////////////////////////// Misc

  public void setIRVertexPutOnHold(final IRVertex irVertex) {
    idOfVertexPutOnHold = irVertex.getId();
  }

  /**
   * Finalize the output write of this vertex.
   * As element-wise output write is done and the block is in memory,
   * flush the block into the designated data store and commit it.
   *
   * @param vertexHarness harness.
   */
  private void finalizeOutputWriters(final VertexHarness vertexHarness) {
    final List<Long> writtenBytesList = new ArrayList<>();

    // finalize OutputWriters for main children
    vertexHarness.getWritersToMainChildrenTasks().forEach(outputWriter -> {
      outputWriter.close();
      final Optional<Long> writtenBytes = outputWriter.getWrittenBytes();
      writtenBytes.ifPresent(writtenBytesList::add);
    });

    // finalize OutputWriters for additional tagged children
    vertexHarness.getWritersToAdditionalChildrenTasks().values().forEach(outputWriters -> {
      outputWriters.forEach(outputWriter -> {
        outputWriter.close();
        final Optional<Long> writtenBytes = outputWriter.getWrittenBytes();
        writtenBytes.ifPresent(writtenBytesList::add);
      });
    });

    long totalWrittenBytes = 0;
    for (final Long writtenBytes : writtenBytesList) {
      totalWrittenBytes += writtenBytes;
    }

    // TODO #236: Decouple metric collection and sending logic
    metricMessageSender.send("TaskMetric", taskId,
      "writtenBytes", SerializationUtils.serialize(totalWrittenBytes));
  }
}