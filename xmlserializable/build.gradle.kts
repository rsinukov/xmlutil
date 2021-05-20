/*
 * Copyright (c) 2018.
 *
 * This file is part of XmlUtil.
 *
 * This file is licenced to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You should have received a copy of the license with the source distribution.
 * Alternatively, you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import net.devrieze.gradle.ext.configureDokka
import net.devrieze.gradle.ext.doPublish
import net.devrieze.gradle.ext.envAndroid
import net.devrieze.gradle.ext.envJvm
import org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka")
    idea
}

val xmlutil_version: String by project
val xmlutil_versiondesc: String by project

base {
    archivesBaseName = "xmlserializable"
    version = xmlutil_version
}

val serializationVersion: String by project

val kotlin_version: String by project

val jupiterVersion: String by project
val woodstoxVersion: String by project

val androidAttribute = Attribute.of("net.devrieze.android", Boolean::class.javaObjectType)
val environmentAttr = TARGET_JVM_ENVIRONMENT_ATTRIBUTE

val moduleName = "net.devrieze.xmlutil.xmlserializable"

val testTask = tasks.create("test") {
    group = "verification"
}
val cleanTestTask = tasks.create("cleanTest") {
    group = "verification"
}

kotlin {
    targets {

        jvm {
            attributes {
                attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, envJvm)
                attribute(androidAttribute, false)
            }
            compilations.all {
                compileKotlinTaskProvider.configure {
                    kotlinOptions {
                        jvmTarget = "1.8"
                    }
                }
                tasks.named<Test>("${target.name}Test") {
                    useJUnitPlatform()
                    testTask.dependsOn(this)
                }
                cleanTestTask.dependsOn(tasks.getByName("clean${target.name[0].toUpperCase()}${target.name.substring(1)}Test"))
                tasks.named<Jar>("jvmJar") {
                    manifest {
                        attributes("Automatic-Module-Name" to moduleName)
                    }
                }
            }
        }
        jvm("android") {
            attributes {
                attribute(androidAttribute, true)
                attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, envAndroid)
                attribute(KotlinPlatformType.attribute, KotlinPlatformType.androidJvm)
            }
            compilations.all {
                val isTest = name=="test"
                compileKotlinTaskProvider.configure {
                    kotlinOptions {
                        jvmTarget = if (isTest) "1.8" else "1.6"
                        logger.lifecycle("Setting task $compileKotlinTaskName to target ${jvmTarget}")
                    }
                }
                tasks.named<Test>("${target.name}Test") {
                    useJUnitPlatform()
                    testTask.dependsOn(this)
                }
                cleanTestTask.dependsOn(tasks.named("clean${target.name[0].toUpperCase()}${target.name.substring(1)}Test"))
            }
        }
        js(BOTH) {
            browser()
            compilations.all {
                kotlinOptions {
                    sourceMap = true
                    sourceMapEmbedSources = "always"
                    suppressWarnings = false
                    verbose = true
                    metaInfo = true
                    moduleKind = "umd"
                    main = "call"
                }
            }
        }
    }

    targets.all {
        mavenPublication {
            version = xmlutil_version
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core")) // Don't add a runtime dep here
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val javaShared by creating {
            dependsOn(commonMain)
        }

        val jvmMain by getting {
            dependsOn(javaShared)
            dependencies {
                implementation(kotlin("stdlib-jdk8", kotlin_version))
            }
        }

        val jvmTest by getting {
            dependencies {
                dependsOn(commonTest)
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter-api:$jupiterVersion")

                runtimeOnly("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
                runtimeOnly("com.fasterxml.woodstox:woodstox-core:$woodstoxVersion")
            }
        }

        val androidMain by getting {
            dependsOn(javaShared)

            dependencies {
                compileOnly("net.sf.kxml:kxml2:2.3.0")
            }
        }

        val androidTest by getting {
            dependencies {
                dependsOn(commonTest)

                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter-api:$jupiterVersion")

                runtimeOnly("org.junit.jupiter:junit-jupiter-engine:$jupiterVersion")
                runtimeOnly("net.sf.kxml:kxml2:2.3.0")
            }
        }

        val jsMain by getting {
            dependsOn(commonMain)
        }

        val jsTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test-js"))
            }
        }

    }
    sourceSets.all {
        languageSettings.apply {
            progressiveMode = true
            apiVersion="1.5"
            languageVersion="1.5"
            useExperimentalAnnotation("kotlin.RequiresOptIn")
        }
    }

}

doPublish()

configureDokka(myModuleVersion = xmlutil_version)

idea {
    module {
        name = "xmlutil-xmlserializable"
    }
}