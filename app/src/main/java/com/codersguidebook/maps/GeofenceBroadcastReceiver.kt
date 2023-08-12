package com.codersguidebook.maps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            Toast.makeText(context, context?.getString(R.string.geofence_error, geofencingEvent.errorCode.toString()), Toast.LENGTH_SHORT).show()
            return
        }

        // Get the transition type.
        val geofenceTransition = geofencingEvent.geofenceTransition
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) context?.sendBroadcast(Intent("GEOFENCE_ENTERED"))
    }
}
