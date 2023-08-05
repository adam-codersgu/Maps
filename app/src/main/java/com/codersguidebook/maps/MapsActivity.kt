package com.codersguidebook.maps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.codersguidebook.maps.databinding.ActivityMapsBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.random.Random

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val MINIMUM_RECOMMENDED_RADIUS = 100F
        const val GEOFENCE_KEY = "TreasureLocation"
    }

    private val geofenceList = arrayListOf<Geofence>()
    private var huntStarted = false
    private var treasureLocation: LatLng? = null
    private var treasureMarker: Marker? = null
    private lateinit var binding: ActivityMapsBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var lastLocation: Location
    private lateinit var mMap: GoogleMap

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            endTreasureHunt()
            Toast.makeText(this@MapsActivity, getString(R.string.treasure_found), Toast.LENGTH_LONG).show()
        }
    }

    // 3600000 ms equals one hour
    private val timer = object : CountDownTimer(3600000, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            binding.timer.text = getString(R.string.timer, millisUntilFinished / 1000)
        }

        override fun onFinish() {
            endTreasureHunt()
            binding.timer.text = getString(R.string.times_up)
        }
    }

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

        registerReceiver(broadcastReceiver, IntentFilter("GEOFENCE_ENTERED"))

        binding.treasureHuntButton.setOnClickListener {
            when {
                !this::lastLocation.isInitialized -> Toast.makeText(this, getString(R.string.location_error), Toast.LENGTH_LONG).show()
                huntStarted -> endTreasureHunt()
                else -> {
                    generateTreasureLocation()
                    binding.treasureHuntButton.text = getString(R.string.end_the_treasure_hunt)
                    binding.hintButton.visibility = View.VISIBLE
                    huntStarted = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
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

    @SuppressLint("MissingPermission")
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
                    timer.start()
                    showHint()
                }
                .addOnFailureListener(this) { e ->
                    Toast.makeText(this, getString(R.string.treasure_error, e.message), Toast.LENGTH_SHORT).show()
                }
        } catch (_: SecurityException) {}

        binding.hintButton.setOnClickListener {
            showHint()
        }
    }

    private fun createGeofencingRequest(): GeofencingRequest {
        return GeofencingRequest.Builder().apply {
            setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            addGeofences(geofenceList)
        }.build()
    }

    private fun createGeofencePendingIntent(): PendingIntent {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun endTreasureHunt() {
        geofencingClient.removeGeofences(createGeofencePendingIntent()).run {
            addOnSuccessListener {
                geofenceList.clear()
            }
            addOnFailureListener { }
        }
        if (treasureMarker == null) placeAddressMarkerOnMap(treasureLocation!!)
        binding.treasureHuntButton.text = getString(R.string.start_treasure_hunt)
        binding.hintButton.visibility = View.INVISIBLE
        huntStarted = false
        timer.cancel()
        binding.timer.text = getString(R.string.hunt_ended)
    }

    private fun removeTreasureMarker() {
        treasureMarker?.remove()
        treasureMarker = null
    }

    private fun placeAddressMarkerOnMap(location: LatLng) {
        val geocodeListener = object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                val address = addresses[0].getAddressLine(0) ?: getString(R.string.no_address)
                addTreasureMarker(location, address)
            }

            override fun onError(errorMessage: String?) {
                addTreasureMarker(location, errorMessage)
            }
        }

        Geocoder(this).getFromLocation(location.latitude, location.longitude, 1, geocodeListener)
    }

    private fun addTreasureMarker(location: LatLng, text: String?) {
        val markerOptions = MarkerOptions()
            .position(location)
            .title(text)
        treasureMarker = mMap.addMarker(markerOptions)
    }

    private fun showHint() {
        if (treasureLocation != null && this::lastLocation.isInitialized) {
            val latDir = if (treasureLocation!!.latitude > lastLocation.latitude) getString(R.string.north)
            else getString(R.string.south)
            val lonDir = if (treasureLocation!!.longitude > lastLocation.longitude) getString(R.string.east)
            else getString(R.string.west)
            Toast.makeText(this, getString(R.string.direction, latDir, lonDir), Toast.LENGTH_SHORT).show()
        }
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