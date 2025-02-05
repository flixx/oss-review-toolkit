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

package com.here.ort.analyzer

import ch.frankel.slf4k.*

import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.Identifier
import com.here.ort.model.OrtIssue
import com.here.ort.model.Project
import com.here.ort.model.ProjectAnalyzerResult
import com.here.ort.model.VcsInfo
import com.here.ort.model.config.AnalyzerConfiguration
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.utils.collectMessagesAsString
import com.here.ort.utils.log
import com.here.ort.utils.normalizeVcsUrl
import com.here.ort.utils.showStackTrace

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.ServiceLoader

import kotlin.system.measureTimeMillis

typealias ManagedProjectFiles = Map<PackageManagerFactory, List<File>>
typealias ResolutionResult = MutableMap<File, ProjectAnalyzerResult>

/**
 * A class representing a package manager that handles software dependencies.
 *
 * @param managerName The package manager's name.
 * @param analyzerRoot The root directory of the analysis.
 * @param analyzerConfig The configuration of the analyzer to use.
 * @param repoConfig The configuration of the repository to use.
 */
abstract class PackageManager(
    val managerName: String,
    protected val analyzerRoot: File,
    protected val analyzerConfig: AnalyzerConfiguration,
    protected val repoConfig: RepositoryConfiguration
) {
    companion object {
        private val LOADER = ServiceLoader.load(PackageManagerFactory::class.java)!!

        /**
         * The list of all available package managers in the classpath.
         */
        val ALL by lazy { LOADER.iterator().asSequence().toList() }

        private val IGNORED_DIRECTORY_MATCHERS = listOf(
            // Ignore intermediate build system directories.
            ".gradle",
            "node_modules",
            // Ignore resources in a standard Maven / Gradle project layout.
            "src/main/resources",
            "src/test/resources",
            // Ignore virtual environments in Python.
            "lib/python2.*/dist-packages",
            "lib/python3.*/site-packages"
        ).map {
            FileSystems.getDefault().getPathMatcher("glob:**/$it")
        }

        /**
         * Recursively search for files managed by a package manager.
         *
         * @param directory The root directory to search for managed files.
         * @param packageManagers A list of package managers to use, defaults to [ALL].
         */
        fun findManagedFiles(directory: File, packageManagers: List<PackageManagerFactory> = ALL):
                ManagedProjectFiles {
            require(directory.isDirectory) {
                "The provided path is not a directory: ${directory.absolutePath}"
            }

            val result = mutableMapOf<PackageManagerFactory, MutableList<File>>()

            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (IGNORED_DIRECTORY_MATCHERS.any { it.matches(dir) }) {
                        log.info { "Not analyzing directory '$dir' as it is hard-coded to be ignored." }
                        return FileVisitResult.SKIP_SUBTREE
                    }

                    val dirAsFile = dir.toFile()
                    val filesInDir = dirAsFile.listFiles()

                    packageManagers.forEach { manager ->
                        // Create a list of lists of matching files per glob.
                        val matchesPerGlob = manager.matchersForDefinitionFiles.mapNotNull { glob ->
                            // Create a list of files in the current directory that match the current glob.
                            val filesMatchingGlob = filesInDir.filter { glob.matches(it.toPath()) }
                            filesMatchingGlob.takeIf { it.isNotEmpty() }
                        }

                        if (matchesPerGlob.isNotEmpty()) {
                            // Only consider all matches for the first glob that has matches. This is because globs are
                            // defined in order of priority, and multiple globs may just be alternative ways to detect
                            // the exact same project.
                            // That is, at the example of a PIP project, if a directory contains all three files
                            // "requirements-py2.txt", "requirements-py3.txt" and "setup.py", only consider the
                            // former two as they match the glob with the highest priority, but ignore "setup.py".
                            result.getOrPut(manager) { mutableListOf() } += matchesPerGlob.first()
                        }
                    }

                    return FileVisitResult.CONTINUE
                }
            })

            return result
        }

        /**
         * Enrich a package's VCS information with information deduced from the package's VCS URL or an optional
         * homepage URL.
         *
         * @param vcsFromPackage The [VcsInfo] of a [Package].
         * @param homepageUrl The URL to the homepage of a [Package], if any.
         */
        fun processPackageVcs(vcsFromPackage: VcsInfo, homepageUrl: String = ""): VcsInfo {
            val normalizedVcsFromPackage = vcsFromPackage.normalize()

            val normalizedUrl = normalizedVcsFromPackage.url.takeIf {
                it.isNotEmpty()
            } ?: normalizeVcsUrl(homepageUrl).takeIf {
                VersionControlSystem.forUrl(it) != null
            }.orEmpty()

            val vcsFromUrl = VersionControlSystem.splitUrl(normalizedUrl)
            return vcsFromUrl.merge(normalizedVcsFromPackage)
        }

        /**
         * Merge the [VcsInfo] read from the project with [VcsInfo] deduced from the VCS URL and from the working
         * directory.
         *
         * Get a project's VCS information from the working tree and optionally enrich it with VCS information from
         * another source (like meta-data) or a homepage URL.
         *
         * @param projectDir The working tree directory of the [Project].
         * @param vcsFromProject The project's [VcsInfo], if any.
         * @param homepageUrl The URL to the homepage of the [Project], if any.
         */
        fun processProjectVcs(
            projectDir: File, vcsFromProject: VcsInfo = VcsInfo.EMPTY,
            homepageUrl: String = ""
        ): VcsInfo {
            val vcsFromWorkingTree = VersionControlSystem.getPathInfo(projectDir).normalize()
            return vcsFromWorkingTree.merge(processPackageVcs(vcsFromProject, homepageUrl))
        }
    }

    /**
     * Optional mapping of found [definitionFiles] before dependency resolution.
     */
    open fun mapDefinitionFiles(definitionFiles: List<File>): List<File> = definitionFiles

    /**
     * Optional step to run before dependency resolution, like checking for prerequisites.
     */
    protected open fun beforeResolution(definitionFiles: List<File>) {}

    /**
     * Return a tree of resolved dependencies (not necessarily declared dependencies, in case conflicts were resolved)
     * for all [definitionFiles] which were found by searching the [analyzerRoot] directory. By convention, the
     * [definitionFiles] must be absolute.
     */
    open fun resolveDependencies(definitionFiles: List<File>): ResolutionResult {
        definitionFiles.forEach { definitionFile ->
            requireNotNull(definitionFile.relativeToOrNull(analyzerRoot)) {
                "'$definitionFile' must be an absolute path below '$analyzerRoot'."
            }
        }

        val result = mutableMapOf<File, ProjectAnalyzerResult>()

        beforeResolution(definitionFiles)

        definitionFiles.forEach { definitionFile ->
            log.info { "Resolving $managerName dependencies for '$definitionFile'..." }

            val elapsed = measureTimeMillis {
                @Suppress("TooGenericExceptionCaught")
                try {
                    resolveDependencies(definitionFile)?.let {
                        result[definitionFile] = it
                    }
                } catch (e: Exception) {
                    e.showStackTrace()

                    log.error { "Resolving dependencies for '${definitionFile.name}' failed with: ${e.message}" }

                    val relativePath = definitionFile.absoluteFile.relativeTo(analyzerRoot).invariantSeparatorsPath

                    val id = Identifier.EMPTY.copy(type = managerName, name = relativePath)
                    val errorProject = Project.EMPTY.copy(
                        id = id,
                        purl = id.toPurl(),
                        definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
                        vcsProcessed = processProjectVcs(definitionFile.parentFile)
                    )

                    val errors = listOf(OrtIssue(source = managerName, message = e.collectMessagesAsString()))

                    result[definitionFile] = ProjectAnalyzerResult(errorProject, sortedSetOf(), errors)
                }
            }

            log.info {
                "Resolving $managerName dependencies for '${definitionFile.name}' took ${elapsed / 1000}s."
            }
        }

        afterResolution(definitionFiles)

        return result
    }

    /**
     * Optional step to run after dependency resolution, like cleaning up temporary files.
     */
    protected open fun afterResolution(definitionFiles: List<File>) {}

    /**
     * Resolve dependencies for a single absolute [definitionFile] and return a [ProjectAnalyzerResult].
     */
    abstract fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult?
}
