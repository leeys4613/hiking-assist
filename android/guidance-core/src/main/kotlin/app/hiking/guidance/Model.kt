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
    /** ISO-8601 오프셋 포함(예: 2026-11-21T17:16+09:00). 있으면 하산 권고에 쓴다 */
    val sunset: String? = null,
    /** 검증을 통과한 산에서만 채운다(명세 §7, AC8). 없으면 재경로는 꺼지고 "코스를 벗어났어요"만 나온다 */
    val graph: Graph? = null,
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

@Serializable
data class Graph(val nodes: List<GNode>, val edges: List<GEdge>)

@Serializable
data class GNode(val id: Int, val lat: Double, val lon: Double, val ele: Double = 0.0, val type: String = "junction")

@Serializable
data class GEdge(
    val id: Int,
    val from: Int,
    val to: Int,
    /** [lat, lon, ele] — from 쪽에서 to 쪽 순서 */
    val geometry: List<List<Double>>,
    @SerialName("length_m") val lengthM: Double = 0.0,
    @SerialName("ascent_m") val ascentM: Double = 0.0,
    @SerialName("descent_m") val descentM: Double = 0.0,
    /** 출입금지/폐쇄. 경로 계산에서 반드시 제외한다(명세 §5.1) */
    val closed: Boolean = false,
)
