package org.nxblack.sculwave.data

object Platform {

    val isFolia: Boolean by lazy {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: Exception) {
            false
        }
    }
}