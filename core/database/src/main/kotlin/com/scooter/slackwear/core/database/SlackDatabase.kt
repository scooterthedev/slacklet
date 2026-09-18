package com.scooter.slackwear.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.scooter.slackwear.core.database.dao.ActivityDao
import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.CustomEmojiDao
import com.scooter.slackwear.core.database.dao.MessageDao
import com.scooter.slackwear.core.database.dao.UsageDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.ActivityEntity
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.database.entity.CustomEmojiEntity
import com.scooter.slackwear.core.database.entity.MessageEntity
import com.scooter.slackwear.core.database.entity.UsageEntity
import com.scooter.slackwear.core.database.entity.UsageKind
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.UnreadState

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        UserEntity::class,
        UsageEntity::class,
        CustomEmojiEntity::class,
        ActivityEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(SlackTypeConverters::class)
abstract class SlackDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun usageDao(): UsageDao
    abstract fun customEmojiDao(): CustomEmojiDao
    abstract fun activityDao(): ActivityDao

    companion object {
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity ADD COLUMN entryType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE activity ADD COLUMN messageTs TEXT")
                db.execSQL("ALTER TABLE activity ADD COLUMN threadTs TEXT")
                db.execSQL("ALTER TABLE activity ADD COLUMN entryKey TEXT")
                db.execSQL("ALTER TABLE activity ADD COLUMN unreadCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun create(context: Context): SlackDatabase =
            Room.databaseBuilder(context, SlackDatabase::class.java, "slack.db")
                .addMigrations(MIGRATION_5_6)
                .build()
    }
}

class SlackTypeConverters {

    @TypeConverter
    fun toConversationKind(value: String): ConversationKind = ConversationKind.valueOf(value)

    @TypeConverter
    fun fromConversationKind(value: ConversationKind): String = value.name

    @TypeConverter
    fun toConfidence(value: String): UnreadState.Confidence = UnreadState.Confidence.valueOf(value)

    @TypeConverter
    fun fromConfidence(value: UnreadState.Confidence): String = value.name

    @TypeConverter
    fun toUsageKind(value: String): UsageKind = UsageKind.valueOf(value)

    @TypeConverter
    fun fromUsageKind(value: UsageKind): String = value.name

    @TypeConverter
    fun toDeliveryState(value: String): DeliveryState = DeliveryState.valueOf(value)

    @TypeConverter
    fun fromDeliveryState(value: DeliveryState): String = value.name
}
