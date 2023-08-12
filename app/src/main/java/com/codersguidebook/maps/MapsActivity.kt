package com.codersguidebook.maps

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.codersguidebook.maps.databinding.ActivityMapsBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.random.Random

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    companion object {
        const val MINIMUM_RECOMMENDED_RADIUS = 100F
        const val GEOFENCE_KEY = "TreasureLocation"
    }

    private val geofenceList = arrayListOf<Geofence>()
    private var treasureLocation: LatLng? = null
    private var treasureMarker: Marker? = null
    private var huntStarted = false
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var lastLocation: Location
    private lateinit var binding: ActivityMapsBinding
    private var receivingLocationUpdates = false
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var isRotating = false
    private lateinit var sensorManager: SensorManager

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

        registerReceiver(broadcastReceiver, IntentFilter("GEOFENCE_ENTERED"), RECEIVER_NOT_EXPORTED)

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

        binding.hintButton.setOnClickListener {
            showHint()
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(
                this,
                magneticField,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!receivingLocationUpdates) createLocationRequest()
    }

    override fun onPause() {
        super.onPause()
        if (this::locationCallback.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
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
        } catch (_: SecurityException) { }
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

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        val locationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        val client = LocationServices.getSettingsClient(this)
        client.checkLocationSettings(locationSettingsRequest).apply {
            addOnSuccessListener {
                receivingLocationUpdates = true
                startLocationUpdates()
            }
            addOnFailureListener {
                if (it is ResolvableApiException) {
                    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                        if (result.resultCode == RESULT_OK) {
                            receivingLocationUpdates = true
                            startLocationUpdates()
                        }
                    }.launch(IntentSenderRequest.Builder(it.resolution).build())
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(p0: LocationResult) {
                    super.onLocationResult(p0)

                    p0.lastLocation?.let { location ->
                        lastLocation = location
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) { }
    }

    private fun updateOrientationAngles() {
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val degrees = (Math.toDegrees(orientationAngles[0].toDouble()))

        val newRotation = degrees.toFloat() * -1
        val rotationChange = newRotation - binding.compass.rotation

        binding.compass.animate().apply {
            isRotating = true
            rotationBy(rotationChange)
            duration = 500
            setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isRotating = false
                }
            })
        }.start()
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }

        if (!isRotating) updateOrientationAngles()
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }
}