package dev.armchallenge.cyberassistant.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.armchallenge.cyberassistant.data.ScanEventDao
import dev.armchallenge.cyberassistant.data.ThreatDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): ThreatDatabase =
        Room.databaseBuilder(context, ThreatDatabase::class.java, "threat-history.db").build()

    @Provides
    fun scanEventDao(database: ThreatDatabase): ScanEventDao = database.scanEventDao()
}
