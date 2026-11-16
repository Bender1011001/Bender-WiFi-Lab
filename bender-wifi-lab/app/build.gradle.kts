import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.sir.benderwifi"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.sir.benderwifi"
        minSdk = 30
        targetSdk = 35
        versionCode = 15
        versionName = "0.5.2-device-count-preview2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // AAPT expands/renames .gz assets. Keep this verified archive opaque.
    androidResources { noCompress += "bundle" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/labAssets"))
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    packaging { jniLibs.useLegacyPackaging = true; jniLibs.keepDebugSymbols += "**/libbender_pixie.so" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
val provenanceSources = rootProject.fileTree(rootProject.projectDir) {
    include("app/src/**", "runtime/**", "engine/bender_engine/**", "tests/**", "research/*.spec.json")
    include("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/wrapper/**", "gradlew", "gradlew.bat", "runtime-bundle/manifest.json", "tools/package_runtime.py")
    include("third_party/pixiewps/src/**", "third_party/pixiewps/LICENSE.md", "third_party/pixiewps-build.json", "tools/build_pixie.py", "tools/pixie_stdin.c")
    include("third_party/hostap/COPYING", "third_party/hostap/src/**", "third_party/hostap/wpa_supplicant/**", "third_party/libnl-tiny/*.c", "third_party/libnl-tiny/include/**", "third_party/LGPL-2.1.txt", "third_party/wps-build.json", "tools/build_wps.py", "tools/wps_hooks.c")
    exclude("**/__pycache__/**", "**/*.pyc", "third_party/hostap/wpa_supplicant/src/**")
}
val stageLabAssets by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/labAssets"))
    from(rootProject.file("runtime")) { into("runtime"); exclude("__pycache__/**", "*.pyc") }
    from(rootProject.file("engine/bender_engine")) { into("runtime/bender_engine"); exclude("__pycache__/**", "*.pyc") }
    from(rootProject.file("runtime-bundle")) {
        into("runtime-image"); include("manifest.json", "rootfs.tar.gz")
        rename("rootfs.tar.gz", "rootfs.bundle")
    }
    from(rootProject.file("research")) { into("research"); include("*.spec.json") }
    from(rootProject.file("third_party/pixiewps")) { into("licenses/pixiewps"); include("LICENSE.md") }
    from(rootProject.file("third_party/pixiewps-build.json")) { into("licenses/pixiewps") }
    from(rootProject.file("third_party/hostap")) { into("licenses/hostap"); include("COPYING") }
    from(rootProject.file("third_party/wps-build.json")) { into("licenses/hostap") }
    from(rootProject.file("third_party/LGPL-2.1.txt")) { into("licenses/libnl-tiny") }
    inputs.files(provenanceSources)
    doFirst {
        rootProject.fileTree("runtime") { include("**/*.sh") }.forEach { script ->
            check(script.readBytes().none { it == 13.toByte() }) {
                "Android shell scripts require LF line endings: ${script.name}"
            }
        }
        val image = rootProject.file("runtime-bundle/rootfs.tar.gz")
        val manifest = rootProject.file("runtime-bundle/manifest.json")
        check(image.isFile && manifest.isFile) { "Build the bundled ARM64 runtime before assembling the app; see tools/BUILD-RUNTIME.md" }
        val metadata = groovy.json.JsonSlurper().parse(manifest) as Map<*, *>
        val digest = MessageDigest.getInstance("SHA-256")
        image.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        check(metadata["arch"] == "arm64" && (metadata["archive_bytes"] as Number).toLong() == image.length()
            && metadata["sha256"] == digest.digest().joinToString("") { "%02x".format(it) }) { "Bundled runtime integrity check failed" }
    }
    doLast {
        val hashes=provenanceSources.files.sortedBy {it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}.associate { source ->
            source.relativeTo(rootProject.projectDir).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") {"%02x".format(it)}
        }
        destinationDir.resolve("build-source-manifest.json").writeText(groovy.json.JsonOutput.toJson(mapOf("schema_version" to 1,"sha256" to hashes)))
    }
}
tasks.named("preBuild").configure { dependsOn(stageLabAssets) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
