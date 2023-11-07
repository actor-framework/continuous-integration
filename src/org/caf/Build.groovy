package org.caf

// Creates coverage reports via kcov and the Cobertura plugin.
def coverageReport(config, jobName, buildId) {
    def settings = config['coverage']
    def testBinaries = settings.containsKey('binary') ? [settings['binary']] : settings['binaries']
    def excludePaths = settings['relativeExcludePaths'].collect { "${pwd()}/sources/$it" } + [
        "${pwd()}/$buildId/",
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

def unixBuild(os, buildType, settings, buildId, index) {
    def flags = settings['flags'] + [
        "CAF_BUILD_INFO_FILE_PATH=${pwd()}/build-${index}.info",
        "CMAKE_INSTALL_PREFIX=${pwd()}/${buildId}"
    ]
    def numCores = settings['numCores'] ?: 1
    withEnv(settings['env']) {
        cmakeBuild([
            installation: 'cmake in search path',
            sourceDir: 'sources',
            buildDir: 'build',
            buildType: buildType,
            cmakeArgs: flags.collect { "-D$it" }.join(' '),
            steps: [[
                args: "--target install -j $numCores",
                withCmake: true,
            ]],
        ])
        warnError('Unit Tests failed!') {
            ctest([
                installation: 'cmake in search path',
                arguments: "--output-on-failure",
                workingDir: 'build',
            ])
            writeFile file: "build-${index}.success", text: "success\n"
        }
    }
}

def msvcBuild(os, buildType, settings, buildId, index) {
    def installDir = "${pwd()}/${buildId}"
    def extraPaths = [
        "C:/Windows/System32",
        "C:/Program Files/CMake/bin",
        "C:/Program Files/Git/cmd",
        "C:/Program Files/Git/bin",
        "$installDir/lib",
        "$installDir/bin",
    ].collect { it.replace('/', '\\') }
    def buildEnv = settings['env'] + ['PATH=' + extraPaths.join(';')]
    def numCores = settings['numCores'] ?: 1
    def mpFlags = numCores < 2 ? [] : ["CMAKE_CXX_FLAGS:STRING=/MP$numCores"]
    def flags = settings['flags'] + mpFlags + [
        "CAF_BUILD_INFO_FILE_PATH=${pwd()}/build-${index}.info",
        "CMAKE_INSTALL_PREFIX=$installDir"
    ]
    withEnv(buildEnv) {
        cmakeBuild([
            installation: 'cmake in search path',
            sourceDir: 'sources',
            buildDir: 'build',
            cmakeArgs: flags.collect { "-D$it" }.join(' '),
            steps: [[
                args: "--target install --config $buildType",
                withCmake: true,
            ]],
        ])
        warnError('Unit Tests failed!') {
            ctest([
                installation: 'cmake in search path',
                arguments: "--output-on-failure -C $buildType",
                workingDir: 'build',
            ])
            writeFile file: "build-${index}.success", text: "success\n"

        }
    }
}

// Runs all build steps.
def cmakeBuild(os, buildType, settings, index) {
    def buildId = ([os, buildType] + settings['tags']).join('_')
    echo "Run CMake build $buildId on node $NODE_NAME"
    if (!settings['tags'].isEmpty())
        error('unsupported tags: ' + settings['tags'].toString())
    dir(buildId) {
      // Create directory.
    }
    if (isUnix())
        unixBuild(os, buildType, settings, buildId, index)
    else
        msvcBuild(os, buildType, settings, buildId, index)
    stash includes: "build-${index}.*", name: "build-${index}"
}

def dockerBuild(osConfig, buildType, settings, index) {
    def imageName = osConfig.split(':')[0]
    def buildId = "${imageName}_${buildType}"
    echo "Run Docker build for image ${imageName} on node $NODE_NAME"
    def baseDir = pwd()
    def initFile = "$baseDir/init.cmake"
    def sourceDir = "$baseDir/sources"
    def buildDir = "$baseDir/build"
    def installDir = "$baseDir/$buildId"
    def rx = /^([a-zA-Z0-9_]+):([a-zA-Z0-9_]+)=(.+)$/
    def init = new StringBuilder()
    settings['flags'].each {
        def res = (it =~ rx)
        if (!res.matches())
            throw new RuntimeException("Invalid CMake syntax: $it")
        def varName = res.group(1)
        def varType = res.group(2)
        def value = res.group(3)
        init << """set($varName "$value" CACHE $varType "")\n"""
    }
    init << """set(CAF_BUILD_INFO_FILE_PATH "$baseDir/build-${index}.info" CACHE FILEPATH "")\n""" \
         << """set(CMAKE_INSTALL_PREFIX "$installDir" CACHE PATH "")\n""" \
         << """set(CMAKE_BUILD_TYPE "$buildType" CACHE STRING "")\n"""
    writeFile([
        file: 'init.cmake',
        text: init.toString()
    ])
    dir(buildId) {
      // Create directory.
    }
    def numCores = settings['numCores'] ?: 1
    def extraEnv = numCores > 1 ? ["CAF_NUM_CORES=$numCores"] : []
    withEnv(settings['env'] + extraEnv) {
        def image = docker.build(imageName, "-f ${sourceDir}/.ci/${imageName}/Dockerfile ${sourceDir}")
        image.inside("--cap-add SYS_PTRACE") {
            settings['tags'].each {
                if (it != 'docker')
                    sh "./sources/.ci/run.sh assert $it"
            }
            sh "./sources/.ci/run.sh build '$initFile' '$sourceDir' '$buildDir'"
            warnError('Unit Tests failed!') {
                sh "./sources/.ci/run.sh test '$buildDir'"
                writeFile file: "build-${index}.success", text: "success\n"
            }
            warnError('Extra script failed!') {
                settings['extraScripts'].each {
                    sh "$it"
                }
            }
        }
        stash includes: "build-${index}.*", name: "build-${index}"
    }
}

def runStage(int index) {
    echo "Run parallel stage on $NODE_NAME, working dir: ${pwd()}"
    deleteDir()
    unstash 'sources'
    def buildMatrix = readJSON file: 'sources/build-matrix.json'
    (os, buildType, settings) = buildMatrix[index]
    if (settings['tags'].contains('docker'))
        dockerBuild(os, buildType, settings, index)
    else
        cmakeBuild(os, buildType, settings, index)
}

def makeStage(int index, String label, String os) {
    [
        "$label": {
            node(os) {
                runStage(index)
            }
        }
    ]
}

return this
