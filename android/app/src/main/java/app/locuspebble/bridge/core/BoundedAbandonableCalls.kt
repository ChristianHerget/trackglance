package app.locuspebble.bridge.core

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs foreign synchronous calls without tying their lifetime to the calling coroutine.
 *
 * There is deliberately no work queue: once [maxWorkers] calls are stuck, new calls fail closed
 * instead of retaining an unbounded backlog. Cancelling the waiter never joins a worker that may be
 * blocked in Binder, a content provider, PackageManager, or durable preference storage.
 */
internal class BoundedAbandonableCallExecutor(
    maxWorkers: Int,
    threadNamePrefix: String,
) : AutoCloseable {
    private val threadNumber = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        0,
        maxWorkers.also { require(it > 0) },
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "$threadNamePrefix-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(block: () -> Unit): Boolean = try {
        executor.execute(block)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun <Result> run(block: () -> Result): Result = suspendCancellableCoroutine { waiter ->
        if (!execute {
                if (!waiter.isActive) return@execute
                try {
                    val result = block()
                    val token = waiter.tryResume(result) ?: return@execute
                    waiter.completeResume(token)
                } catch (error: Throwable) {
                    val token = waiter.tryResumeWithException(error) ?: return@execute
                    waiter.completeResume(token)
                }
            }
        ) {
            val token = waiter.tryResumeWithException(
                RejectedExecutionException("All bounded foreign-call workers are busy"),
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
