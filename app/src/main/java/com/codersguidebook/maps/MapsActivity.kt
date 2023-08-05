package com.codersguidebook.maps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.codersguidebook.maps.databinding.ActivityMapsBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import kotlin.random.Random

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val MINIMUM_RECOMMENDED_RADIUS = 100F
        const val GEOFENCE_KEY = "TreasureLocation"
    }

    private val geofenceList = arrayListOf<Geofence>()
    private var treasureLocation: LatLng? = null
    private lateinit var binding: ActivityMapsBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var lastLocation: Location
    private lateinit var mMap: GoogleMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        if (!LocationPermissionHelper.hasLocationPermission(this)) LocationPermissionHelper.requestPermissions(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geofencingClient = LocationServices.getGeofencingClient(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!LocationPermissionHelper.hasLocationPermission(this)) {
            LocationPermissionHelper.requestPermissions(this)
        } else prepareMap()
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.uiSettings.isZoomControlsEnabled = true
        prepareMap()
    }

    @SuppressLint("MissingPermission")
    private fun prepareMap() {
        if (LocationPermissionHelper.hasLocationPermission(this)) {
            mMap.isMyLocationEnabled = true

            // Find the user's last known location
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.apply {
                    lastLocation = location
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12f))
                }
            }
        }
    }

    private fun generateTreasureLocation() {
        val choiceList = listOf(true, false)
        var choice = choiceList.random()
        val treasureLat = if (choice) lastLocation.latitude + Random.nextFloat()
        else lastLocation.latitude - Random.nextFloat()
        choice = choiceList.random()
        val treasureLong = if (choice) lastLocation.longitude + Random.nextFloat()
        else lastLocation.longitude - Random.nextFloat()
        treasureLocation = LatLng(treasureLat, treasureLong)

        removeTreasureMarker()
        geofenceList.add(Geofence.Builder()
            .setRequestId(GEOFENCE_KEY)
            .setCircularRegion(
                treasureLat,
                treasureLong,
                MINIMUM_RECOMMENDED_RADIUS
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        )

        try {
            geofencingClient.addGeofences(createGeofencingRequest(), createGeofencePendingIntent())
                .addOnSuccessListener(this) {
                    Toast.makeText(this, getString(R.string.begin_search), Toast.LENGTH_SHORT).show()
                    // TODO: Start the timer and display an initial hint
                }
                .addOnFailureListener(this) { e ->
                    Toast.makeText(this, getString(R.string.treasure_error, e.message), Toast.LENGTH_SHORT).show()
                }
        } catch (_: SecurityException) {}
    }

    object LocationPermissionHelper {
        private const val BACKGROUND_LOCATION_PERMISSION = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        private const val COARSE_LOCATION_PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION
        private const val FINE_LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

        fun hasLocationPermission(activity: Activity): Boolean {
            return ContextCompat.checkSelfPermission(activity, FINE_LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(activity, BACKGROUND_LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }

        fun requestPermissions(activity: Activity) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, FINE_LOCATION_PERMISSION)) {
                AlertDialog.Builder(activity).apply {
                    setMessage(activity.getString(R.string.permission_required))
                    setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
                        ActivityCompat.requestPermissions(activity, arrayOf(FINE_LOCATION_PERMISSION,
                            COARSE_LOCATION_PERMISSION, BACKGROUND_LOCATION_PERMISSION), 0)
                    }
                    show()
                }
            } else {
                ActivityCompat.requestPermissions(activity, arrayOf(FINE_LOCATION_PERMISSION,
                    COARSE_LOCATION_PERMISSION, BACKGROUND_LOCATION_PERMISSION), 0)
            }
        }
    }
}