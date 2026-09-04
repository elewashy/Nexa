package com.elewashy.nexa.feature.browser.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchHistoryRepositoryTest {
    private lateinit var database: NexaDatabase
    private lateinit var repository: SearchHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SearchHistoryRepositoryImpl(database.searchHistoryDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `only explicit normalized queries are stored and repeated queries are deduplicated`() = runTest {
        repository.record("  material   icons ")
        repository.record("weather")
        repository.record("material icons")

        assertEquals(listOf("material icons", "weather"), repository.recent())
        assertEquals(listOf("material icons"), repository.matching("material"))
    }

    @Test
    fun `blank queries are ignored and LIKE metacharacters are escaped`() = runTest {
        repository.record("   ")
        repository.record("100% coverage")
        repository.record("100 percent")

        assertEquals(listOf("100% coverage"), repository.matching("%"))
    }
}
