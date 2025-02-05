/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.model

import com.vdurmont.semver4j.Semver

import java.util.EnumSet

/**
 * Details about the used source code scanner.
 */
data class ScannerDetails(
    /**
     * The name of the scanner.
     */
    val name: String,

    /**
     * The version of the scanner.
     */
    val version: String,

    /**
     * The configuration of the scanner, could be command line arguments for example.
     */
    val configuration: String
) {
    companion object {
        private val MAJOR_MINOR = EnumSet.of(Semver.VersionDiff.MAJOR, Semver.VersionDiff.MINOR)
    }

    /**
     * True if the [other] scanner has the same name and configuration, and the [Semver] version differs only in other
     * parts than [major][Semver.VersionDiff.MAJOR] and [minor][Semver.VersionDiff.MINOR]. For the comparison the
     * [loose][Semver.SemverType.LOOSE] Semver type is used for maximum compatibility with the versions returned from
     * the scanners.
     */
    fun isCompatible(other: ScannerDetails) =
        name.equals(other.name, true) && configuration == other.configuration &&
                Semver(version, Semver.SemverType.LOOSE).diff(other.version) !in MAJOR_MINOR
}
