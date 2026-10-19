package org.nxblack.sculwave
import java.net.http.HttpClient
import java.time.Duration

object Http {
    val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
}