package app.hiking.guidance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 코스 패키지(명세 §5.1). Phase 1 은 graph/weather/pace_model 을 읽지 않는다(ignoreUnknownKeys). */
@Serializable
data class CoursePackage(
    @SerialName("package_version") val packageVersion: Int,
    val mountain: Mountain,
    val course: CourseDef,
    val pois: List<Poi> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): CoursePackage = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class Mountain(val id: String, val name: String)

@Serializable
data class CourseDef(
    val id: String,
    val name: String,
    /** [lat, lon, ele] */
    val polyline: List<List<Double>>,
    @SerialName("summit_index") val summitIndex: Int,
    @SerialName("end_index") val endIndex: Int,
)

@Serializable
data class Poi(
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    @SerialName("course_dist_m") val courseDistM: Double,
)
