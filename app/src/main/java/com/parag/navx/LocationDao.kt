package com.parag.navx

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationDao {

    // Insert a new GPS coordinate into the table
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLocation(location: LocationEntity)

    // Retrieve all coordinates to draw on a map later
    @Query("SELECT * FROM tracked_locations ORDER BY timestamp ASC")
    fun getAllLocations(): List<LocationEntity>

    // Wipe the table clean when you start a new ride
    @Query("DELETE FROM tracked_locations")
    fun clearAllLocations()
}