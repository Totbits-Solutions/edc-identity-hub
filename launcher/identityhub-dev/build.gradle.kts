/*
 *  Copyright (c) 2025 Contributors
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Development launcher with super-user bootstrap
 *
 */

plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    runtimeOnly(project(":dist:bom:identityhub-bom"))
    implementation(project(":spi:participant-context-spi"))
    implementation(project(":spi:did-spi"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("identity-hub-dev.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

edcBuild {
    publish.set(false)
}
