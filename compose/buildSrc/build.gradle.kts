import org.gradle.kotlin.dsl.`kotlin-dsl`

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
