package com.pricecompare.price_comparison.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Tiny helper that builds the bounded, daemon-threaded executors used by the
 * price-comparison services and discovery providers to parallelize
 * independent retailer calls.
 *
 * <p>Retailer calls are blocking (HTTP/network I/O), so each owner runs a
 * FIXED-size pool &mdash; bounded concurrency, never an unbounded
 * create-a-thread-per-call strategy &mdash; with daemon workers. Daemon
 * threads keep the pool safe to construct directly from unit tests (the JVM is
 * never kept alive), while the bound caps concurrent network work regardless
 * of request volume. Executors are cheap to create: threads start lazily on
 * first use.</p>
 *
 * <p>This is a plain utility class, NOT a Spring component.</p>
 */
public final class ServiceExecutors {

    /** Hard ceiling on pool width: no caller can build an unbounded pool here. */
    private static final int MAX_THREADS = 8;

    private ServiceExecutors() {
    }

    /**
     * Returns a new fixed-size executor (daemon workers) suitable for the
     * handful of registered retailer providers. The number of tasks submitted
     * equals the number of providers (at most a handful), so this stays
     * strictly bounded.
     */
    public static ExecutorService newBoundedExecutor(String namePrefix) {
        int threads = Math.max(2, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors()));
        return newBoundedExecutor(namePrefix, threads);
    }

    /**
     * Same contract as {@link #newBoundedExecutor(String)} but with a
     * caller-chosen width, for owners that submit many independent blocking
     * calls (a discovery provider completing a batch of product pages) and
     * therefore want to name their own politeness level.
     *
     * <p>The bound is still hard: the pool never grows past {@code maxThreads}
     * and never past {@link #MAX_THREADS}, so a caller can size its concurrency
     * without ever risking an unbounded thread explosion. Threads stay daemon,
     * keeping the pool safe to build from unit tests.</p>
     */
    public static ExecutorService newBoundedExecutor(String namePrefix, int maxThreads) {
        int threads = Math.max(2, Math.min(MAX_THREADS, maxThreads));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-worker");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }
}