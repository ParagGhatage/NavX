package com.parag.navx

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!locationGranted) {
            Toast.makeText(this, "NavX requires GPS permission to track routes.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        database = AppDatabase.getDatabase(this)

        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }

        setContent {
            MapLibreScreen(database)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop GPS tracking when app closes
        val intent = Intent(this, TrackingService::class.java)
        stopService(intent)
        android.util.Log.d("NavX", "App closed - GPS service stopped")
    }
}

@Composable
fun MapLibreScreen(database: AppDatabase) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember { MapView(context) }

    var liveLat by remember { mutableStateOf<Double?>(null) }
    var liveLng by remember { mutableStateOf<Double?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var currentRouteId by remember { mutableStateOf<Int?>(null) }
    var isLocationEnabled by remember { mutableStateOf(true) }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun getGPSLocation(callback: (latitude: Double, longitude: Double) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(context, "GPS is off. Enable Location in settings.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, context.mainExecutor) { location: Location? ->
                if (location != null) {
                    android.util.Log.d("NavX", "Got FRESH GPS location: ${location.latitude}, ${location.longitude}")
                    callback(location.latitude, location.longitude)
                } else {
                    Toast.makeText(context, "Cannot get GPS lock. Are you indoors?", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnown != null) {
                android.util.Log.d("NavX", "Got LAST KNOWN GPS location: ${lastKnown.latitude}, ${lastKnown.longitude}")
                callback(lastKnown.latitude, lastKnown.longitude)
            }
        }
    }

    fun centerMapOnLocation(latitude: Double, longitude: Double) {
        val currentLatLng = LatLng(latitude, longitude)
        val position = CameraPosition.Builder().target(currentLatLng).zoom(16.0).tilt(0.0).build()
        mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(position), 1500)

        // Add marker for current location
        mapLibreMap?.addMarker(MarkerOptions().position(currentLatLng).title("My Location"))
    }

    // Delete all routes, start GPS tracking on app open
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Delete all routes and locations
            database.locationDao().deleteAll()
            val allRoutes = database.routeDao().getAllRoutes()
            allRoutes.forEach { database.routeDao().deleteRoute(it.id) }

            // Create new route for this session
            val newRoute = RouteEntity(startTime = System.currentTimeMillis())
            val routeId = database.routeDao().insertRoute(newRoute).toInt()
            currentRouteId = routeId
            android.util.Log.d("NavX", "New route created: $routeId")
        }

        // Get initial GPS location immediately
        delay(500)
        getGPSLocation { lat, lng ->
            liveLat = lat
            liveLng = lng
            centerMapOnLocation(lat, lng)
            Toast.makeText(context, "GPS lock acquired", Toast.LENGTH_SHORT).show()
        }

        // Start GPS tracking service for continuous updates
        delay(1000)
        val intent = Intent(context, TrackingService::class.java)
        intent.putExtra("routeId", currentRouteId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Toast.makeText(context, "GPS tracking started", Toast.LENGTH_SHORT).show()
    }

    // Poll database for latest GPS coordinates (for continuous updates from service)
    LaunchedEffect(currentRouteId) {
        while (true) {
            delay(1000)
            if (currentRouteId != null) {
                val latestLocation = withContext(Dispatchers.IO) {
                    database.locationDao().getLocationsByRoute(currentRouteId ?: -1).lastOrNull()
                }

                if (latestLocation != null) {
                    android.util.Log.d("NavX", "Got location from SERVICE: ${latestLocation.latitude}, ${latestLocation.longitude}")
                    liveLat = latestLocation.latitude
                    liveLng = latestLocation.longitude
                }
            }
        }
    }

    // Location status monitoring
    LaunchedEffect(Unit) {
        while (true) {
            val wasEnabled = isLocationEnabled
            isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

            if (wasEnabled && !isLocationEnabled && isRecording) {
                isRecording = false
                val intent = Intent(context, TrackingService::class.java)
                context.stopService(intent)
                Toast.makeText(context, "GPS disabled. Recording stopped.", Toast.LENGTH_LONG).show()
            }

            delay(5000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        mapLibreMap = map
                        map.uiSettings.apply {
                            isZoomGesturesEnabled = true
                            isScrollGesturesEnabled = true
                            isRotateGesturesEnabled = true
                            isTiltGesturesEnabled = false
                        }

                        val defaultPosition = CameraPosition.Builder()
                            .target(LatLng(0.0, 0.0))
                            .zoom(2.0)
                            .build()
                        map.cameraPosition = defaultPosition

                        try {
                            map.setStyle("asset://offline-style.json") { style ->
                                android.util.Log.d("NavX", "Offline map style loaded")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("NavX", "Error loading offline style: ${e.message}")
                            // Fallback to online if offline fails
                            map.setStyle("https://demotiles.maplibre.org/style.json") { style ->
                                android.util.Log.d("NavX", "Fallback to online map")
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .fillMaxWidth(0.85f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRecording) "● RECORDING ROUTE" else "GPS TELEMETRY (IDLE)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "LATITUDE", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = liveLat?.let { String.format("%.6f", it) } ?: "—",
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "LONGITUDE", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = liveLng?.let { String.format("%.6f", it) } ?: "—",
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Toggle Button
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = {
                    if (!isRecording) {
                        // Mark as recording (service already running)
                        isRecording = true
                        Toast.makeText(context, "Route recording started", Toast.LENGTH_SHORT).show()
                    } else {
                        // Stop recording
                        val intent = Intent(context, TrackingService::class.java)
                        context.stopService(intent)
                        isRecording = false
                        Toast.makeText(context, "Recording stopped.", Toast.LENGTH_SHORT).show()

                        // Update route with end time and draw it
                        currentRouteId?.let { routeId ->
                            coroutineScope.launch(Dispatchers.IO) {
                                database.routeDao().getRouteById(routeId)?.let { route ->
                                    val updatedRoute = route.copy(endTime = System.currentTimeMillis(), isActive = false)
                                    database.routeDao().updateRoute(updatedRoute)

                                    // Draw the current route
                                    val locations = database.locationDao().getLocationsByRoute(routeId)
                                    if (locations.size >= 2) {
                                        withContext(Dispatchers.Main) {
                                            mapLibreMap?.style?.let { style ->
                                                val points = locations.map { Point.fromLngLat(it.longitude, it.latitude) }
                                                val lineString = LineString.fromLngLats(points)
                                                val feature = Feature.fromGeometry(lineString)

                                                var source = style.getSourceAs<GeoJsonSource>("current-route-source")
                                                if (source == null) {
                                                    source = GeoJsonSource("current-route-source")
                                                    style.addSource(source)
                                                }
                                                source.setGeoJson(feature)

                                                if (style.getLayer("current-route-layer") == null) {
                                                    val lineLayer = LineLayer("current-route-layer", "current-route-source")
                                                        .withProperties(
                                                            PropertyFactory.lineColor(android.graphics.Color.RED),
                                                            PropertyFactory.lineWidth(5f),
                                                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                                                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                                                        )
                                                    style.addLayer(lineLayer)
                                                }

                                                mapLibreMap?.addMarker(MarkerOptions().position(LatLng(locations.first().latitude, locations.first().longitude)).title("Start"))
                                                mapLibreMap?.addMarker(MarkerOptions().position(LatLng(locations.last().latitude, locations.last().longitude)).title("Finish"))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFFC62828) else Color(0xFF2E7D32)
                ),
                modifier = Modifier.size(width = 180.dp, height = 50.dp)
            ) {
                Text(
                    text = if (isRecording) "STOP" else "START",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Map Control Stack
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MapControlFAB(icon = Icons.Default.Add, contentDescription = "Zoom In") {
                mapLibreMap?.cameraPosition?.let { currentCam ->
                    mapLibreMap?.animateCamera(CameraUpdateFactory.zoomTo(currentCam.zoom + 1.0), 300)
                }
            }

            MapControlFAB(icon = Icons.Default.Remove, contentDescription = "Zoom Out") {
                mapLibreMap?.cameraPosition?.let { currentCam ->
                    mapLibreMap?.animateCamera(CameraUpdateFactory.zoomTo(currentCam.zoom - 1.0), 300)
                }
            }

            MapControlFAB(icon = Icons.Default.LocationOn, contentDescription = "Locate Me") {
                getGPSLocation { lat, lng ->
                    liveLat = lat
                    liveLng = lng
                    centerMapOnLocation(lat, lng)
                }
            }
        }
    }
}

@Composable
fun MapControlFAB(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(56.dp)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
