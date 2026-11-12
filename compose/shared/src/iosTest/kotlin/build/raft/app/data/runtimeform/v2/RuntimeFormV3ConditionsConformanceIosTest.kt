package build.raft.app.data.runtimeform.v2

import kotlin.test.Test

/**
 * The v3 condition conformance checks (RuntimeFormV3ConditionsConformanceTest, commonTest) on
 * Kotlin/Native: its regex engine is not the JVM's, so the portable pattern subset and the `$` → `\z` /
 * `.` → `[^\n\r  ]` substitutions must hold here too. The iOS lane runs `*IosTest` classes only.
 */
class RuntimeFormV3ConditionsConformanceIosTest {
    @Test fun everyCaseEvaluatesToItsExpectedValue() = runtimeFormV3CheckConformanceCases()

    @Test fun everyUnsupportedSchemaIsReportedUnsupported() = runtimeFormV3CheckConformanceUnsupported()

    @Test fun objectsCompareStructurallyWhateverTheKeyOrder() = runtimeFormV3CheckStructuralCompare()

    @Test fun patternsInsideThePortableSubsetAreSupported() = runtimeFormV3CheckPortablePatterns()

    @Test fun patternsOutsideThePortableSubsetMakeTheConditionUnsupported() = runtimeFormV3CheckNonPortablePatterns()

    @Test fun patternsMatchWithEcmaScriptDollarAndDot() = runtimeFormV3CheckEcmaScriptMatching()

    @Test fun aNonPortablePatternInAFormMakesTheWholeFormUnavailable() = runtimeFormV3CheckNonPortablePatternRefusesForm()
}
