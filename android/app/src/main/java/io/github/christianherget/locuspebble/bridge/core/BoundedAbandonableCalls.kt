package io.github.christianherget.locuspebble.bridge.core

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs foreign synchronous calls without tying their lifetime to the calling coroutine.
 *
 * Permits bound submitted work, so once [maxWorkers] calls are stuck, new calls fail closed instead
 * of retaining a backlog. The executor queue only bridges the instant between releasing a permit
 * and its worker returning. Cancelling the waiter never joins a worker that may be blocked in
 * Binder, a content provider, PackageManager, or durable preference storage.
 */
internal class BoundedAbandonableCallExecutor(
    maxWorkers: Int,
    threadNamePrefix: String,
) : AutoCloseable {
    private val threadNumber = AtomicInteger()
    private val permits = Semaphore(maxWorkers.also { require(it > 0) })
    private val executor = ThreadPoolExecutor(
        maxWorkers,
        maxWorkers,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "$threadNamePrefix-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun execute(block: () -> Unit): Boolean {
        if (!permits.tryAcquire()) return false
        return try {
            executor.execute {
                try {
                    block()
                } finally {
                    permits.release()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            permits.release()
            false
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun <Result> run(block: () -> Result): Result = suspendCancellableCoroutine { waiter ->
        if (!permits.tryAcquire()) {
            val token = waiter.tryResumeWithException(
                RejectedExecutionException("All bounded foreign-call workers are busy"),
            )
            if (token != null) waiter.completeResume(token)
            return@suspendCancellableCoroutine
        }
        try {
            executor.execute {
                var permitReleased = false
                fun releasePermit() {
                    if (!permitReleased) {
                        permitReleased = true
                        permits.release()
                    }
                }

                if (!waiter.isActive) {
                    releasePermit()
                    return@execute
                }
                try {
                    val result = block()
                    releasePermit()
                    val token = waiter.tryResume(result) ?: return@execute
                    waiter.completeResume(token)
                } catch (error: Throwable) {
                    releasePermit()
                    val token = waiter.tryResumeWithException(error) ?: return@execute
                    waiter.completeResume(token)
                } finally {
                    releasePermit()
                }
            }
        } catch (_: RejectedExecutionException) {
            permits.release()
            val token = waiter.tryResumeWithException(
                RejectedExecutionException("Bounded foreign-call executor is closed"),
            )
            if (token != null) waiter.completeResume(token)
        }
    }

    override fun close() {
        // Interrupt cooperative calls, but never await workers: a Binder proxy may ignore interrupt.
        executor.shutdownNow()
    }

    private companion object {
        const val WORKER_KEEP_ALIVE_SECONDS = 30L
    }
}
