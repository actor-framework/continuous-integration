def call(config, jobName) {
    def build = new org.caf.Build()
    // Create stages for building everything in our build matrix in parallel.
    def xs = [:]
    config['buildMatrix'].eachWithIndex { entry, index ->
        def (os, settings) = entry
        if (settings.containsKey('tools')) {
            settings['tools'].eachWithIndex { tool, toolIndex ->
                def matrixIndex = "[$index:$toolIndex]"
                def builds = settings['builds']
                def labelExpr = "$os && $tool"
                xs << build.makeStages(config, jobName, settings, matrixIndex, os, builds, labelExpr, settings['extraSteps'] ?: [])
            }
        } else {
            def matrixIndex = "[$index]"
            def builds = settings['builds']
            def labelExpr = "$os"
            xs << build.makeStages(config, jobName, settings, matrixIndex, os, builds, labelExpr, settings['extraSteps'] ?: [])
        }
    }
    parallel xs
}
