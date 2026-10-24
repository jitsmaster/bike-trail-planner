package com.biketrailplanner.domain

/** Grade ordering: A is best, E is worst, UNGRADED and REJECTED are "not graded". */
object GradeRank {
    fun rank(grade: Grade): Int = when (grade) {
        Grade.A -> 0
        Grade.B -> 1
        Grade.C -> 2
        Grade.D -> 3
        Grade.E -> 4
        Grade.UNGRADED -> 5
        Grade.REJECTED -> 6
    }

    /** True when [grade] is better (safer) than [limit]. */
    fun betterThan(grade: Grade, limit: Grade): Boolean = rank(grade) < rank(limit)

    /** True when [grade] is worse than [limit]. */
    fun worseThan(grade: Grade, limit: Grade): Boolean = rank(grade) > rank(limit)
}
