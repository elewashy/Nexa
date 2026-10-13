package com.elewashy.nexa.feature.downloads.data

import android.Manifest
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import com.elewashy.nexa.core.files.DownloadDirectory
import com.elewashy.nexa.feature.downloads.data.engine.DownloadTask
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadStore
import com.elewashy.nexa.feature.downloads.data.persistence.toEntity
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: NexaDatabase
    private lateinit var store: DownloadStore
    private lateinit var repository: DownloadRepositoryImpl
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        database = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java).allowMainThreadQueries().build()
        store = DownloadStore(database, Dispatchers.IO)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = DownloadRepositoryImpl(context, scope, store)
        file = File(DownloadDirectory.ensureRoot(), "recovery.bin")
        file.delete()
        File(file.path + DownloadTask.PART_SUFFIX).delete()
    }

    @After fun tearDown() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
        file.delete()
        File(file.path + DownloadTask.PART_SUFFIX).delete()
    }

    private fun seed() = runBlocking {
        store.markLegacyImported()
        store.upsertEntity(DownloadItem(1, "https://example.com/file", file.name, file.path,
            totalBytes = 3, downloadedBytes = 3, status = DownloadStatus.COMPLETED).toEntity())
    }

    private fun command(action: suspend () -> Unit) {
        val job = scope.launch { action() }
        val deadline = System.currentTimeMillis() + 5000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Command did not finish", job.isCompleted)
        assertFalse("Command failed", job.isCancelled)
    }

    @Test fun `external deletion is detected without process restart`() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        seed()
        command { repository.refreshFileAvailability() }
        assertEquals(DownloadStatus.COMPLETED, repository.downloads.value.single().status)
        assertTrue(file.delete())
        command { repository.refreshFileAvailability() }
        val item = repository.downloads.value.single()
        assertEquals(DownloadStatus.FAILED, item.status)
        assertEquals(DownloadTask.ERROR_FILE_MISSING, item.errorMessage)
    }

    @Test fun `missing completed file retries from zero with no stale segment state`() {
        seed()
        command { repository.refreshFileAvailability() }
        assertEquals(DownloadTask.ERROR_FILE_MISSING, repository.downloads.value.single().errorMessage)
        command { repository.retry(1) }
        val item = repository.downloads.value.single()
        assertEquals(0L, item.downloadedBytes)
        assertEquals(-1L, item.totalBytes)
        assertTrue(runBlocking { store.segmentsFor(1) }.isEmpty())
        assertEquals(0L, runBlocking { store.loadAll() }.single().downloadedBytes)
    }

    @Test fun `revoked storage access is not mistaken for a deleted file`() {
        file.writeBytes(byteArrayOf(1, 2, 3))
        seed()
        command { repository.refreshFileAvailability() }
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        file.delete()
        command { repository.refreshFileAvailability() }
        assertEquals(DownloadStatus.COMPLETED, repository.downloads.value.single().status)
    }
}
