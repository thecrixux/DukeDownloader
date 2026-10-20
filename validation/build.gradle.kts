plugins { kotlin("jvm") version "1.9.22" }
kotlin { jvmToolchain(17) }
sourceSets {
    main { kotlin.srcDir("../app/src/main/java/com/tottodrillo/data/download"); kotlin.srcDir("../app/src/main/java/com/tottodrillo/data/covers") }
    test { kotlin.srcDir("../app/src/test/java/com/tottodrillo/data/download"); kotlin.srcDir("../app/src/test/java/com/tottodrillo/data/covers") }
}
dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test { testLogging { events("passed", "failed", "skipped") } }
