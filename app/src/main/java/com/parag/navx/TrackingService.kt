package com.parag.navx

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TrackingService : Service() {

    private val TAG = "NavX_Tracker"
    private val CHANNEL_ID = "tracking_channel"

    // Hardware and Database managers
    private lateinit var locationManager: LocationManager
    private lateinit var database: AppDatabase

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 1. We got a ping from the hardware
            Log.i(TAG, "HARDWARE GPS: Lat: ${location.latitude} | Lon: ${location.longitude}")

            // 2. Map it to our SQLite Entity
            val locationData = LocationEntity(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                // location.time returns the actual satellite atomic clock time in milliseconds
                timestamp = location.time
            )

            // 3. Save it permanently to the local database
            try {
                database.locationDao().insertLocation(locationData)
                Log.i(TAG, "SUCCESS: Coordinate saved to offline SQLite database.")
            } catch (e: Exception) {
                Log.e(TAG, "DATABASE ERROR: Failed to save coordinate: ${e.message}")
            }
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "GPS Provider Enabled by user")
        }

        override fun onProviderDisabled(provider: String) {
            Log.e(TAG, "GPS Provider Disabled by user. We cannot track.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        createNotificationChannel()

        // Initialize the Location hardware manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize the Room SQLite database
        database = AppDatabase.getDatabase(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service Started")

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NavX Active Tracking")
            .setContentText("Recording route to offline database...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        startLocationTracking()

        return START_STICKY
    }

    private fun startLocationTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Powering on GPS receiver...")
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // Poll every 5 seconds
                    0f,
                    locationListener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to GPS hardware: ${e.message}")
            }
        } else {
            Log.e(TAG, "Cannot start tracking. Location permission missing.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed. Powering down GPS.")
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(locationListener)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}