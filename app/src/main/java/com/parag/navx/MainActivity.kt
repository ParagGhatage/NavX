package com.parag.navx

import androidx.compose.material.icons.filled.Remove
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        database = AppDatabase.getDatabase(this)

        setContent {
            MapLibreScreen(database)
        }
    }
}

@Composable
fun MapLibreScreen(database: AppDatabase) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember { MapView(context) }

    // Telemetry state
    var displayedLat by remember { mutableStateOf<Double?>(null) }
    var displayedLng by remember { mutableStateOf<Double?>(null) }

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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
                            // 1. HARD LOCK TILT TO FALSE
                            isTiltGesturesEnabled = false
                        }

                        map.setStyle("https://demotiles.maplibre.org/style.json")
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
                    text = "GPS TELEMETRY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
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
                            text = displayedLat?.let { String.format("%.6f", it) } ?: "—",
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "LONGITUDE", fontSize = 10.sp, color = Color.Gray)
                        Text(
                            text = displayedLng?.let { String.format("%.6f", it) } ?: "—",
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

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

            // Using standard minus icon from extended package (assuming it's available, otherwise fallback)
            // Zoom Out Button
            MapControlFAB(icon = Icons.Default.Remove, contentDescription = "Zoom Out") {
                mapLibreMap?.cameraPosition?.let { currentCam ->
                    mapLibreMap?.animateCamera(CameraUpdateFactory.zoomTo(currentCam.zoom - 1.0), 300)
                }
            }

            // 2. THE PERSPECTIVE TOGGLE BUTTON HAS BEEN DELETED

            MapControlFAB(icon = Icons.Default.LocationOn, contentDescription = "Locate Me") {
                val latestLocation = database.locationDao().getAllLocations().lastOrNull()
                if (latestLocation != null && mapLibreMap != null) {
                    val currentLatLng = LatLng(latestLocation.latitude, latestLocation.longitude)

                    displayedLat = latestLocation.latitude
                    displayedLng = latestLocation.longitude

                    mapLibreMap?.clear()
                    mapLibreMap?.addMarker(MarkerOptions().position(currentLatLng).title("Current Location"))

                    val position = CameraPosition.Builder()
                        .target(currentLatLng)
                        .zoom(16.0)
                        // 3. HARD LOCK CAMERA TILT TO 0.0 (FLAT)
                        .tilt(0.0)
                        .build()

                    mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(position), 1500)
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