import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.datasource.ktor"

  publishing { singleVariant("release") { withSourcesJar() } }
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
}

dependencies {
  api(project(modulePrefix + "lib-common"))
  api(project(modulePrefix + "lib-datasource"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(project(modulePrefix + "test-utils"))
  androidTestImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.ktor.client.okhttp)
  api(libs.ktor.client.core)
}

extra["releaseArtifactId"] = "media3-datasource-ktor"
extra["releaseName"] = "Media3 Ktor DataSource module"

apply(from = "../../publish.gradle")
