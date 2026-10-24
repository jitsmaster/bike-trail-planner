package com.biketrailplanner.data

import com.biketrailplanner.domain.LatLon

/** The planner only needs these two operations; the cache and the client both provide them. */
interface RouteService {
    suspend fun route(
        from: LatLon,
        to: LatLon,
        via: List<LatLon> = emptyList(),
        allowFallback: Boolean = false,
    ): RouteResult
    /**
     * Trace a shape against [baseUrl], the endpoint that served the route when there is one. D12:
     * [allowFallback] allows the public server to be tried as well, for a caller with no route - see
     * the implementation on `ValhallaClient`.
     */
    suspend fun trace(shape: String, baseUrl: String, allowFallback: Boolean = false): TraceResult
}
