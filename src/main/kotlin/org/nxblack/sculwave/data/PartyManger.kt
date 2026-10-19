package org.nxblack.echoWave.data

import java.util.concurrent.ConcurrentHashMap

object PartyManger {
    val parties = ConcurrentHashMap<String, Group>()
}