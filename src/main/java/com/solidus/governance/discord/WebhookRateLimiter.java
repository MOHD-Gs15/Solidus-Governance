package com.solidus.governance.discord;

import com.solidus.governance.SolidusGovernanceMod;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class WebhookRateLimiter {
    private static final long RATE_LIMIT_MS = 5000L;
    private static final int MAX_QUEUE_SIZE = 50;
    private final Queue<QueuedMessage> queue = new LinkedList<QueuedMessage>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Solidus-DiscordRateLimiter");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastSendTime = 0L;

    public WebhookRateLimiter() {
        this.scheduler.scheduleAtFixedRate(this::drainQueue, 5000L, 5000L, TimeUnit.MILLISECONDS);
        SolidusGovernanceMod.LOGGER.debug("WebhookRateLimiter initialized (5s rate limit, max 50 queued)");
    }

    public CompletableFuture<Boolean> enqueue(Supplier<CompletableFuture<Boolean>> sendTask) {
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>();
        QueuedMessage msg = new QueuedMessage(sendTask, future);
        Queue<QueuedMessage> queue = this.queue;
        synchronized (queue) {
            while (this.queue.size() >= 50) {
                QueuedMessage dropped = this.queue.poll();
                if (dropped == null) continue;
                dropped.future.complete(false);
                SolidusGovernanceMod.LOGGER.warn("WebhookRateLimiter: dropped oldest message (queue full)");
            }
            this.queue.add(msg);
        }
        return future;
    }

    private void drainQueue() {
        QueuedMessage msg;
        Queue<QueuedMessage> queue = this.queue;
        synchronized (queue) {
            msg = this.queue.poll();
        }
        if (msg == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - this.lastSendTime;
        if (elapsed < 5000L) {
            Queue<QueuedMessage> queue2 = this.queue;
            synchronized (queue2) {
                ((LinkedList)this.queue).addFirst(msg);
            }
            return;
        }
        this.lastSendTime = now;
        try {
            ((CompletableFuture)msg.sendTask.get().thenAccept(success -> msg.future.complete((Boolean)success))).exceptionally(ex -> {
                msg.future.completeExceptionally((Throwable)ex);
                return null;
            });
        }
        catch (Exception e) {
            SolidusGovernanceMod.LOGGER.error("WebhookRateLimiter: failed to send queued message", (Throwable)e);
            msg.future.complete(false);
        }
    }

    public void shutdown() {
        SolidusGovernanceMod.LOGGER.info("WebhookRateLimiter shutting down, flushing queue...");
        // GOV-04 fix (reliability round): the D-8 bound was PER MESSAGE
        // (10s each). With a full queue of 50 and a slow/hung endpoint, the
        // flush could stall SERVER_STOPPING for up to ~8 minutes. The flush
        // now has a TOTAL budget: per-message wait 5s, overall 30s; anything
        // not delivered in that window is dropped with a log line.
        final long perMessageTimeoutMs = 5_000L;
        final long totalBudgetMs = 30_000L;
        long deadline = System.currentTimeMillis() + totalBudgetMs;
        int dropped = 0;
        int flushed = 0;
        Queue<QueuedMessage> queue = this.queue;
        synchronized (queue) {
            QueuedMessage msg;
            while ((msg = this.queue.poll()) != null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    msg.future.complete(false);
                    dropped++;
                    continue;
                }
                try {
                    // D-8 fix (audit round 3): this used to be an UNBOUNDED join on
                    // the server thread (WebhookManager.shutdown runs during
                    // SERVER_STOPPING) - one hung webhook endpoint could hang the
                    // entire server stop. Bounded wait: drop the message instead.
                    Boolean result = msg.sendTask.get().get(Math.min(perMessageTimeoutMs, remaining), TimeUnit.MILLISECONDS);
                    msg.future.complete(result != null && result != false);
                    flushed++;
                }
                catch (Exception e) {
                    SolidusGovernanceMod.LOGGER.warn("WebhookRateLimiter: failed to flush message during shutdown", (Throwable)e);
                    msg.future.complete(false);
                    dropped++;
                }
            }
        }
        if (dropped > 0) {
            SolidusGovernanceMod.LOGGER.warn(
                "WebhookRateLimiter: dropped {} queued alert(s) during shutdown ({}s total budget exceeded or send failed); {} delivered.",
                dropped, totalBudgetMs / 1000L, flushed);
        }
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(10L, TimeUnit.SECONDS)) {
                this.scheduler.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            this.scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        SolidusGovernanceMod.LOGGER.info("WebhookRateLimiter shut down complete ({} delivered, {} dropped).", flushed, dropped);
    }

    public int getQueueSize() {
        Queue<QueuedMessage> queue = this.queue;
        synchronized (queue) {
            return this.queue.size();
        }
    }

    private record QueuedMessage(Supplier<CompletableFuture<Boolean>> sendTask, CompletableFuture<Boolean> future) {
    }
}
