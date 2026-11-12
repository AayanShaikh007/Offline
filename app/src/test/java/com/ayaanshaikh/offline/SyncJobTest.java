package com.ayaanshaikh.offline;

import static org.junit.Assert.*;
import android.app.job.JobScheduler;
import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class SyncJobTest {
    private Context context;
    private OfflineStore store;
    private JobScheduler scheduler;
    @Before public void setup() throws Exception {
        var field = OfflineStore.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        context = RuntimeEnvironment.getApplication();
        store = OfflineStore.get(context);
        scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancelAll();
    }
    @After public void cleanup() {
        scheduler.cancelAll();
        store.close();
    }
    @Test public void emptyLibraryDoesNotScheduleWork() {
        SyncJob.request(context, true);
        SyncJob.onOpen(context);
        assertTrue(scheduler.getAllPendingJobs().isEmpty());
    }
    @Test public void offlineRequestsDeferAndRepeatedRequestsDoNotDuplicateJobs() {
        store.add(new PlaylistLink("youtube", "PL0123456789abc"));
        SyncJob.request(context, true);
        SyncJob.request(context, true);
        var jobs = scheduler.getAllPendingJobs();
        assertEquals(2, jobs.size());
        var periodic = jobs.stream().filter(j -> j.isPeriodic()).findFirst().orElseThrow();
        assertEquals(SyncJob.HOUR, periodic.getIntervalMillis());
        assertTrue(periodic.isPersisted());
        assertTrue(periodic.isRequireBatteryNotLow());
        assertTrue(periodic.isRequireStorageNotLow());
        assertTrue(context.getSharedPreferences("sync", 0).getBoolean("force", false));
        assertFalse(SyncJob.wifi(context));
    }
    @Test public void removingLastPlaylistCancelsDeferredAndPeriodicJobs() {
        var link = new PlaylistLink("youtube", "PL0123456789abc");
        store.add(link);
        SyncJob.request(context, true);
        store.remove(link);
        SyncJob.cancelIfEmpty(context);
        assertTrue(scheduler.getAllPendingJobs().isEmpty());
        assertFalse(context.getSharedPreferences("sync", 0).getBoolean("force", false));
    }
}
