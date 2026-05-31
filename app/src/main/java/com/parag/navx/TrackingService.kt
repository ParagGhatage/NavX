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

    // The hardware manager and our listener
    private lateinit var locationManager: LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // WE HAVE DATA! Print the raw latitude, longitude, and altitude to the terminal.
            Log.i(TAG, "HARDWARE GPS: Lat: ${location.latitude} | Lon: ${location.longitude} | Alt: ${location.altitude}m")
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
        // Initialize the manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service Started")

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NavX Active Tracking")
            .setContentText("Hardware GPS is listening...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        // Turn on the GPS chip
        startLocationTracking()

        return START_STICKY
    }

    private fun startLocationTracking() {
        // Double-check permissions right before touching the hardware (Android requires this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Powering on GPS receiver...")
            try {
                // Request updates from the physical GPS chip.
                // Params: Provider, Min Time (ms), Min Distance (meters), Listener
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // 5 seconds
                    0f,    // 0 meters (update even if standing still for testing)
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
        // CRITICAL: Stop listening when service dies to save battery
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