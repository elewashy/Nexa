package com.elewashy.nexa.core.di

import android.content.Context
import androidx.room.Room
import com.elewashy.nexa.core.data.persistence.NexaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Owns database infrastructure only. Feature modules provide their own DAOs
 * and repositories from [NexaDatabase].
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideNexaDatabase(@ApplicationContext context: Context): NexaDatabase =
        Room.databaseBuilder(context, NexaDatabase::class.java, "nexa.db")
            .addMigrations(
                NexaDatabase.MIGRATION_1_2,
                NexaDatabase.MIGRATION_2_3,
                NexaDatabase.MIGRATION_3_4,
                NexaDatabase.MIGRATION_4_5,
                NexaDatabase.MIGRATION_5_6,
                NexaDatabase.MIGRATION_6_7,
                NexaDatabase.MIGRATION_7_8,
            )
            .build()
}
