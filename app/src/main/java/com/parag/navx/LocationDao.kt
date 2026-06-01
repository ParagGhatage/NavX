package com.parag.navx

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationEntity)

    @Query("SELECT * FROM locations ORDER BY timestamp ASC")
    suspend fun getAllLocations(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE routeId = :routeId ORDER BY timestamp ASC")
    suspend fun getLocationsByRoute(routeId: Int): List<LocationEntity>

    @Query("DELETE FROM locations")
    suspend fun deleteAll()

    @Query("DELETE FROM locations WHERE routeId = :routeId")
    suspend fun deleteByRoute(routeId: Int)
}