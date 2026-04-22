// Copyright 2021 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package androidx.media3.build

import groovy.util.Node
import org.gradle.api.XmlProvider

// Workaround for https://github.com/gradle/gradle/issues/3170, adding
// <type>aar</type> in the POM to all dependencies that have AAR files.
fun addMissingAarTypeToXml(xml: XmlProvider) {
  // Dependencies that have JARs only (=don't contain an AAR file).
  val jarOnlyDependencies =
    setOf(
      // go/keep-sorted start
      "androidx.annotation:annotation",
      "androidx.collection:collection",
      "androidx.concurrent:concurrent-futures",
      "com.github.philburk:jsyn",
      "com.google.guava:guava",
      "com.google.truth:truth",
      "com.squareup.okhttp3:mockwebserver",
      "com.squareup.okhttp3:okhttp",
      "io.ktor:ktor-client-android",
      "io.ktor:ktor-client-core",
      "junit:junit",
      "org.chromium.net:cronet-api",
      "org.jetbrains.kotlin:kotlin-stdlib",
      "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
      "org.jetbrains.kotlinx:kotlinx-coroutines-android",
      "org.jetbrains.kotlinx:kotlinx-coroutines-core",
      "org.jetbrains.kotlinx:kotlinx-coroutines-guava",
      "org.jetbrains.kotlinx:kotlinx-coroutines-test",
      "org.mockito:mockito-core",
      "org.robolectric:robolectric",
      // go/keep-sorted end
    )
  // Dependencies that have AAR files.
  val aarDependencies =
    setOf(
      // go/keep-sorted start
      "androidx.annotation:annotation-experimental",
      "androidx.appcompat:appcompat",
      "androidx.compose.foundation:foundation",
      "androidx.compose.material3:material3",
      "androidx.core:core",
      "androidx.core:core-ktx",
      "androidx.exifinterface:exifinterface",
      "androidx.leanback:leanback",
      "androidx.lifecycle:lifecycle-service",
      "androidx.media:media",
      "androidx.mediarouter:mediarouter",
      "androidx.recyclerview:recyclerview",
      "androidx.test.ext:junit",
      "androidx.test.ext:truth",
      "androidx.test:core",
      "androidx.work:work-runtime",
      "com.airbnb.android:lottie",
      "com.google.ads.interactivemedia.v3:interactivemedia",
      "com.google.android.gms:play-services-cast-framework",
      "com.google.android.gms:play-services-cronet",
      "com.google.android.material:material",
      "com.google.testparameterinjector:test-parameter-injector",
      "io.antmedia:rtmp-client",
      // go/keep-sorted end
    )

  val rootNode = xml.asNode()
  val dependenciesNodes =
    (rootNode.children() as List<*>).filterIsInstance<Node>().filter {
      it.name().toString().endsWith("dependencies")
    }

  for (dependenciesNode in dependenciesNodes) {
    val dependencyNodes = (dependenciesNode.children() as List<*>).filterIsInstance<Node>()
    for (dependencyNode in dependencyNodes) {
      val depChildren = (dependencyNode.children() as List<*>).filterIsInstance<Node>()
      val groupIdNode = depChildren.find { it.name().toString().endsWith("groupId") }
      val artifactIdNode = depChildren.find { it.name().toString().endsWith("artifactId") }
      val groupId = groupIdNode?.children()?.firstOrNull()?.toString()
      val artifactId = artifactIdNode?.children()?.firstOrNull()?.toString()

      val dependencyName = "$groupId:$artifactId"
      val isProjectLibrary = groupId == "androidx.media3"

      val hasJar = jarOnlyDependencies.contains(dependencyName)
      val hasAar = isProjectLibrary || aarDependencies.contains(dependencyName)
      if (!hasJar && !hasAar) {
        // To look for what kind of dependency it is i.e. aar or jar type,
        // please expand the External Libraries in Project view in Android Studio
        // and search for your dependency inside Gradle Script dependencies.
        // .aar files have @aar suffix at the end of their name,
        // while .jar files have nothing.
        throw IllegalStateException(
          "$dependencyName is not on the JAR or AAR list in AarTypeWorkaround.kt"
        )
      }
      val hasTypeDeclaration = depChildren.any { it.name().toString().endsWith("type") }
      if (hasAar && !hasTypeDeclaration) {
        dependencyNode.appendNode("type", "aar")
      }
    }
  }
}
