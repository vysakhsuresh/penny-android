package app.penny.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [EventRow::class, EntityRow::class, TransactionRow::class, TransactionFts::class],
    version = 1,
    exportSchema = true,
)
abstract class PennyDatabase : RoomDatabase() {
    abstract fun dao(): PennyDao

    companion object {
        const val NAME = "penny.db"

        /** Encrypted at rest with SQLCipher (SE-01); [passphrase] comes from [DatabaseKeyManager]. */
        fun open(context: Context, passphrase: ByteArray): PennyDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, PennyDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }

        fun inMemory(context: Context): PennyDatabase =
            Room.inMemoryDatabaseBuilder(context, PennyDatabase::class.java).build()
    }
}
