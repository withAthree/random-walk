package com.randomwalk.app.geo

data class LatLon(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude out of range: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude out of range: $longitude"
        }
    }
}
