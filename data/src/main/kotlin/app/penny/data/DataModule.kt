package app.penny.data

import android.content.Context
import app.penny.core.event.EventStore
import app.penny.data.crypto.DatabaseKeyManager
import app.penny.data.db.PennyDao
import app.penny.data.db.PennyDatabase
import app.penny.packs.PackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): PennyDatabase =
        PennyDatabase.open(context, DatabaseKeyManager(context).passphrase())

    @Provides fun dao(db: PennyDatabase): PennyDao = db.dao()

    @Provides @Singleton
    fun roomEventStore(db: PennyDatabase): RoomEventStore = RoomEventStore(db)

    @Provides fun eventStore(store: RoomEventStore): EventStore = store

    @Provides @Singleton
    fun packs(@ApplicationContext context: Context): PackRepository = PackRepository.fromAssets(context.assets)
}
