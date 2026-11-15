package app.penny.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [EventRow::class, EntityRow::class, TransactionRow::class, TransactionFts::class, KvRow::class],
    version = 2,
    exportSchema = true,
)
abstract class PennyDatabase : RoomDatabase() {
    abstract fun dao(): PennyDao

    companion object {
        const val NAME = "penny.db"

        /** v2: Lamport clock and remote flag on events (sync), kv table for device-local state. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN lamport INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN remote INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE events SET lamport = seq")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_profileId_seq ON events (profileId, seq)")
                db.execSQL("CREATE TABLE IF NOT EXISTS kv (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        /** Encrypted at rest with SQLCipher (SE-01); [passphrase] comes from [DatabaseKeyManager]. */
        fun open(context: Context, passphrase: ByteArray, name: String = NAME): PennyDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, PennyDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        fun inMemory(context: Context): PennyDatabase =
            Room.inMemoryDatabaseBuilder(context, PennyDatabase::class.java).build()
    }
}
