plugins {
    kotlin("jvm") version "2.1.0"
    application
}

repositories { mavenCentral() }

dependencies { testImplementation(libs.junit.api) }

application { mainClass.set("playground.MainKt") }
