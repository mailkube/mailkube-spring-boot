plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
    pmd
    id("com.diffplug.spotless") version "8.9.0"
}

group = "com.mailkube"
// Supplied as `-Pversion=X.Y.Z` by the release, and read from gradle.properties (a permanent
// `0.0.0` placeholder) otherwise. There is no version literal in the tree. See .rules/RELEASE.md.
version = providers.gradleProperty("version").getOrElse("0.0.0")

repositories {
    mavenCentral()
}

java {
    // A toolchain, not `sourceCompatibility`. The floor is inherited FROM THE SDK, not from Spring
    // Boot: Boot 3.4 still runs on Java 17, but the SDK's own toolchain is 25 with no `release`
    // override, so its class files cannot load on an earlier JVM and compiling this starter lower
    // would buy nothing. See .rules/SPRING_BOOT_INTEGRATION.md.
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
    withSourcesJar()
    withJavadocJar()
}

// The framework version is a matrix axis of its own, separate from the language version, per
// .rules/INTEGRATION_CONTRACT.md. `-PspringBootVersion=` selects one; the declared floor is the
// default so a plain `./gradlew check` tests what the POM actually promises.
//
// The floor is 3.5.0 and it is DERIVED, not chosen. Spring's `SimpleMetadataReaderFactory`
// reads a class's bytecode with a bundled ASM to evaluate `@AutoConfiguration`, and the ASM in
// spring-core 6.2.0 — which Boot 3.4.0 pins — cannot parse Java 25 class files (major 69). It fails
// with "ASM ClassReader failed to parse class file", so this starter's own auto-configuration is
// unreadable and every context fails to start. Verified directly: 6.2.0 fails, 6.2.5 and later
// parse it, and every 7.x parses it. Boot 3.5.0 pins 6.2.7, which is the first Boot release this
// starter can actually run under. See .rules/SPRING_BOOT_INTEGRATION.md.
val springBootVersion: String =
    providers.gradleProperty("springBootVersion").getOrElse("3.5.0")

// Spring Framework's version is NOT derived here. It is whatever the Boot BOM for the selected
// line pins, which is the only pairing Boot supports and the only one worth testing: Boot 3.x
// pins Framework 6.x and Boot 4.x pins 7.x, and mixing them is a combination no consumer has.
dependencies {
    // `api`, not `implementation`: MailSender and MailException are in this starter's own public
    // signatures, so a consumer must be able to name them without declaring the dependency.
    //
    // spring-context-support, NOT spring-context — verified, and the distinction is the whole
    // reason this starter can exist without jakarta.mail. `org.springframework.mail.MailSender`,
    // `SimpleMailMessage` and the MailException hierarchy all live here, and this artifact pulls
    // no mail provider of its own. `JavaMailSender` is also here, in the `.javamail` subpackage,
    // and IT is the one that needs jakarta.mail. See .rules/SPRING_BOOT_INTEGRATION.md.
    api(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    api("org.springframework:spring-context-support")
    api("com.mailkube:mailkube-java:1.2.0")

    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Optional by design, and this is the second install shape the contract asks CI to exercise:
    // a batch or worker application gets the mail sender without being forced onto a web stack.
    // `compileOnly` + `optional` is how a starter expresses that — it compiles the webhook
    // controller here and leaves the dependency out of the consumer's graph.
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework:spring-test")
    // spring-web is compileOnly for consumers and testImplementation here, so the webhook path is
    // exercised even though it is optional. AutoConfigurationTest then uses Boot's FilteredClassLoader
    // to prove the no-spring-web install shape from the same suite.
    testImplementation("org.springframework:spring-web")
    // MockMvc's standalone setup builds a mock servlet environment around a real DispatcherServlet,
    // so BOTH of these are test-only needs this starter never imposes on a consumer:
    //   spring-webmvc        — DispatcherServlet itself, which is not in spring-web
    //   jakarta.servlet-api  — spring-web declares it `provided`, expecting a servlet container
    // Without them the webhook tests fail on NoClassDefFoundError, which reads as a bug in the
    // controller rather than as a missing test dependency.
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The dependency-floor gate, which .rules/CI_GATES.md requires of any artifact WITH runtime
// dependencies — so unlike the SDK, this starter does not get to skip it. Gradle has no
// `--prefer-lowest`, so the floor is realized by forcing each declared lower bound: `-PdependencyFloor`
// resolves the oldest combination this POM claims to support and runs the suite against it.
//
// Without this, the `>= ` bounds in the published POM are fiction: every local build and every CI leg
// resolves something newer, and the first consumer on the floor is the one who finds out.
//
// Deliberately NO `failOnVersionConflict()`. It sounds like it belongs here and it does not: it
// fails on transitive conflicts the Boot BOM resolves perfectly well on its own (micrometer, for
// one), so the gate would go red over a disagreement no consumer ever sees. What this job proves is
// that the DECLARED lower bounds work — that is `force`, plus `-PspringBootVersion` selecting the
// oldest supported BOM.
//
// The SDK force below is deliberately EQUAL to the version declared above, so today it changes
// nothing. That is the point: it goes live the moment the declared dependency moves ahead of the
// floor this POM promises, and writing it now means the gate does not have to be remembered then.
if (providers.gradleProperty("dependencyFloor").isPresent) {
    configurations.configureEach {
        resolutionStrategy {
            force("com.mailkube:mailkube-java:1.2.0")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    // `-Werror` is the cheap half of what Error Prone would give, without hooking javac internals
    // or needing `--add-exports`.
    //
    // `-Xlint:all` minus two categories, each suppressed for a stated reason rather than by
    // dropping `-Werror`, which would silence everything else with them:
    //
    //   -this-escape: the JDK 21+ lint fires on constructor patterns that are idiomatic in classes
    //                 Spring instantiates reflectively.
    //   -processing:  `spring-boot-configuration-processor` claims only @ConfigurationProperties,
    //                 so javac warns "No processor claimed any of these annotations" and lists
    //                 every Spring annotation in the tree. That is inherent to a starter: the
    //                 annotations are read by Spring at runtime, not by a processor at compile
    //                 time, and the warning would fire on every correct build forever.
    options.compilerArgs.addAll(listOf("-Xlint:all,-this-escape,-processing", "-Werror"))
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    options.encoding = "UTF-8"
}

// The jar carries its version in the manifest, and reserves the JPMS module name WITHOUT shipping
// a module-info.java. That omission is deliberate and verified rather than assumed:
//
// Every Spring artifact on this classpath is an AUTOMATIC module (`jar --describe-module` reports
// "No module descriptor found. Derived automatic module." for spring-context, spring-boot-autoconfigure,
// spring-web and spring-jcl). A module-info here COMPILES cleanly against those, but `jlink` then
// refuses the whole image — "automatic module cannot be used with jlink: spring.jcl" — on a
// transitive dependency this starter does not control. Shipping one would advertise a modularity
// this artifact cannot deliver until Spring itself ships descriptors.
//
// `Automatic-Module-Name` reserves `com.mailkube.spring` so the SDK's own module name stays
// unsplit, which is the invariant that actually matters here: the SDK permanently claims its own
// module name, and a second jar declaring the same one fails module resolution outright. See .rules/SPRING_BOOT_INTEGRATION.md for the
// deviation record and the fallback.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "mailkube-spring-boot",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "com.mailkube.spring",
        )
    }
}

// ...and the same version as a resource, because the manifest route does not work on the module
// path: builtin loaders define no Package object for a package in a named module, so
// `getImplementationVersion()` is null there. Both are derived from the one Gradle version
// property, and neither is committed.
val versionResourceDir = layout.buildDirectory.dir("generated/version")

val generateVersionResource =
    tasks.register<WriteProperties>("generateVersionResource") {
        destinationFile =
            versionResourceDir.map {
                it.file("com/mailkube/spring/version.properties")
            }
        comment = "Generated from the Gradle version property. Do not edit, and do not commit."
        property("version", providers.provider { project.version.toString() })
    }

// `builtBy` on the source directory, not `dependsOn` on processResources: `sourcesJar` also reads
// this directory, and Gradle 9 fails the build when a task consumes another's output with no
// dependency edge. Declaring the producer once covers every consumer, present and future.
sourceSets.main { resources.srcDir(files(versionResourceDir).builtBy(generateVersionResource)) }

spotless {
    java {
        // palantir-java-format, NOT google-java-format: the latter needs
        // `--add-exports jdk.compiler/...` since JEP 396, which on a build means JVM arguments
        // that affect everything.
        palantirJavaFormat("2.97.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle { ktlint() }
}

pmd {
    toolVersion = "7.26.0"
    isConsoleOutput = true
    ruleSets = emptyList()
    ruleSetFiles = files("config/pmd/ruleset.xml")
}

// Tests are exempt from the documentation rule: a test method's name is its documentation.
tasks.named<Pmd>("pmdTest") { ruleSetFiles = files("config/pmd/ruleset-test.xml") }

jacoco { toolVersion = "0.8.15" }

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

// Not automatic: without this, `check` runs the report and never the gate.
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "mailkube-spring-boot"
            pom {
                name = "mailkube-spring-boot"
                description = "Spring Boot starter for mailkube."
                url = "https://github.com/mailkube/mailkube-spring-boot"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        name = "Mailtactic, Corp."
                        organization = "Mailtactic, Corp."
                    }
                }
                scm {
                    url = "https://github.com/mailkube/mailkube-spring-boot"
                    connection = "scm:git:https://github.com/mailkube/mailkube-spring-boot.git"
                    developerConnection =
                        "scm:git:ssh://git@github.com/mailkube/mailkube-spring-boot.git"
                }
            }
        }
    }

    // WITHOUT this block `./gradlew publish` has nowhere to upload to, so it does nothing and
    // EXITS 0: a release that looks green and ships no artifact. A publication says WHAT to
    // publish; a repository says WHERE. Both are required.
    //
    // The repository NAME binds the credentials: `PasswordCredentials` on a repository called
    // `central` reads the `centralUsername` / `centralPassword` project properties, which
    // release.yml supplies as ORG_GRADLE_PROJECT_* environment variables.
    repositories {
        maven {
            name = "central"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials(PasswordCredentials::class)
        }
    }
}

signing {
    // Maven Central requires signed artifacts and has NO OIDC trusted publishing, so this repo
    // needs a GPG key and a Portal token as secrets. The `com.mailkube` namespace is already verified
    // by the SDK and this artifact publishes under it, so no new namespace verification is needed.
    val signingKey = project.findProperty("signingKey") as String?
    val signingPassword = project.findProperty("signingPassword") as String?
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}
