def call(config, result, message) {
    def status = new org.caf.Status()
    config['checks'].each {
        if (it != 'build')
            status.setBuildStatus(config, it, result, message)
    }
}
