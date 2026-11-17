package com.ayaanshaikh.offline;

import static org.junit.Assert.*;

import android.app.job.JobParameters;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SyncJobLifecycleTest {
    private ServiceController<SyncJob> controller;
    private SyncJob service;
    private ExecutorService worker;
    private final CountDownLatch release = new CountDownLatch(1);

    @Before public void setup() throws Exception {
        controller = Robolectric.buildService(SyncJob.class).create();
        service = controller.get();
        worker = ReflectionHelpers.getStaticField(SyncJob.class, "WORK");
        var entered = new CountDownLatch(1);
        worker.submit(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    @After public void cleanup() throws Exception {
        if (service != null) {
            for (var run : jobs().values()) work(run).cancel(true);
            jobs().clear();
        }
        release.countDown();
        if (worker != null) worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        if (controller != null) controller.destroy();
    }

    private JobParameters params(int id) {
        var params = Shadow.newInstanceOf(JobParameters.class);
        ReflectionHelpers.setField(params, "jobId", id);
        assertEquals(id, params.getJobId());
        return params;
    }

    private Map<Integer, Object> jobs() { return ReflectionHelpers.getField(service, "jobs"); }
    private Future<?> work(Object run) { return ReflectionHelpers.getField(run, "work"); }

    @Test public void aDistinctStopParcelCancelsTheImmediateWorker() {
        var start = params(1102);
        var stop = params(1102);
        assertNotSame(start, stop);
        assertTrue(service.onStartJob(start));
        var future = work(jobs().get(1102));
        assertTrue(service.onStopJob(stop));
        assertTrue("Stopped queued work must never execute", future.isCancelled());
        assertTrue(jobs().isEmpty());
    }

    @Test public void periodicStopsCancelWithoutRequestingAnImmediateRetry() {
        service.onStartJob(params(1101));
        var future = work(jobs().get(1101));
        assertFalse(service.onStopJob(params(1101)));
        assertTrue(future.isCancelled());
    }

    @Test public void stoppingOneIdKeepsTheOtherWorker() {
        service.onStartJob(params(1101));
        var periodic = work(jobs().get(1101));
        service.onStartJob(params(1102));
        var immediate = work(jobs().get(1102));
        service.onStopJob(params(1102));
        assertTrue(immediate.isCancelled());
        assertFalse(periodic.isCancelled());
    }

    @Test public void aLateCompletionCannotAcknowledgeAReplacementRun() throws Exception {
        service.onStartJob(params(1102));
        var old = jobs().get(1102);
        service.onStopJob(params(1102));
        service.onStartJob(params(1102));
        var replacement = jobs().get(1102);
        var complete = SyncJob.class.getDeclaredMethod("complete", old.getClass(), boolean.class, boolean.class);
        complete.setAccessible(true);
        complete.invoke(service, old, false, false);
        assertSame(replacement, jobs().get(1102));
        assertFalse(work(replacement).isCancelled());
        service.onStopJob(params(1102));
        assertTrue(work(replacement).isCancelled());
    }

    @Test public void aRepeatedStartReplacesAndCancelsItsOlderWorker() {
        service.onStartJob(params(1102));
        var old = work(jobs().get(1102));
        service.onStartJob(params(1102));
        var replacement = work(jobs().get(1102));
        assertTrue(old.isCancelled());
        assertFalse(replacement.isCancelled());
    }
}
