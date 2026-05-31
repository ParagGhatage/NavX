package com.parag.navx

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long
)