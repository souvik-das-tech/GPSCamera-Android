// Live GPS position, from Google Play services' fused location provider (GPS, helped by Wi-Fi
// and the mobile network for a quick first fix), or plain GPS on phones without Play services.
package com.bbcl.gpscamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class GpsStatus { STARTING, SEARCHING, FIXED, PERMISSION_DENIED, DISABLED }

data class GpsState(val status: GpsStatus = GpsStatus.STARTING, val position: Location? = null) {
    val hasFix get() = status == GpsStatus.FIXED && position != null
}

class LocationTracker(context: Context) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private val handler = Handler(Looper.getMainLooper())
    private val fused = if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS) {
        LocationServices.getFusedLocationProviderClient(app)
    } else null
    private val manager = app.getSystemService(LocationManager::class.java)
    private var running = false

    /** A fix older than this is treated as lost. */
    private val lost = Runnable { _state.value = _state.value.copy(status = GpsStatus.SEARCHING) }

    private fun onFix(l: Location) {
        _state.value = GpsState(GpsStatus.FIXED, l)
        handler.removeCallbacks(lost)
        handler.postDelayed(lost, 30_000)
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onFix)
        }
    }
    private val listener = LocationListener(::onFix)

    /** Starts tracking, or reports why it can't. Safe to call again (e.g. on resume). */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            stop()
            _state.value = GpsState(GpsStatus.DISABLED)
            return
        }
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = GpsState(GpsStatus.PERMISSION_DENIED)
            return
        }
        if (running) return
        running = true
        if (!_state.value.hasFix) _state.value = GpsState(GpsStatus.SEARCHING)
        if (fused != null) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } else {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        }
    }

    fun stop() {
        if (!running) return
        running = false
        fused?.removeLocationUpdates(callback)
        manager.removeUpdates(listener)
        handler.removeCallbacks(lost)
    }
}
