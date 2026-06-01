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
}

@Composable
fun MapLibreScreen(database: AppDatabase) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope() // Scope for DB calls on button clicks

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember { MapView(context) }

    var liveLat by remember { mutableStateOf<Double?>(null) }
    var liveLng by remember { mutableStateOf<Double?>(null) }
    var isTrackingActive by remember { mutableStateOf(false) }

    // --- THE 1-SECOND POLLING LOOP (FIXED) ---
    LaunchedEffect(Unit) {
        while (true) {
            // Move the database work to the IO thread
            val latestLocation = withContext(Dispatchers.IO) {
                database.locationDao().getAllLocations().lastOrNull()
            }

            // Update UI state on the Main thread
            if (latestLocation != null) {
                liveLat = latestLocation.latitude
                liveLng = latestLocation.longitude
            }
            delay(1000)
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
                        map.setStyle("asset://offline-style.json")
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
                    text = if (isTrackingActive) "● RECORDING ROUTE" else "GPS TELEMETRY (IDLE)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTrackingActive) Color.Red else MaterialTheme.colorScheme.primary,
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

        // Action Buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.locationDao().deleteAll() // ← wipe old session
                    }
                    val intent = Intent(context, TrackingService::class.java)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    isTrackingActive = true
                    // Clear the map of old routes before a new recording
                    mapLibreMap?.clear()
                    Toast.makeText(context, "Recording 5-sec GPS intervals", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Text("Record")
            }

            Button(
                onClick = {
                    // 1. Kill the hardware service
                    val intent = Intent(context, TrackingService::class.java)
                    context.stopService(intent)
                    isTrackingActive = false
                    Toast.makeText(context, "Stopped. Plotting route...", Toast.LENGTH_SHORT).show()

                    // 2. Query SQLite and draw the Polyline
                    coroutineScope.launch(Dispatchers.IO) {
                        val allLocations = database.locationDao().getAllLocations()

                        if (allLocations.size >= 2) {
                            // Convert Room Entities to GeoJSON Points (MUST BE Longitude, Latitude)
                            val points = allLocations.map { Point.fromLngLat(it.longitude, it.latitude) }
                            val lineString = LineString.fromLngLats(points)
                            val feature = Feature.fromGeometry(lineString)

                            withContext(Dispatchers.Main) {
                                mapLibreMap?.style?.let { style ->

                                    // Inject the vector data
                                    var source = style.getSourceAs<GeoJsonSource>("route-source")
                                    if (source == null) {
                                        source = GeoJsonSource("route-source")
                                        style.addSource(source)
                                    }
                                    source.setGeoJson(feature)

                                    // Paint the vector data red
                                    if (style.getLayer("route-layer") == null) {
                                        val lineLayer = LineLayer("route-layer", "route-source")
                                            .withProperties(
                                                PropertyFactory.lineColor(android.graphics.Color.parseColor("#FF0000")),
                                                PropertyFactory.lineWidth(5f),
                                                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                                                PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
                                            )
                                        style.addLayer(lineLayer)
                                    }

                                    // Drop start and end pins
                                    mapLibreMap?.addMarker(MarkerOptions().position(LatLng(allLocations.first().latitude, allLocations.first().longitude)).title("Start"))
                                    mapLibreMap?.addMarker(MarkerOptions().position(LatLng(allLocations.last().latitude, allLocations.last().longitude)).title("Finish"))
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Not enough points to draw a route.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Text("Stop")
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
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

                    if (!isGpsEnabled) {
                        Toast.makeText(context, "GPS is off. Enable Location in settings.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Pinging satellites...", Toast.LENGTH_SHORT).show()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            locationManager.getCurrentLocation(
                                LocationManager.GPS_PROVIDER,
                                null,
                                context.mainExecutor
                            ) { location: Location? ->
                                if (location != null) {
                                    val currentLatLng = LatLng(location.latitude, location.longitude)
                                    val position = CameraPosition.Builder().target(currentLatLng).zoom(16.0).tilt(0.0).build()
                                    mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(position), 1500)
                                } else {
                                    Toast.makeText(context, "Cannot get lock. Are you indoors?", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            if (lastKnown != null) {
                                val currentLatLng = LatLng(lastKnown.latitude, lastKnown.longitude)
                                val position = CameraPosition.Builder().target(currentLatLng).zoom(16.0).tilt(0.0).build()
                                mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(position), 1500)
                            }
                        }
                    }
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