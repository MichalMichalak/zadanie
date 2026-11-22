package local.exercise.coupons.web

/** Response body describing a failure; shared by every endpoint of the application. */
data class ErrorResponse(val code: String, val message: String)
