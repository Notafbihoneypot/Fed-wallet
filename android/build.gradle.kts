// Build-tool XML parsing: CVE-2021-33813 is fixed in JDOM 2.0.6.1.
buildscript {
    configurations.getByName("classpath").resolutionStrategy.force("org.jdom:jdom2:2.0.6.1")
}

plugins {
    id("com.android.application") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.force("org.jdom:jdom2:2.0.6.1")
    }
}
