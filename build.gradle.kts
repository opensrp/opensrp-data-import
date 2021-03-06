import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.5.0"
  kotlin("plugin.serialization") version "1.5.0"
  application
  id("com.github.johnrengelman.shadow") version "6.1.0"
}

group = "org.smartregister"
version = "3.1.0-SNAPSHOT"

repositories {
  mavenCentral()
  jcenter()
}

val mainVerticleName = "org.smartregister.dataimport.main.MainVerticle"

val watchForChange = "src/**/*"
val doOnChange = "${projectDir}/gradlew classes"
val launcherClassName =  "io.vertx.core.Launcher"

application {
  mainClassName =  "org.smartregister.dataimport.MainKt" //Replace with launcherClassName when you just want to run vertx MainVerticle
}

val vertxVersion = "4.0.2"
val junitJupiterVersion = "5.7.0"
val kotlinSerializationVersion = "1.2.0"
val log4jVersion = "2.14.1"
val cliktVersion = "3.1.0"
val openCsvVersion = "5.4"

dependencies {
  implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
  implementation("io.vertx:vertx-web-client")
  implementation("io.vertx:vertx-web")
  implementation("io.vertx:vertx-pg-client")
  implementation("io.vertx:vertx-mysql-client")
  implementation("io.vertx:vertx-auth-oauth2")
  implementation("io.vertx:vertx-lang-kotlin")
  implementation("io.vertx:vertx-lang-kotlin-coroutines")
  implementation("io.vertx:vertx-config")
  implementation("io.vertx:vertx-circuit-breaker")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinSerializationVersion")
  implementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
  implementation("com.github.ajalt.clikt:clikt:$cliktVersion")
  implementation("com.opencsv:opencsv:$openCsvVersion")

  implementation(kotlin("stdlib-jdk8"))
  testImplementation("io.vertx:vertx-junit5")
  testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions.jvmTarget = "11"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11))
  }
}

tasks.withType<ShadowJar> {
  archiveClassifier.set("fat")
  manifest {
    attributes(
      mapOf("Main-Verticle" to mainVerticleName)
    )
  }

  mergeServiceFiles()
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events = setOf(PASSED, SKIPPED, FAILED)
  }
}

tasks.withType<JavaExec> {
  args = listOf(
    "run",
    mainVerticleName,
    "--redeploy=$watchForChange",
    "--launcher-class=$launcherClassName",
    "--on-redeploy=$doOnChange",
    "-Dvertx.logger-delegate-factory-class-name=Log4j2LogDelegateFactory",
    "-Denvironment=dev"
  )
}
