plugins {
    java
    // Nếu dùng Gradle 9.x hãy đổi sang bản 9.x của plugin này
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.musicbox"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    // Server API (Paper API chạy tốt trên Leaf / Purpur)
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // Simple Voice Chat API (plugin voicechat phải có trên server)
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")

    // Giải mã MP3 (JLayer, gọi trực tiếp) và OGG (vorbisspi) - được shade vào jar
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3") {
        exclude(group = "junit", module = "junit")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    // Chỉ thay ${version} trong plugin.yml (không đụng tới config.yml)
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Gộp META-INF/services để javax.sound tìm thấy decoder MP3/OGG
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}


