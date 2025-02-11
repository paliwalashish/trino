/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.execution.executor.dedicated;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.ThreadSafe;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.inject.Inject;
import io.airlift.units.Duration;
import io.opentelemetry.api.trace.Tracer;
import io.trino.execution.SplitRunner;
import io.trino.execution.TaskId;
import io.trino.execution.TaskManagerConfig;
import io.trino.execution.executor.RunningSplitInfo;
import io.trino.execution.executor.TaskExecutor;
import io.trino.execution.executor.TaskHandle;
import io.trino.execution.executor.scheduler.FairScheduler;
import io.trino.spi.VersionEmbedder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class ThreadPerDriverTaskExecutor
        implements TaskExecutor
{
    private final FairScheduler scheduler;
    private final Tracer tracer;
    private final VersionEmbedder versionEmbedder;
    private final int maxDriversPerTask;
    private final ScheduledThreadPoolExecutor backgroundTasks = new ScheduledThreadPoolExecutor(2);

    @GuardedBy("this")
    private final Map<TaskId, TaskEntry> tasks = new HashMap<>();

    @GuardedBy("this")
    private boolean closed;

    @Inject
    public ThreadPerDriverTaskExecutor(TaskManagerConfig config, Tracer tracer, VersionEmbedder versionEmbedder)
    {
        this(
                tracer,
                versionEmbedder,
                new FairScheduler(config.getMaxWorkerThreads(), "SplitRunner-%d", Ticker.systemTicker()),
                config.getMaxDriversPerTask());
    }

    @VisibleForTesting
    public ThreadPerDriverTaskExecutor(Tracer tracer, VersionEmbedder versionEmbedder, FairScheduler scheduler, int maxDriversPerTask)
    {
        this.scheduler = scheduler;
        this.tracer = requireNonNull(tracer, "tracer is null");
        this.versionEmbedder = requireNonNull(versionEmbedder, "versionEmbedder is null");
        this.maxDriversPerTask = maxDriversPerTask;
    }

    @PostConstruct
    @Override
    public synchronized void start()
    {
        scheduler.start();
        backgroundTasks.scheduleWithFixedDelay(this::scheduleMoreLeafSplits, 0, 100, TimeUnit.MILLISECONDS);
        backgroundTasks.scheduleWithFixedDelay(this::adjustConcurrency, 0, 10, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    @Override
    public synchronized void stop()
    {
        closed = true;
        tasks.values().forEach(TaskEntry::destroy);
        backgroundTasks.shutdownNow();
        scheduler.close();
    }

    @Override
    public synchronized TaskHandle addTask(
            TaskId taskId,
            DoubleSupplier utilizationSupplier,
            int initialSplitConcurrency,
            Duration splitConcurrencyAdjustFrequency,
            OptionalInt maxDriversPerTask)
    {
        checkArgument(!closed, "Executor is already closed");
        TaskEntry task = new TaskEntry(
                taskId,
                scheduler,
                versionEmbedder,
                tracer,
                initialSplitConcurrency,
                maxDriversPerTask.orElse(this.maxDriversPerTask),
                utilizationSupplier);
        tasks.put(taskId, task);
        return task;
    }

    @Override
    public synchronized void removeTask(TaskHandle handle)
    {
        TaskEntry entry = (TaskEntry) handle;
        tasks.remove(entry.taskId());
        if (!entry.isDestroyed()) {
            entry.destroy();
        }
    }

    @Override
    public synchronized List<ListenableFuture<Void>> enqueueSplits(TaskHandle handle, boolean intermediate, List<? extends SplitRunner> splits)
    {
        checkArgument(!closed, "Executor is already closed");

        TaskEntry entry = (TaskEntry) handle;

        List<ListenableFuture<Void>> futures = new ArrayList<>();
        for (SplitRunner split : splits) {
            futures.add(entry.addSplit(split, intermediate));
        }

        return futures;
    }

    private synchronized void scheduleMoreLeafSplits()
    {
        for (TaskEntry task : tasks.values()) {
            task.scheduleMoreLeafSplits();
        }
    }

    private void adjustConcurrency()
    {
        for (TaskEntry task : tasks.values()) {
            task.updateConcurrency();
        }
    }

    @Override
    public Set<TaskId> getStuckSplitTaskIds(Duration processingDurationThreshold, Predicate<RunningSplitInfo> filter)
    {
        // TODO
        return ImmutableSet.of();
    }
}
