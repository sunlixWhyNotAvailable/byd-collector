package com.bydcollector.collector.ha

/**
 * The endpoint profile being edited or used by an HA channel.
 *
 * A profile is only an address pair. Credentials and the logical destination
 * remain shared by the channel.
 */
enum class HaEndpointProfile {
    PRIMARY,
    ALTERNATIVE
}
