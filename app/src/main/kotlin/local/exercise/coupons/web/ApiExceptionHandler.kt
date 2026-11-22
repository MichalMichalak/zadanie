package local.exercise.coupons.web

import local.exercise.coupons.coupon.*
import local.exercise.coupons.providers.CountryLookupException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Turns domain and framework failures into the HTTP contract. */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MerchantNotFoundException::class)
    fun handleMerchantNotFound(ex: CouponException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", ex)

    @ExceptionHandler(CustomerNotFoundException::class)
    fun handleCustomerNotFound(ex: CouponException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", ex)

    @ExceptionHandler(CouponNotFoundException::class)
    fun handleCouponNotFound(ex: CouponException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND", ex)

    @ExceptionHandler(CouponDepletedException::class)
    fun handleDepleted(ex: CouponException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.CONFLICT, "COUPON_DEPLETED", ex)

    @ExceptionHandler(CouponAlreadyUsedException::class)
    fun handleAlreadyUsed(ex: CouponException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.CONFLICT, "COUPON_ALREADY_USED", ex)

    @ExceptionHandler(CountryMismatchException::class)
    fun handleCountryMismatch(ex: CouponException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.FORBIDDEN, "COUNTRY_MISMATCH", ex)

    @ExceptionHandler(CountryLookupException::class)
    fun handleCountryLookupFailure(ex: CountryLookupException): ResponseEntity<ErrorResponse> {
        log.warn("Country lookup failed: {}", ex.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("COUNTRY_LOOKUP_FAILED", "Could not determine the customer's country"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalidBody(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
            .ifEmpty { "Request body is not valid" }
        return ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "Request body is missing or malformed"))

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(ex: MissingRequestHeaderException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("INVALID_REQUEST", "Required header '${ex.headerName}' is missing"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "'${ex.name}' has an invalid value"))

    private fun error(status: HttpStatus, code: String, ex: CouponException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(code, ex.message ?: status.reasonPhrase))
}
