def call(config) {
    deleteDir()
    unstash 'sources'
    def stages = [:]
    def factory = new org.caf.Build()
    def buildMatrix = readJSON file: 'sources/build-matrix.json'
    for (int index = 0; index < buildMatrix.size(); ++index) {
        (os, buildType, settings) = buildMatrix[index]
        def indexStr = "$index".padLeft(2, '0')
        def label = "[$indexStr] $os $buildType"
        def isDockerBuild = settings['tags'].contains('docker')
        stages << factory.makeStage(index, label, isDockerBuild ? 'docker' : os)
    }
    parallel stages
}
