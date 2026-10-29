import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val appVersion = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")

    // Воспроизведение через libVLC (mp3 / m4a / webm / opus), медиаклавиши — через WinAPI (JNA)
    implementation("uk.co.caprica:vlcj:4.12.1")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

/*
 * VLC едет вместе с установщиком: libvlc + только аудио-плагины из установленного VLC.
 * Путь можно переопределить: ./gradlew -PvlcDir=D:/VLC :desktop:packageMsi
 */
val vlcDir = (findProperty("vlcDir") as String?) ?: "C:/Program Files/VideoLAN/VLC"
val vlcResources = layout.buildDirectory.dir("vlc-resources")
val prepareVlc by tasks.registering(Copy::class) {
    from(vlcDir) {
        include("libvlc.dll", "libvlccore.dll")
        include(
            "plugins/access/**", "plugins/audio_filter/**", "plugins/audio_mixer/**", "plugins/audio_output/**",
            "plugins/codec/**", "plugins/demux/**", "plugins/packetizer/**", "plugins/stream_filter/**",
            "plugins/logger/**", "plugins/misc/**",
        )
        // Видео-, субтитро- и аппаратные декодеры плееру музыки не нужны
        exclude("plugins/codec/*d3d*", "plugins/codec/*dxva*", "plugins/codec/*subs*", "plugins/codec/*ttml*",
            "plugins/codec/*libass*", "plugins/codec/*x26*", "plugins/codec/*dav1d*", "plugins/codec/*aom*",
            "plugins/codec/*libmpeg2*", "plugins/codec/*qsv*", "plugins/codec/*schroedinger*", "plugins/codec/*theora*",
            "plugins/codec/*zvbi*", "plugins/codec/*telx*", "plugins/codec/*cc_*", "plugins/codec/*svg*")
    }
    into(vlcResources.map { it.dir("windows") })
}

compose.desktop {
    application {
        mainClass = "com.musichub.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx512m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Unison"
            packageVersion = appVersion
            // Только ASCII: MSI собирается в кодовой странице 1252, кириллица ломает WiX (LGHT0311)
            description = "Unison - Yandex Music, YouTube and VK in one player"
            vendor = "Vexorter42"
            copyright = "GPL-3.0"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(vlcResources)
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.scripting", "java.sql",
                "jdk.crypto.ec", "jdk.httpserver", "jdk.unsupported", "jdk.zipfs", "jdk.accessibility")

            windows {
                iconFile.set(project.file("icons/unison.ico"))
                menuGroup = "Unison"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // Постоянный код обновления: новая версия ставится поверх старой
                upgradeUuid = "6f3c2d0e-8a4b-4f1e-9a57-2b6c1d7e5a31"
            }
        }
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("package") || it.name.startsWith("createDistributable") || it.name == "prepareAppResources" }
        .configureEach { dependsOn(prepareVlc) }
}

// Живая проверка без интерфейса: ./gradlew :desktop:smokeCheck -Pquery="Кино Группа крови"
tasks.register<JavaExec>("smokeCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.musichub.SmokeCheckKt")
    args((findProperty("query") as String?) ?: "Кино Группа крови")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// Проверка интерфейса без окна: поиск → клик по треку → играет, скриншоты в build/ui-check
tasks.register<JavaExec>("uiCheck") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.musichub.UiCheckKt")
    args(layout.buildDirectory.dir("ui-check").get().asFile.absolutePath, (findProperty("query") as String?) ?: "Кино Группа крови")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
