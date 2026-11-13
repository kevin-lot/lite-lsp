plugins {
    kotlin("jvm") version "2.1.0"
    application
}

repositories { mavenCentral() }

application { mainClass.set("playground.MainKt") }
