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

package com.here.ort.model.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Resolutions for issues with a repository.
 */
data class Resolutions(
    /**
     * Resolutions for issues with the analysis or scan of the projects in this repository and their dependencies.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val errors: List<ErrorResolution> = emptyList(),

    /**
     * Resolutions for license policy violations.
     */
    @JsonAlias("evaluator_errors")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val ruleViolations: List<RuleViolationResolution> = emptyList()
) {
    /**
     * Merge this [Resolutions] with [other] [Resolutions]. Duplicates are removed.
     */
    fun merge(other: Resolutions) =
        Resolutions(
            errors = (errors + other.errors).distinct(),
            ruleViolations = (ruleViolations + other.ruleViolations).distinct()
        )
}
