def values = new Properties()
new File(basedir, 'target/rethinkj-test/project.properties').withReader('UTF-8') { values.load(it) }

assert values['jvm.args'] == '-Xmx256m -Dlate=yes'
assert new File(values['system.cache']).canonicalPath == new File(basedir, 'target/cache').canonicalPath
assert new File(values['testClasses']).canonicalPath == new File(basedir, 'target/test-classes').canonicalPath
assert values['release'] == '25'
assert new File(values['testResources']).canonicalPath == new File(basedir, 'src/test/resources').canonicalPath
assert values['classpath'].contains('junit-jupiter-api-6.1.3.jar')
assert values['launcher'].endsWith('junit-platform-launcher-6.1.3.jar')
assert new File(values['launcher']).isFile()
