package com.thelightphone.sdk

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

fun <T : RoomDatabase> SealedLightContext.buildDatabase(
    dbClass: Class<T>,
    dbName: String?,
    vararg migrations: Migration,
): T {
    return Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName)
        .addMigrations(*migrations)
        .fallbackToDestructiveMigration()
        .build()
}