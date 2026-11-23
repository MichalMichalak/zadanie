package local.exercise.coupons.coupon.web

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.util.Locale
import kotlin.reflect.KClass

/**
 * A real ISO 3166-1 alpha-2 country code in lowercase.
 *
 * The set comes from the JDK, so validating a country costs no dependency. It holds the officially
 * assigned codes: `us` and `gb` pass, while `zz` (unknown) and `uk` (an alias, the ISO code is
 * `gb`) do not.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [IsoCountryValidator::class])
annotation class IsoCountry(
    val message: String = "country must be a valid ISO 3166-1 alpha-2 code (two lowercase letters)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class IsoCountryValidator : ConstraintValidator<IsoCountry, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
        value == null || value in ISO_COUNTRIES
}

/** Lowercase because the request contract is lowercase; [Locale.getISOCountries] returns uppercase. */
private val ISO_COUNTRIES: Set<String> =
    Locale.getISOCountries().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
