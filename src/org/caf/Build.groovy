package org.caf

// Creates coverage reports via kcov and the Cobertura plugin.
def coverageReport(config, jobName, buildId) {
    def settings = config['coverage']
    def testBinary = settings['binary']
    def excludePaths = settings['relativeExcludePaths'].collect { "$WORKSPACE/sources/$it" } + [
        "$WORKSPACE/$buildId/",
        "/usr/",
    ]
    try { unstash buildId }
    catch (Exception) { }
    if (!fileExists("${buildId}.success")) {
        echo "Skip coverage report for build ID $buildId due to earlier failure"
        return
    }
    echo "Create coverage report for build ID $buildId"
    // Paths we wish to ignore in the coverage report.
    def excludePathsStr = excludePaths.join(',')
    dir('sources') {
        try {
            withEnv(['ASAN_OPTIONS=verify_asan_link_order=false:detect_leaks=0']) {
                sh """
                    kcov --exclude-path=$excludePathsStr kcov-result $testBinary &> kcov_output.txt
                    find kcov-result -name 'cobertura.xml' -exec mv {} cobertura.xml \\;
                    find kcov-result -name 'coverage.json' -exec mv {} coverage.json \\;
                """
            }
            archiveArtifacts 'cobertura.xml,coverage.json'
            cobertura([
                autoUpdateHealth: false,
                autoUpdateStability: false,
                coberturaReportFile: 'cobertura.xml',
                conditionalCoverageTargets: '70, 0, 0',
                failUnhealthy: false,
                failUnstable: false,
                lineCoverageTargets: '80, 0, 0',
                maxNumberOfBuilds: 0,
                methodCoverageTargets: '80, 0, 0',
                onlyStable: false,
                sourceEncoding: 'ASCII',
                zoomCoverageChart: false,
            ])
            stash includes: 'coverage.json', name: 'coverage-result'
        } catch (Exception e) {
            echo "exception: $e"
            sh 'ls -R .'
            archiveArtifacts 'kcov_output.txt'
        }
    }
}

// Runs a custom sources/scripts/integration/integration.py script for integration tests.
def integrationTests(config, jobName, buildId) {
    def app = "$WORKSPACE/$buildId/bin/${config.integration.binary}"
    // Any error here must not fail the build itself.
    dir('integration-tests') {
        deleteDir()
        try {
            def baseDir = "$WORKSPACE/$buildId/${config.integration.path}"
            def conf_set = config.integration.containsKey('config') ? "$WORKSPACE/sources/${config.integration.config}" : "$baseDir/default_set.yaml"
            def envDir = pwd() + "python-environment"
            writeFile([
                file: 'all-integration-tests.txt',
                text: ''
            ])
            writeFile([
                file: 'failed-integration-tests.txt',
                text: ''
            ])
            sh """
                chmod +x "$app"
                export LD_LIBRARY_PATH="$WORKSPACE/$buildId/lib"
                python3 -m venv "$envDir"
                source "$envDir/bin/activate"
                pip install -r "$baseDir/requirements.txt"
                python "$baseDir/integration.py" -l | while read test ; do
                    echo "\$test" >> all-integration-tests.txt
                    python "$baseDir/integration.py" --app "$app" -t "\$test" -d "integration" -s "$conf_set" || echo "\$test" >> failed-integration-tests.txt
                done
            """
            if (fileExists('integration')) {
                zip([
                    archive: true,
                    dir: 'integration',
                    zipFile: 'integration.zip',
                ])
            }
            archiveArtifacts '*.txt'
            stash([
                includes: '*.txt',
                name: 'integration-result',
            ])
        } catch (Exception e) {
            echo "exception: $e"
        }
    }
}

// Compiles, installs and tests via CMake.
def cmakeSteps(config, jobName, buildId, buildType, cmakeBaseArgs) {
    def installDir = "$WORKSPACE/$buildId"
    def cmakeArgs = config['dependencies']['cmakeRootVariables'].collect {
        "-D$it=\"$installDir\""
    }
    cmakeBaseArgs.each {
      cmakeArgs  << "-D$it"
    }
    cmakeArgs << "-DCMAKE_INSTALL_PREFIX=\"$installDir\""
    dir('sources') {
        // Configure and build.
        cmakeBuild([
            buildDir: 'build',
            buildType: buildType,
            cmakeArgs: cmakeArgs.join(' '),
            installation: 'cmake in search path',
            sourceDir: '.',
            steps: [[
                args: '--target install',
                withCmake: true,
            ]],
        ])
        // Run unit tests.
        try {
            ctest([
                arguments: '--output-on-failure',
                installation: 'cmake in search path',
                workingDir: 'build',
            ])
            writeFile file: "${buildId}.success", text: "success\n"
        } catch (Exception) {
            writeFile file: "${buildId}.failure", text: "failure\n"
        }
        stash includes: "${buildId}.*", name: buildId
    }
    // Only generate artifacts for the master branch.
    if (jobName == 'master') {
        zip([
            archive: true,
            dir: buildId,
            zipFile: "${buildId}.zip",
        ])
    }
}

def unzipAndDelete(buildId) {
    unzip([
        zipFile: "${buildId}.zip",
        dir: "$WORKSPACE/$buildId",
        quiet: true,
    ])
    deleteDir()
}

// Runs all build steps.
def buildSteps(config, jobName, buildId, buildType, cmakeArgs) {
    echo "prepare build steps on stage $STAGE_NAME"
    deleteDir()
    dir(buildId) {
      // Create directory.
    }
    def webDependencies = config['dependencies']['web'] ?: []
    def artifactDependencies = config['dependencies']['artifact'] ?: []
    dir('dependency-import') {
        webDependencies.each {
            sh "curl -O \"$it/${buildId}.zip\""
            unzipAndDelete(buildId)
        }
        artifactDependencies.each {
            copyArtifacts([
                filter: "${buildId}.zip",
                projectName: it,
            ])
            unzipAndDelete(buildId)
        }
    }
    unstash 'sources'
    def buildEnv = buildId.startsWith('Windows_') ? ['PATH=C:\\Windows\\System32;C:\\Program Files\\CMake\\bin;C:\\Program Files\\Git\\cmd;C:\\Program Files\\Git\\bin']
                                                  : ["label_exp=" + STAGE_NAME.toLowerCase(), "ASAN_OPTIONS=detect_leaks=0"]
    withEnv(buildEnv) {
        cmakeSteps(config, jobName, buildId, buildType, cmakeArgs)
    }
}

// Builds a stage for given builds. Results in a parallel stage if `builds.size() > 1`.
def makeStages(config, jobName, matrixIndex, os, builds, lblExpr, extraSteps) {
    builds.collectEntries { buildType ->
        def id = "$matrixIndex $lblExpr: $buildType"
        [
            (id):
            {
                node(lblExpr) {
                    stage(id) {
                        try {
                            def buildId = "${lblExpr}_${buildType}".replace(' && ', '_')
                            withEnv(config['buildEnvironments'][lblExpr] ?: []) {
                              buildSteps(config, jobName, buildId, buildType, (config['buildFlags'][os] ?: config['defaultBuildFlags'])[buildType])
                              extraSteps.each { fun ->
                                if (fun instanceof String)
                                  "$fun"(config, jobName, buildId)
                                else
                                  fun(config, jobName, buildId)
                              }
                            }
                        } finally {
                          cleanWs()
                        }
                    }
                }
            }
        ]
    }
}

return this
