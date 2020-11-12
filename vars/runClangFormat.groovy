def call(Map config) {
    deleteDir()
    unstash 'sources'
    dir('sources') {
        def pyScript = libraryResource 'org/caf/clang-format-diff.py'
        writeFile([
            file: 'clang-format-diff.py',
            text: pyScript,
        ])
        sh '''
            chmod +x clang-format-diff.py
            ./clang-format-diff.py -p1 < git_diff.txt > clang-format-diff.txt || touch style-violation-found
        '''
        stash([
            includes: 'clang-format-diff.txt',
            name: 'clang-format-result',
        ])
        archiveArtifacts('clang-format-diff.txt')
        if (fileExists('style-violation-found')) {
            unstable(message: "Style violation found")
        }
    }
}
