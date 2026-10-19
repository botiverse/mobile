package build.raft.app

/**
 * Marks :core declarations that are public only so the in-repo Kuikly layer (:shared) can keep
 * using them after the module split. They are not a supported surface for app code: :core and
 * :shared opt in module-wide; anything else must opt in explicitly and own the coupling.
 */
@RequiresOptIn(
    message = "Internal :core API shared with :shared; not intended for app code.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class SlockCoreInternalApi
