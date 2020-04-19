package org.caf

// Creates coverage reports via kcov and the Cobertura plugin.
def coverageReport(config, jobName, buildId) {
    def settings = config['coverage']
    def testBinaries = settings.containsKey('binary') ? [settings['binary']] : settings['binaries']
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
            testBinaries.each{testBinary ->
                withEnv(['ASAN_OPTIONS=verify_asan_link_order=false:detect_leaks=0']) {
                    sh """
                        kcov --exclude-path=$excludePathsStr kcov-result $testBinary &> kcov_output.txt
                    """
                }
            }
            sh """
                find kcov-result -name 'cobertura.xml' -exec mv {} cobertura.xml \\;
                find kcov-result -name 'coverage.json' -exec mv {} coverage.json \\;
            """
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
            unstable(message: "Unable to create coverage report")
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
            sh """
                chmod +x "$app"
                export LD_LIBRARY_PATH="$WORKSPACE/$buildId/lib"
                python3 -m venv "$envDir"
                source "$envDir/bin/activate"
                pip install -r "$baseDir/requirements.txt"
                python "$baseDir/integration.py" -l | while read test ; do
                    echo "\$test" >> all-integration-tests.txt
                    python "$baseDir/integration.py" --app "$app" -t "\$test" -d "integration" -s "$conf_set" &> "\$test.txt" || echo "\$test" >> failed-integration-tests.txt
                done
            """
            if (fileExists('integration')) {
                zip([
                    archive: true,
                    dir: 'integration',
                    zipFile: 'integration.zip',
                ])
            }
            if (fileExists('failed-integration-tests.txt')) {
                unstable(message: "One or more integration tests failed");
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
def cmakeSteps(config, jobName, jobSettings, buildId, buildType, cmakeBaseArgs) {
    echo "Run CMake for build ID $buildId on node $NODE_NAME"
    def installDir = "$WORKSPACE/$buildId"
    def cmakeArgs = []
    def cmakeInstallation = env.CMAKE_INSTALLATION ?: 'cmake in search path'
    echo "using CMake installation: ${cmakeInstallation}"
    if (config.containsKey('dependencies')) {
        cmakeArgs = config['dependencies']['cmakeRootVariables'].collect {
            "-D$it=\"$installDir\""
        }
    }
    cmakeBaseArgs.each {
      cmakeArgs  << "-D$it"
    }
    cmakeArgs << "-DCMAKE_INSTALL_PREFIX=\"$installDir\""
    def cmakeBuildArgs = [
        "--target install",
        "--config $buildType",
    ]
    if (jobSettings.containsKey('numCores')) {
        // On UNIX, we use the (default) Makefile generator and set -j
        // On MSVC, we need to set a compiler flag for parallel builds.
        if (isUnix()) {
            cmakeBuildArgs << "-j ${jobSettings.numCores}"
        } else {
            cmakeArgs << "-DCMAKE_CXX_FLAGS:STRING=/MP${jobSettings.numCores}"
        }
    }
    dir('sources') {
        // Configure and build.
        cmakeBuild([
            buildDir: 'build',
            buildType: buildType,
            cmakeArgs: cmakeArgs.join(' '),
            installation: cmakeInstallation,
            sourceDir: '.',
            steps: [[
                args: cmakeBuildArgs.join(' '),
                withCmake: true,
            ]],
        ])
        // Run unit tests.
        try {
            def pathVar = "${env.PATH}"
            if (!isUnix()) {
                // On Windows, we need to add the .dll folder to the PATH.
                pathVar = "$pathVar;$installDir\\bin;$installDir\\lib"
            }
            withEnv(["PATH=$pathVar"]) {
                ctest([
                    arguments: "--output-on-failure -C $buildType",
                    installation: cmakeInstallation,
                    workingDir: 'build',
                ])
            }
            writeFile file: "${buildId}.success", text: "success\n"
        } catch (Exception) {
            writeFile file: "${buildId}.failure", text: "failure\n"
            unstable(message: "One or more unit tests failed")
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
def buildSteps(config, jobName, jobSettings, buildId, buildType, cmakeArgs) {
    echo "prepare build steps on stage $STAGE_NAME"
    deleteDir()
    dir(buildId) {
      // Create directory.
    }
    if (config.containsKey('dependencies')) {
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
    }
    unstash 'sources'
    def buildEnv = buildId.startsWith('Windows_') ? ['PATH=C:\\Windows\\System32;C:\\Program Files\\CMake\\bin;C:\\Program Files\\Git\\cmd;C:\\Program Files\\Git\\bin']
                                                  : ["label_exp=" + STAGE_NAME.toLowerCase(), "ASAN_OPTIONS=detect_leaks=0"]
    withEnv(buildEnv) {
        cmakeSteps(config, jobName, jobSettings, buildId, buildType, cmakeArgs)
    }
}

def dockerBuild(config, imageName, jobSettings, buildId, buildType, flags) {
    echo "Run ${imageName} Dockerfile on node $NODE_NAME"
    unstash 'sources'
    flags << "CMAKE_INSTALL_PREFIX:PATH=$WORKSPACE/bundle"
    flags << "CMAKE_BUILD_TYPE:STRING=$buildType"
    def envVars = config['buildEnvironments'][imageName] ?: []
    if (jobSettings.containsKey('numCores'))
        envVars << "CAF_NUM_CORES=${jobSettings.numCores}"
    def rx = /^([a-zA-Z0-9_]+):([a-zA-Z0-9_]+)=(.+)$/
    def cmakeVarsBuilder = new StringBuilder()
    flags.each {
        def res = (it =~ rx)
        if(res.matches())
          cmakeVarsBuilder << """set(${res.group(1)} "${res.group(3)}" CACHE ${res.group(2)} "")\n"""
        else
          throw new RuntimeException("Invalid CMake syntax: $it")
    }
    writeFile([
        file: 'cmake-init.txt',
        text: cmakeVarsBuilder.toString(),
    ])
    withEnv(envVars) {
        def image = docker.build(imageName, "sources/.ci/${imageName}")
        image.inside {
            sh './sources/.ci/build.sh'
        }
    }
}

// Builds a stage for given builds. Results in a parallel stage if `builds.size() > 1`.
def makeStages(config, jobName, jobSettings, matrixIndex, os, builds, lblExpr, extraSteps) {
    builds.collectEntries { buildType ->
        def isDockerBuild = (jobSettings['tags'] ?: []).contains('docker')
        def nodeLbl = isDockerBuild ? 'docker' : lblExpr
        def id = "$matrixIndex $lblExpr: $buildType"
        [
            (id):
            {
                node(nodeLbl) {
                    stage(id) {
                        def baseFlags = (config['buildFlags'][os] ?: config['defaultBuildFlags'])[buildType]
                        def flags = baseFlags + (jobSettings['extraFlags'] ?: []) + (jobSettings["extra${buildType.capitalize()}Flags"] ?: [])
                        def buildId = "${lblExpr}_${buildType}".replace(' && ', '_')
                        def stageImpl = {
                            withEnv(config['buildEnvironments'][lblExpr] ?: []) {
                              buildSteps(config, jobName, jobSettings, buildId, buildType, flags)
                              extraSteps.each { fun ->
                                  if (fun instanceof String)
                                      "$fun"(config, jobName, buildId)
                                  else
                                      fun(config, jobName, buildId)
                              }
                            }
                        }
                        try {
                            if (isDockerBuild) {
                                dockerBuild(config, lblExpr, jobSettings, buildId, buildType, flags)
                            } else {
                                stageImpl()
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
