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
    /** 서버가 학습한 개인 페이스(명세 §8). 없으면 순수 Tobler */
    @SerialName("pace_model") val paceModel: PaceModel? = null,
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

/** 개인 페이스 모델: 경사 bin 별 (실제 시간 / Tobler 시간) 계수. server/app/pace.py 와 같은 bin 규칙. */
@Serializable
data class PaceModel(
    val type: String = "tobler_personal",
    val factor: Double = 1.0,
    @SerialName("grade_bins") val gradeBins: GradeBins? = null,
    /** 이 코스에서 정상까지 걸린 시간의 중앙값(초). 정상 안내의 "평소보다 N분 빨라요"에 쓴다 */
    @SerialName("typical_summit_s") val typicalSummitS: Double? = null,
) {
    /** [grade]: 상승/수평거리(오르막 +). 이 경사에 곱할 계수 */
    fun factorFor(grade: Double): Double = gradeBins?.let { it.factors[it.binOf(grade)] } ?: factor
}

@Serializable
data class GradeBins(val edges: List<Double>, val factors: List<Double>) {
    /** Python bisect_right(edges, grade) 와 같다: grade 이하인 경계의 수 */
    fun binOf(grade: Double): Int = edges.count { it <= grade }
}
