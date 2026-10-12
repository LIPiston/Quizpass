plugins {
    kotlin("jvm") version "2.4.0"
}

group = "top.lipiston"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        setUrl("https://jitpack.io")
    }
}

dependencies {
    // MUST match the Kalmia build that actually loads this plugin at runtime. Kalmia and its
    // bundled Cason library change binary signatures between patch releases (1.0.28 declared
    // JSONArray.getList() as ArrayList, 1.0.32 declares List), so compiling against a different
    // version than the one that loads the jar fails at runtime with NoSuchMethodError.
    implementation("com.github.cao-awa:Kalmia:1.0.32")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}