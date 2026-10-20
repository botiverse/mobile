package org.jetbrains.kotlin.konan.target;

/**
 * Link-time stand-in for the Kotlin/Native target object {@code KonanTarget.WATCHOS_X86}, which Kotlin
 * removed (the target is no longer supported). It is NOT a KonanTarget and never equals one.
 *
 * <p>Why: the KMM resource generator plugin {@code com.tencent.kuiklybase.resource.generator}
 * 0.1.0-raft.5 (bytemain/KuiklyBase-components, a moko-resources fork) was compiled against
 * Kotlin 2.0/2.1 and compares every native target against {@code KonanTarget.WATCHOS_X86.INSTANCE} while
 * configuring {@code :shared}. With Kotlin 2.4 that class is gone and project configuration fails
 * with {@code NoClassDefFoundError: org/jetbrains/kotlin/konan/target/KonanTarget$WATCHOS_X86}. buildSrc
 * classes are on the parent class loader of every build script / plugin, so this stub satisfies the
 * {@code getstatic}; the identity comparison is then simply false, which is the correct answer.
 *
 * <p>TODO(cmp-latest): delete once resource-generator is republished against Kotlin 2.4+.
 */
public final class KonanTarget$WATCHOS_X86 {
    public static final KonanTarget$WATCHOS_X86 INSTANCE = new KonanTarget$WATCHOS_X86();

    private KonanTarget$WATCHOS_X86() {
    }
}
