package com.parag.navx

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var database: AppDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var currentRouteId: Int? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()

        // 1. Keep the CPU alive even when screen is locked
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NavX::TrackingLock")
        wakeLock?.acquire()

        database = AppDatabase.getDatabase(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        startForegroundService()

        // Polling hardware every 5 seconds
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 10f, locationListener
            )
        } catch (e: SecurityException) { /* Handle missing permissions */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentRouteId = intent?.getIntExtra("routeId", -1)?.takeIf { it != -1 }
        return super.onStartCommand(intent, flags, startId)
    }

    private val locationListener = LocationListener { location ->
        // Ignore fixes worse than 20 meters accuracy
        if (location.accuracy > 20f) return@LocationListener

        serviceScope.launch {
            database.locationDao().insertLocation(
                LocationEntity(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    timestamp = System.currentTimeMillis(),
                    routeId = currentRouteId
                )
            )
        }
    }

    private fun startForegroundService() {
        val channelId = "navx_tracking_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "NavX GPS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NavX Recording")
            .setContentText("Keeping GPS active...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        wakeLock?.release() // Allow phone to sleep when we stop recording
    }

    override fun onBind(intent: Intent?): IBinder? = null
}