/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 *
 */

package ai.grakn.engine.tasks.manager.redisqueue;

import ai.grakn.engine.GraknEngineConfig;
import static ai.grakn.engine.GraknEngineConfig.REDIS_SERVER_PORT;
import static ai.grakn.engine.GraknEngineConfig.REDIS_SERVER_URL;
import ai.grakn.engine.TaskId;
import ai.grakn.engine.lock.LockProvider;
import ai.grakn.engine.tasks.connection.RedisCountStorage;
import ai.grakn.engine.tasks.manager.TaskConfiguration;
import ai.grakn.engine.tasks.manager.TaskManager;
import ai.grakn.engine.tasks.manager.TaskState;
import ai.grakn.engine.util.EngineID;
import com.codahale.metrics.MetricRegistry;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.util.Pool;

/**
 * Handle the lifecycle of tasks in Redis. Given a jedis pool
 * it starts a set of consumers that subscribe to the task queue.
 * Tasks can be added using the addTask method.
 *
 * @author pluraliseseverythings
 */
public class RedisTaskManager implements TaskManager {
    private final static Logger LOG = LoggerFactory.getLogger(RedisTaskManager.class);
    private final EngineID engineId;
    private final GraknEngineConfig config;
    private final RedisTaskStorage redisTaskStorage;
    private final Pool<Jedis> jedisPool;
    private final int threads;
    private final RedisTaskQueue redisTaskQueue;
    private final ExecutorService consumerExecutor;
    private final RedissonClient redissonClient;


    public RedisTaskManager(EngineID engineId, GraknEngineConfig graknEngineConfig, RedisCountStorage redisCountStorage, MetricRegistry metricsRegistry) {
        // TODO hacky way, the pool should be created in the main server class and passed here
        this(engineId, graknEngineConfig, redisCountStorage.getJedisPool(), 2, metricsRegistry);
    }

    public RedisTaskManager(EngineID engineId, GraknEngineConfig config, Pool<Jedis> jedisPool,
            int threads, MetricRegistry metricRegistry) {
        this.engineId = engineId;
        this.config = config;
        this.redisTaskStorage = RedisTaskStorage.create(jedisPool);
        this.jedisPool = jedisPool;
        this.threads = threads;
        this.redisTaskQueue = new RedisTaskQueue(jedisPool, metricRegistry);
        this.consumerExecutor = Executors.newFixedThreadPool(threads);
        Config rConfig = new Config();
        rConfig.useSingleServer().setAddress(config.getProperty(REDIS_SERVER_URL) + config.tryProperty(REDIS_SERVER_PORT).orElse("6379" ));
        this.redissonClient = Redisson.create(rConfig);
    }

    @Override
    public void close() {
        this.consumerExecutor.shutdown();
        try {
            this.redisTaskQueue.close();
            this.consumerExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.error("Termination not interrupted", e);
        } catch (IOException e) {
            LOG.error("Termination not completed", e);
        }
        LOG.debug("TaskManager closed");
    }

    @Override
    public void start() {
        IntStream.rangeClosed(1, threads).forEach(
                (int ignored) ->
                        // TODO refactor the interfaces so that we don't have to pass the manager around
                        redisTaskQueue.subscribe(this, consumerExecutor, engineId, config, jedisPool));
        // TODO: get rid of this singleton
        LockProvider.instantiate((lockName, existingLock) -> {
            if(existingLock != null){
                return existingLock;
            }
            // TODO: this version is reentrant, might not be what we want
            return redissonClient.getLock(lockName);
        });
        LOG.debug("Redis task manager started");
    }

    @Override
    public void stopTask(TaskId id) {
        // TODO Just a prototype, make sure this work in all scenarios
        TaskState task = redisTaskStorage.getState(id);
        if (task == null) {
            // TODO make sure other parts of the code can handle a partially defined task state
            // TODO also fix the Java representation of a task state
            task = TaskState.of(id);
        }
        try {
            task.markStopped();
            redisTaskStorage.updateState(task);
        } catch (Exception e) {
            LOG.error("Unexpected error while stopping {}", id);
            throw e;
        }
    }

    @Override
    public RedisTaskStorage storage() {
        return redisTaskStorage;
    }

    @Override
    public void addTask(TaskState taskState, TaskConfiguration configuration){
        Task task = Task.builder()
                .setId(taskState.getId().getValue())
                .setTaskConfiguration(configuration)
                .setTaskState(taskState).build();
        redisTaskQueue.putJob(task);
    }

    public RedisTaskQueue getQueue() {
        return redisTaskQueue;
    }
}
